package com.myen88.wearkey.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.myen88.wearkey.data.WatchKeyStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = WatchKeyStore(context)
        if (store.autoKey && store.enrolled && !store.vin.isNullOrBlank()) {
            AutoKeyService.start(context)
        }
    }
}
