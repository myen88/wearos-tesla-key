package com.myen88.wearkey.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.tiles.TileService
import com.myen88.wearkey.ble.TeslaBleClient
import com.myen88.wearkey.data.WatchKeyStore
import com.myen88.wearkey.domain.VehicleCommander
import com.myen88.wearkey.keystore.AndroidKeystoreKey
import com.myen88.wearkey.protocol.KeyId
import com.myen88.wearkey.protocol.Vin
import com.myen88.wearkey.protocol.crypto.ProtocolException
import com.myen88.wearkey.protocol.message.LockSnapshot
import com.myen88.wearkey.session.AutoKeyBus
import com.myen88.wearkey.session.AutoKeyService
import com.myen88.wearkey.session.CommandForegroundService
import com.myen88.wearkey.tile.KeyTileService
import com.tesla.generated.vcsec.Vcsec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class Screen { PERMISSION, ONBOARDING, PAIRING, DASHBOARD }

data class UiState(
    val screen: Screen = Screen.ONBOARDING,
    val vin: String = "",
    val vinError: String? = null,
    val androidFormFactor: Boolean = true,
    val fingerprint: String = "",
    val keyIsHardwareOnly: Boolean = true,
    val busy: Boolean = false,
    val lockLabel: String = "Lock unknown",
    val asleep: Boolean? = null,
    val trunkOpen: Boolean = false,
    val frunkOpen: Boolean = false,
    val batteryLabel: String = "Battery —",
    val banner: String? = null,
    val bannerIsError: Boolean = false,
    val confirmClosure: String? = null,
    val confirmForget: Boolean = false,
    val autoKey: Boolean = false,
    val rssiFallback: Boolean = true,
    val nearRssi: Int = -65,
    val farRssi: Int = -85,
    val farHoldSeconds: Int = 8,
    val liveRssi: String = "RSSI —",
)

class WatchKeyViewModel(app: Application) : AndroidViewModel(app) {
    private val store = WatchKeyStore(app)
    private val key = AndroidKeystoreKey()
    private val commander = VehicleCommander(TeslaBleClient(app), key, store)
    private val gate = Mutex()
    private val wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchkey:command")
        .apply { setReferenceCounted(false) }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AutoKeyBus.detail.collect { detail ->
                val rssi = AutoKeyBus.rssi.value
                val smoothed = AutoKeyBus.smoothed.value
                val live = if (rssi == Int.MIN_VALUE) {
                    detail
                } else {
                    "RSSI $rssi   avg $smoothed"
                }
                _state.value = _state.value.copy(liveRssi = live)
            }
        }
    }

    fun onPermissionsChanged() {
        _state.value = initialState().copy(banner = _state.value.banner, bannerIsError = _state.value.bannerIsError)
    }

    fun setVinDraft(value: String) {
        _state.value = _state.value.copy(vin = value.uppercase().take(17), vinError = null)
    }

    fun toggleFormFactor() {
        if (_state.value.busy) return
        val next = !_state.value.androidFormFactor
        store.androidFormFactor = next
        _state.value = _state.value.copy(androidFormFactor = next)
    }

    fun continueFromVin() {
        val error = Vin.validationError(_state.value.vin)
        if (error != null) {
            _state.value = _state.value.copy(vinError = error)
            return
        }
        val vin = Vin.normalize(_state.value.vin)
        store.vin = vin
        store.enrolled = false
        store.clearSessions()
        try {
            key.generate()
        } catch (error: ProtocolException) {
            _state.value = _state.value.copy(banner = error.message, bannerIsError = true)
            return
        }
        _state.value = pairingState(vin)
    }

    fun startPairing() {
        val vin = store.vin ?: return
        val form = if (_state.value.androidFormFactor) {
            Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE
        } else {
            Vcsec.KeyFormFactor.KEY_FORM_FACTOR_CLOUD_KEY
        }
        launchCommand("Pairing…") {
            val detail = commander.pair(vin, form) { phase ->
                _state.value = _state.value.copy(banner = phase, bannerIsError = false)
            }
            store.enrolled = true
            _state.value = dashboardState(vin).copy(banner = detail, bannerIsError = false)
            val reading = commander.refresh(vin)
            applyReading(reading.lock, reading.batteryPercent, reading.note ?: detail, reading.note != null)
        }
    }

    fun refresh() = launchCommand("Reading the car…") {
        val reading = commander.refresh(requireVin())
        applyReading(
            reading.lock,
            reading.batteryPercent,
            reading.note ?: "Updated.",
            reading.note != null,
        )
    }

    fun lock() = launchCommand("Locking…") {
        applyReading(commander.lock(requireVin()), battery = null, banner = "Locked.")
    }

    fun unlock() = launchCommand("Unlocking…") {
        applyReading(commander.unlock(requireVin()), battery = null, banner = "Unlocked.")
    }

    fun trunk() = confirmThen("trunk", "Tap again to open the trunk.") {
        launchCommand("Opening the trunk…") {
            applyReading(commander.openTrunk(requireVin()), battery = null, banner = "Trunk command sent.")
        }
    }

    fun frunk() = confirmThen("frunk", "Tap again to open the frunk.") {
        launchCommand("Opening the frunk…") {
            applyReading(commander.openFrunk(requireVin()), battery = null, banner = "Frunk command sent.")
        }
    }

    fun drive() = confirmThen("drive", "Tap again for remote start. Use a PIN to Drive on the car.") {
        launchCommand("Starting…") {
            commander.drive(requireVin())
            _state.value = _state.value.copy(banner = "Drive command sent.", bannerIsError = false)
        }
    }

    fun setAutoKeyEnabled(enabled: Boolean) {
        if (enabled && (!store.enrolled || store.vin.isNullOrBlank())) {
            _state.value = _state.value.copy(banner = "Pair the watch before turning Auto key on.", bannerIsError = true)
            return
        }
        store.autoKey = enabled
        val app = getApplication<Application>()
        if (enabled) AutoKeyService.start(app) else AutoKeyService.stop(app)
        _state.value = _state.value.copy(autoKey = enabled, banner = if (enabled) "Auto key on." else "Auto key off.", bannerIsError = false)
        updateTile()
    }

    fun toggleRssiFallback() {
        val next = !store.rssiFallback
        store.rssiFallback = next
        _state.value = _state.value.copy(rssiFallback = next)
    }

    fun stepNear() {
        val next = if (store.nearRssi >= -48) -78 else store.nearRssi + 3
        store.nearRssi = next
        if (store.farRssi >= next) store.farRssi = next - 12
        _state.value = _state.value.copy(nearRssi = store.nearRssi, farRssi = store.farRssi)
    }

    fun stepFar() {
        val next = if (store.farRssi <= -100) store.nearRssi - 8 else store.farRssi - 3
        store.farRssi = next.coerceAtMost(store.nearRssi - 5)
        _state.value = _state.value.copy(farRssi = store.farRssi)
    }

    fun stepHold() {
        val next = when (store.farHoldSeconds) {
            5 -> 8
            8 -> 12
            12 -> 20
            else -> 5
        }
        store.farHoldSeconds = next
        _state.value = _state.value.copy(farHoldSeconds = next)
    }

    fun climate(on: Boolean) = launchCommand(if (on) "Turning climate on…" else "Turning climate off…") {
        commander.climate(requireVin(), on)
        _state.value = _state.value.copy(
            banner = if (on) "Climate on." else "Climate off.",
            bannerIsError = false,
        )
    }

    fun requestForget() {
        _state.value = _state.value.copy(
            confirmForget = true,
            confirmClosure = null,
            banner = "This removes the key from the watch only. Delete it in the car's Locks screen too.",
            bannerIsError = false,
        )
    }

    fun forget() {
        key.delete()
        store.enrolled = false
        store.clearSessions()
        store.lastLock = null
        store.lastBattery = null
        updateTile()
        _state.value = initialState().copy(banner = "Key removed from this watch.", bannerIsError = false)
    }

    fun onExternalCommand(command: String?) {
        if (command == null) return
        if (_state.value.screen != Screen.DASHBOARD) {
            _state.value = _state.value.copy(
                banner = "Pair the watch before the shortcut can run.",
                bannerIsError = true,
            )
            return
        }
        when (command) {
            "unlock" -> unlock()
            "lock" -> lock()
            "auto" -> setAutoKeyEnabled(!store.autoKey)
        }
    }

    private fun confirmThen(id: String, prompt: String, action: () -> Unit) {
        if (_state.value.confirmClosure == id) {
            _state.value = _state.value.copy(confirmClosure = null)
            action()
        } else {
            _state.value = _state.value.copy(confirmClosure = id, confirmForget = false, banner = prompt, bannerIsError = false)
        }
    }

    private fun launchCommand(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!gate.tryLock()) {
                _state.value = _state.value.copy(banner = "Wait for the current command to finish.", bannerIsError = true)
                return@launch
            }
            beginForeground(label)
            _state.value = _state.value.copy(busy = true, banner = label, bannerIsError = false, confirmClosure = null)
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    banner = error.message?.ifBlank { null } ?: "Something went wrong.",
                    bannerIsError = true,
                )
            } finally {
                endForeground()
                _state.value = _state.value.copy(busy = false)
                gate.unlock()
            }
        }
    }

    private fun applyReading(lock: LockSnapshot, battery: Int?, banner: String, bannerIsError: Boolean = false) {
        store.lastLock = lock.label
        if (battery != null) store.lastBattery = "$battery%"
        updateTile()
        _state.value = _state.value.copy(
            screen = Screen.DASHBOARD,
            lockLabel = lock.label,
            asleep = lock.asleep,
            trunkOpen = lock.trunkOpen,
            frunkOpen = lock.frunkOpen,
            batteryLabel = when {
                battery != null -> "Battery $battery%"
                store.lastBattery != null -> "Battery ${store.lastBattery}"
                else -> "Battery —"
            },
            banner = banner,
            bannerIsError = bannerIsError,
        )
    }

    private fun requireVin(): String = store.vin ?: throw ProtocolException("Enter the VIN first.")

    private fun initialState(): UiState {
        val permitted = hasBluetoothPermission()
        val vin = store.vin.orEmpty()
        val keyReady = runCatching { key.exists() }.getOrDefault(false)
        val enrolled = store.enrolled && keyReady && vin.isNotEmpty()
        val screen = when {
            !permitted -> Screen.PERMISSION
            enrolled -> Screen.DASHBOARD
            keyReady && vin.isNotEmpty() -> Screen.PAIRING
            else -> Screen.ONBOARDING
        }
        val fingerprint = if (keyReady) runCatching { KeyId.fingerprint(key.publicUncompressed()) }.getOrDefault("") else ""
        val hardwareOnly = if (keyReady) runCatching { !key.isPrivateKeyExportable() }.getOrDefault(true) else true
        return UiState(
            screen = screen,
            vin = vin,
            androidFormFactor = store.androidFormFactor,
            fingerprint = fingerprint,
            keyIsHardwareOnly = hardwareOnly,
            lockLabel = store.lastLock ?: "Lock unknown",
            batteryLabel = store.lastBattery?.let { "Battery $it" } ?: "Battery —",
            autoKey = store.autoKey,
            rssiFallback = store.rssiFallback,
            nearRssi = store.nearRssi,
            farRssi = store.farRssi,
            farHoldSeconds = store.farHoldSeconds,
        )
    }

    private fun pairingState(vin: String): UiState {
        val fingerprint = KeyId.fingerprint(key.publicUncompressed())
        return _state.value.copy(
            screen = Screen.PAIRING,
            vin = vin,
            vinError = null,
            fingerprint = fingerprint,
            keyIsHardwareOnly = !key.isPrivateKeyExportable(),
            banner = "Stand next to the car, then start pairing.",
            bannerIsError = false,
        )
    }

    private fun dashboardState(vin: String) = _state.value.copy(
        screen = Screen.DASHBOARD,
        vin = vin,
        fingerprint = runCatching { KeyId.fingerprint(key.publicUncompressed()) }.getOrDefault(""),
    )

    private fun hasBluetoothPermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun beginForeground(text: String) {
        val app = getApplication<Application>()
        val intent = Intent(app, CommandForegroundService::class.java)
            .putExtra(CommandForegroundService.EXTRA_TEXT, text)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            // The activity is already on screen. The command can still run.
        }
        if (!wakeLock.isHeld) wakeLock.acquire(60_000L)
    }

    private fun endForeground() {
        if (wakeLock.isHeld) wakeLock.release()
        val app = getApplication<Application>()
        app.stopService(Intent(app, CommandForegroundService::class.java))
    }

    private fun updateTile() {
        TileService.getUpdater(getApplication()).requestUpdate(KeyTileService::class.java)
    }

    override fun onCleared() {
        if (wakeLock.isHeld) wakeLock.release()
        super.onCleared()
    }
}
