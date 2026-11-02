package com.myen88.wearkey

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.myen88.wearkey.data.WatchKeyStore
import com.myen88.wearkey.session.AutoKeyService
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
        val auto = NotificationChannel(
            AutoKeyService.CHANNEL_ID,
            "Auto key",
            NotificationManager.IMPORTANCE_LOW,
        )
        auto.setShowBadge(false)
        manager.createNotificationChannel(auto)
        val store = WatchKeyStore(this)
        if (store.autoKey && store.enrolled && !store.vin.isNullOrBlank()) {
            runCatching { AutoKeyService.start(this) }
        }
    }
}
