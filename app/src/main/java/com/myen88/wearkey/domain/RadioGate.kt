package com.myen88.wearkey.domain

import kotlinx.coroutines.sync.Mutex

/**
 * One BLE radio user at a time. Auto key holds the mutex while it is
 * connected. A button command sets [yieldBackground] so that loop drops
 * the link, then takes the mutex itself.
 */
object RadioGate {
    val mutex = Mutex()

    @Volatile
    var yieldBackground: Boolean = false
}
