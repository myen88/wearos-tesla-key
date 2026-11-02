package com.myen88.wearkey.data

import android.content.Context
import com.myen88.wearkey.protocol.crypto.SessionSnapshot
import com.myen88.wearkey.protocol.hexToBytes
import com.myen88.wearkey.protocol.toHex

class WatchKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var vin: String?
        get() = prefs.getString(KEY_VIN, null)
        set(value) {
            prefs.edit().putString(KEY_VIN, value).apply()
        }

    var enrolled: Boolean
        get() = prefs.getBoolean(KEY_ENROLLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENROLLED, value).apply()
        }

    var androidFormFactor: Boolean
        get() = prefs.getBoolean(KEY_FORM, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FORM, value).apply()
        }

    var lastLock: String?
        get() = prefs.getString(KEY_LOCK, null)
        set(value) {
            prefs.edit().putString(KEY_LOCK, value).apply()
        }

    var lastBattery: String?
        get() = prefs.getString(KEY_BATTERY, null)
        set(value) {
            prefs.edit().putString(KEY_BATTERY, value).apply()
        }

    var autoKey: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO, value).apply()
        }

    var rssiFallback: Boolean
        get() = prefs.getBoolean(KEY_RSSI_FALLBACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_RSSI_FALLBACK, value).apply()
        }

    var nearRssi: Int
        get() = prefs.getInt(KEY_NEAR, -65)
        set(value) {
            prefs.edit().putInt(KEY_NEAR, value).apply()
        }

    var farRssi: Int
        get() = prefs.getInt(KEY_FAR, -85)
        set(value) {
            prefs.edit().putInt(KEY_FAR, value).apply()
        }

    var farHoldSeconds: Int
        get() = prefs.getInt(KEY_HOLD, 8)
        set(value) {
            prefs.edit().putInt(KEY_HOLD, value).apply()
        }

    var passiveCounter: Int
        get() = prefs.getInt(KEY_PASSIVE_COUNTER, 1)
        set(value) {
            prefs.edit().putInt(KEY_PASSIVE_COUNTER, value).commit()
        }

    fun save(snapshot: SessionSnapshot) {
        val prefix = prefix(snapshot.domain)
        prefs.edit()
            .putString(prefix + "epoch", snapshot.epoch.toHex())
            .putInt(prefix + "counter", snapshot.counter)
            .putString(prefix + "vpub", snapshot.vehiclePublicKey.toHex())
            .putLong(prefix + "offset", snapshot.clockOffsetSeconds)
            .putLong(prefix + "clock", snapshot.lastVehicleClock)
            .commit()
    }

    fun load(domain: Int): SessionSnapshot? {
        val prefix = prefix(domain)
        val epoch = prefs.getString(prefix + "epoch", null) ?: return null
        val publicKey = prefs.getString(prefix + "vpub", null) ?: return null
        if (!prefs.contains(prefix + "counter")) return null
        return SessionSnapshot(
            domain = domain,
            epoch = epoch.hexToBytes(),
            counter = prefs.getInt(prefix + "counter", 0),
            vehiclePublicKey = publicKey.hexToBytes(),
            clockOffsetSeconds = prefs.getLong(prefix + "offset", 0L),
            lastVehicleClock = prefs.getLong(prefix + "clock", 0L),
        )
    }

    fun clear(domain: Int) {
        val prefix = prefix(domain)
        prefs.edit()
            .remove(prefix + "epoch")
            .remove(prefix + "counter")
            .remove(prefix + "vpub")
            .remove(prefix + "offset")
            .remove(prefix + "clock")
            .commit()
    }

    fun clearSessions() {
        clear(2)
        clear(3)
    }

    private fun prefix(domain: Int) = "sess_${domain}_"

    companion object {
        const val PREFS = "watch_key"
        private const val KEY_VIN = "vin"
        private const val KEY_ENROLLED = "enrolled"
        private const val KEY_FORM = "android_form_factor"
        private const val KEY_LOCK = "last_lock"
        private const val KEY_BATTERY = "last_battery"
        private const val KEY_AUTO = "auto_key"
        private const val KEY_RSSI_FALLBACK = "rssi_fallback"
        private const val KEY_NEAR = "near_rssi"
        private const val KEY_FAR = "far_rssi"
        private const val KEY_HOLD = "far_hold_seconds"
        private const val KEY_PASSIVE_COUNTER = "passive_counter"
    }
}
