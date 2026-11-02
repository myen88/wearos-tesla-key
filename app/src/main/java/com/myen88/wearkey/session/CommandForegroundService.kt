package com.myen88.wearkey.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.myen88.wearkey.MainActivity
import com.myen88.wearkey.R

/**
 * Keeps the process in the foreground for the few seconds a BLE command takes.
 * The watch screen often turns off as soon as the wrist drops.
 */
class CommandForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Talking to the car"
        startForeground(
            NOTIFICATION_ID,
            notification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
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
            .build()
    }

    companion object {
        const val CHANNEL_ID = "car-link"
        const val EXTRA_TEXT = "text"
        private const val NOTIFICATION_ID = 42
    }
}
