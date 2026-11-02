package com.myen88.wearkey

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.myen88.wearkey.session.CommandForegroundService

class WatchKeyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CommandForegroundService.CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }
}
