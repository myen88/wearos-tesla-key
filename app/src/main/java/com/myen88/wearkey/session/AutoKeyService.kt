package com.myen88.wearkey.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.myen88.wearkey.MainActivity
import com.myen88.wearkey.R
import com.myen88.wearkey.ble.TeslaBleClient
import com.myen88.wearkey.data.WatchKeyStore
import com.myen88.wearkey.domain.AutoKeyListener
import com.myen88.wearkey.domain.RadioGate
import com.myen88.wearkey.domain.VehicleCommander
import com.myen88.wearkey.keystore.AndroidKeystoreKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AutoKeyBus {
    val detail = MutableStateFlow("Off")
    val rssi = MutableStateFlow(Int.MIN_VALUE)
    val smoothed = MutableStateFlow(Int.MIN_VALUE)
    val connected = MutableStateFlow(false)
}

class AutoKeyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = WatchKeyStore(this)
        if (!store.autoKey || !store.enrolled || store.vin.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            notification("Looking for the car"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        acquireLock()
        if (job?.isActive != true) {
            job = scope.launch { runLoop(store) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        AutoKeyBus.connected.value = false
        AutoKeyBus.detail.value = "Off"
        super.onDestroy()
    }

    private suspend fun runLoop(store: WatchKeyStore) {
        val commander = VehicleCommander(TeslaBleClient(this), AndroidKeystoreKey(), store)
        val listener = AutoKeyListener { rssi, smoothed, connected, detail ->
            AutoKeyBus.rssi.value = rssi
            AutoKeyBus.smoothed.value = smoothed
            AutoKeyBus.connected.value = connected
            AutoKeyBus.detail.value = detail
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, notification(detail))
        }
        var backoff = 1_000L
        while (scope.isActive && store.autoKey) {
            if (RadioGate.yieldBackground) {
                listener.onSample(AutoKeyBus.rssi.value, AutoKeyBus.smoothed.value, false, "Paused for a command")
                delay(300)
                continue
            }
            val vin = store.vin
            if (vin.isNullOrBlank() || !store.enrolled) break
            try {
                commander.maintain(vin, listener)
                backoff = 1_000L
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                listener.onSample(Int.MIN_VALUE, Int.MIN_VALUE, false, error.message ?: "Reconnecting")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            delay(1_000)
        }
        stopSelf()
    }

    private fun acquireLock() {
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchkey:auto").apply {
            setReferenceCounted(false)
        }
        lock.acquire()
        wakeLock = lock
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "auto-key"
        private const val NOTIFICATION_ID = 43

        fun start(context: Context) {
            val intent = Intent(context, AutoKeyService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoKeyService::class.java))
        }
    }
}
