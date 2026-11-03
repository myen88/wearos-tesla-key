package com.myen88.wearkey.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.myen88.wearkey.BuildConfig
import com.myen88.wearkey.protocol.TeslaProtocol
import com.myen88.wearkey.protocol.ble.Advertisement
import com.myen88.wearkey.protocol.ble.BleFrames
import com.myen88.wearkey.protocol.ble.ProtocolFramingException
import com.myen88.wearkey.protocol.ble.Reassembler
import com.myen88.wearkey.protocol.crypto.ProtocolException
import com.myen88.wearkey.protocol.randomBytes
import com.myen88.wearkey.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class Decision { CONTINUE, DONE }

interface VehicleLink {
    /** Stable routing address for infotainment. VCSEC uses a fresh address per command. */
    val infotainmentRouting: ByteArray

    suspend fun exchange(
        request: ByteArray,
        timeout: Duration,
        accept: (ByteArray) -> Decision,
    ): ByteArray

    suspend fun poll(timeout: Duration): ByteArray?

    suspend fun readRssi(): Int

    suspend fun send(bytes: ByteArray)

    fun isConnected(): Boolean

    suspend fun preferLowPower()
}

class TeslaBleClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun <T> use(vin: String, patient: Boolean = false, block: suspend (VehicleLink) -> T): T {
        val link = connect(vin, patient)
        try {
            return block(link)
        } finally {
            link.close()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(vin: String, patient: Boolean): GattLink {
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: throw ProtocolException("This watch has no Bluetooth radio.")
        val adapter = manager.adapter ?: throw ProtocolException("This watch has no Bluetooth radio.")
        if (!adapter.isEnabled) {
            throw ProtocolException("Turn on Bluetooth in the watch settings.")
        }
        requireScanEnvironment()
        val name = Advertisement.localName(vin)
        Log.i(TAG, "Scanning for the vehicle advertisement")
        val tally = ScanTally()
        val device = if (!patient) {
            scanCollect(adapter, name, ScanSettings.SCAN_MODE_LOW_LATENCY, 18.seconds, tally)
        } else {
            scanCollect(adapter, name, ScanSettings.SCAN_MODE_LOW_LATENCY, 8.seconds, tally)
                ?: scanCollect(adapter, name, ScanSettings.SCAN_MODE_LOW_POWER, 20.seconds, tally)
        } ?: throw ProtocolException(
            Advertisement.describeScanMiss(tally.deviceCount, tally.teslaNames(), name),
        )
        val link = GattLink(appContext, device)
        try {
            link.open()
        } catch (error: Throwable) {
            link.close()
            throw error
        }
        return link
    }

    suspend fun survey(vin: String?): ScanSurvey {
        requireScanEnvironment()
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: throw ProtocolException("This watch has no Bluetooth radio.")
        val adapter = manager.adapter ?: throw ProtocolException("This watch has no Bluetooth radio.")
        if (!adapter.isEnabled) throw ProtocolException("Turn on Bluetooth in the watch settings.")
        val expected = if (vin.isNullOrBlank()) "" else Advertisement.localName(vin)
        val tally = ScanTally()
        scanCollect(adapter, expected, ScanSettings.SCAN_MODE_LOW_LATENCY, 15.seconds, tally)
        return ScanSurvey(
            deviceCount = tally.deviceCount,
            sightings = tally.sightings(),
            ibeaconWithoutTeslaName = tally.ibeaconWithoutName,
            expectedName = expected,
        )
    }

    private fun requireScanEnvironment() {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            throw ProtocolException(
                "Allow location. Android hides the car's Bluetooth name until this app has location permission.",
            )
        }
        val locations = appContext.getSystemService(LocationManager::class.java)
        if (locations != null && !locations.isLocationEnabled) {
            throw ProtocolException(
                "Location is off. Turn it on in watch settings. Android hides the car's advertisement while Location is off.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanCollect(
        adapter: BluetoothAdapter,
        expected: String,
        mode: Int,
        timeout: kotlin.time.Duration,
        tally: ScanTally,
    ): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner
            ?: throw ProtocolException("Bluetooth scanning is unavailable.")
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (tally.consider(result, expected) && continuation.isActive) {
                            try {
                                scanner.stopScan(this)
                            } catch (_: SecurityException) {
                            }
                            continuation.resume(result.device)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(ProtocolException(scanFailure(errorCode)))
                        }
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(mode)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build()
                try {
                    scanner.startScan(null, settings, callback)
                } catch (error: SecurityException) {
                    continuation.resumeWithException(ProtocolException("Bluetooth permission was denied.", error))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    try {
                        scanner.stopScan(callback)
                    } catch (_: SecurityException) {
                    }
                }
            }
        }
    }

    private fun scanFailure(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Bluetooth scan failed. Toggle Bluetooth on the watch and try again."
        else -> "Bluetooth scan failed (code $code)."
    }

    companion object {
        const val TAG = "WatchKey"
    }
}

@SuppressLint("MissingPermission")
private class GattLink(
    private val context: Context,
    private val device: BluetoothDevice,
) : VehicleLink {
    override val infotainmentRouting: ByteArray = randomBytes(TeslaProtocol.ADDRESS_LENGTH)

    private val thread = HandlerThread("tesla-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val events = Channel<GattEvent>(Channel.BUFFERED)
    private val incoming = Channel<ByteArray>(capacity = 32)
    private val reassembler = Reassembler()
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var gatt: BluetoothGatt? = null
    private var rssiSlot: CompletableDeferred<Int>? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writeSlot: CompletableDeferred<Int>? = null
    private var mtu = 23
    private var lastRxNanos = 0L

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connected.set(newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS)
            events.trySend(GattEvent.Connection(status, newState))
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            rssiSlot?.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else Int.MIN_VALUE)
            rssiSlot = null
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            events.trySend(GattEvent.Services(status))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@GattLink.mtu = mtu
            }
            events.trySend(GattEvent.Mtu(status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            events.trySend(GattEvent.Descriptor(status))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeSlot?.complete(status)
            writeSlot = null
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val now = System.nanoTime()
            if (lastRxNanos != 0L && now - lastRxNanos > 1_000_000_000L) {
                reassembler.reset()
            }
            lastRxNanos = now
            try {
                reassembler.push(value).forEach { frame ->
                    if (BuildConfig.DEBUG) {
                        Log.d(TeslaBleClient.TAG, "RX ${frame.size} bytes ${frame.toHex().take(80)}")
                    }
                    incoming.trySend(frame)
                }
            } catch (error: ProtocolFramingException) {
                incoming.close(error)
            }
        }
    }

    suspend fun open() {
        gatt = onGatt {
            device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
        } ?: throw ProtocolException("The watch could not open a Bluetooth connection.")
        await(15.seconds, "Could not connect to the car. Move closer and try again.") { event ->
            event is GattEvent.Connection &&
                event.status == BluetoothGatt.GATT_SUCCESS &&
                event.newState == BluetoothProfile.STATE_CONNECTED
        }
        val started = onGatt { gatt?.discoverServices() == true }
        if (!started) throw ProtocolException("Could not discover the car's Bluetooth services.")
        await(10.seconds, "The car did not list its Bluetooth services.") { event ->
            event is GattEvent.Services && event.status == BluetoothGatt.GATT_SUCCESS
        }
        onGatt { gatt?.requestMtu(247) }
        withTimeoutOrNull(4.seconds) {
            await(4.seconds, "MTU") { it is GattEvent.Mtu }
        }
        val service = gatt?.getService(uuid(TeslaProtocol.SERVICE_UUID))
            ?: throw ProtocolException("The car did not expose the Tesla key service. Move closer and try again.")
        val write = service.getCharacteristic(uuid(TeslaProtocol.WRITE_CHARACTERISTIC_UUID))
            ?: throw ProtocolException("The car is missing the command characteristic.")
        val read = service.getCharacteristic(uuid(TeslaProtocol.READ_CHARACTERISTIC_UUID))
            ?: throw ProtocolException("The car is missing the response characteristic.")
        writeCharacteristic = write
        val descriptor = read.getDescriptor(uuid(TeslaProtocol.CLIENT_CONFIG_UUID))
            ?: throw ProtocolException("The car is missing the notify descriptor.")
        val notifyReady = onGatt {
            gatt?.setCharacteristicNotification(read, true)
            gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                ?: BluetoothStatusCodes.ERROR_UNKNOWN
        }
        if (notifyReady != BluetoothStatusCodes.SUCCESS) {
            throw ProtocolException("Could not subscribe to car responses (status $notifyReady).")
        }
        await(8.seconds, "The car did not enable responses.") { event ->
            event is GattEvent.Descriptor && event.status == BluetoothGatt.GATT_SUCCESS
        }
        onGatt { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        connected.set(true)
        Log.i(TeslaBleClient.TAG, "Connected, MTU $mtu")
    }

    override suspend fun poll(timeout: Duration): ByteArray? = try {
        withTimeoutOrNull(timeout) { incoming.receive() }
    } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
        null
    }

    override suspend fun readRssi(): Int {
        val deferred = CompletableDeferred<Int>()
        rssiSlot = deferred
        val started = onGatt { gatt?.readRemoteRssi() == true }
        if (!started) return Int.MIN_VALUE
        return withTimeoutOrNull(2.seconds) { deferred.await() } ?: Int.MIN_VALUE
    }

    override suspend fun send(bytes: ByteArray) {
        writeFrame(BleFrames.encode(bytes))
    }

    override fun isConnected(): Boolean = connected.get() && !closed.get()

    override suspend fun preferLowPower() {
        onGatt { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
    }

    override suspend fun exchange(
        request: ByteArray,
        timeout: Duration,
        accept: (ByteArray) -> Decision,
    ): ByteArray {
        while (incoming.tryReceive().isSuccess) {
            // Drop leftovers from an earlier command on this connection.
        }
        if (BuildConfig.DEBUG) {
            Log.d(TeslaBleClient.TAG, "TX ${request.size} bytes ${request.toHex().take(80)}")
        }
        writeFrame(BleFrames.encode(request))
        val mark = TimeSource.Monotonic.markNow()
        while (true) {
            val remaining = timeout - mark.elapsedNow()
            if (remaining.isNegative()) {
                throw ProtocolException("The car did not respond. Move closer and try again.")
            }
            val frame = try {
                withTimeout(remaining) { incoming.receive() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw ProtocolException("The car did not respond. Move closer and try again.")
            }
            if (accept(frame) == Decision.DONE) return frame
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: SecurityException) {
            }
            gatt = null
            thread.quitSafely()
        }
        incoming.close()
        events.close()
    }

    private suspend fun writeFrame(frame: ByteArray) {
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        for (chunk in BleFrames.chunks(frame, chunkSize)) {
            val deferred = CompletableDeferred<Int>()
            writeSlot = deferred
            val started = onGatt {
                val characteristic = writeCharacteristic
                    ?: return@onGatt BluetoothStatusCodes.ERROR_UNKNOWN
                gatt?.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) ?: BluetoothStatusCodes.ERROR_UNKNOWN
            }
            if (started != BluetoothStatusCodes.SUCCESS) {
                writeSlot = null
                throw ProtocolException("The watch could not write to the car (status $started).")
            }
            val result = withTimeoutOrNull(5.seconds) { deferred.await() }
                ?: throw ProtocolException("The car did not acknowledge a Bluetooth write.")
            if (result != BluetoothGatt.GATT_SUCCESS) {
                throw ProtocolException("The car rejected a Bluetooth write (status $result).")
            }
        }
    }

    private suspend fun await(timeout: Duration, describe: String, match: (GattEvent) -> Boolean) {
        within(timeout, describe) {
            while (true) {
                when (val event = events.receive()) {
                    is GattEvent.Connection -> {
                        if (event.newState == BluetoothProfile.STATE_DISCONNECTED && !match(event)) {
                            throw ProtocolException(disconnectMessage(event.status))
                        }
                        if (match(event)) return@within
                    }
                    else -> if (match(event)) return@within
                }
            }
        }
    }

    private suspend fun <T> onGatt(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        handler.post {
            if (!continuation.isActive) return@post
            try {
                continuation.resume(block())
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
    }

    private fun disconnectMessage(status: Int): String = when (status) {
        133 -> "The watch could not hold the Bluetooth connection. Move closer and try again."
        else -> "The car disconnected (status $status)."
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)
}

data class TeslaSighting(val name: String, val rssi: Int)

data class ScanSurvey(
    val deviceCount: Int,
    val sightings: List<TeslaSighting>,
    val ibeaconWithoutTeslaName: Int,
    val expectedName: String,
)

@SuppressLint("MissingPermission")
private class ScanTally {
    private val addresses = HashSet<String>()
    private val tesla = LinkedHashMap<String, Int>()
    var ibeaconWithoutName: Int = 0
        private set

    val deviceCount: Int get() = addresses.size

    fun teslaNames(): List<String> = tesla.keys.toList()

    fun sightings(): List<TeslaSighting> = tesla.map { TeslaSighting(it.key, it.value) }

    fun consider(result: ScanResult, expected: String): Boolean {
        addresses.add(result.device.address)
        val parsed = Advertisement.parse(result.scanRecord?.bytes)
        val names = buildList {
            addAll(parsed.names)
            result.scanRecord?.deviceName?.let { add(it) }
            try {
                result.device.name?.let { add(it) }
            } catch (_: SecurityException) {
            }
        }
        val teslaNames = names.filter { Advertisement.isTeslaName(it) }
        if (parsed.appleIBeacon && teslaNames.isEmpty()) ibeaconWithoutName += 1
        for (name in teslaNames) {
            val key = name.trim()
            val previous = tesla.entries.firstOrNull { Advertisement.sameName(it.key, key) }
            if (previous == null) {
                tesla[key] = result.rssi
            } else if (result.rssi > previous.value) {
                tesla[previous.key] = result.rssi
            }
        }
        return names.any { Advertisement.sameName(expected, it) }
    }
}

private sealed interface GattEvent {
    data class Connection(val status: Int, val newState: Int) : GattEvent
    data class Services(val status: Int) : GattEvent
    data class Mtu(val status: Int) : GattEvent
    data class Descriptor(val status: Int) : GattEvent
}

private suspend fun <T> within(timeout: Duration, describe: String, block: suspend () -> T): T = try {
    withTimeout(timeout) { block() }
} catch (error: kotlinx.coroutines.TimeoutCancellationException) {
    throw ProtocolException(describe, error)
}
