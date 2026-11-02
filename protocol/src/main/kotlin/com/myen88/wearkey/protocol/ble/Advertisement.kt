package com.myen88.wearkey.protocol.ble

import com.myen88.wearkey.protocol.sha1
import com.myen88.wearkey.protocol.toHex

/**
 * BLE local name `S` + hex(SHA-1(VIN)[:8]) + `C`.
 * The hash is of the VIN's ASCII bytes, matching vehicle-command `VehicleLocalName`.
 */
object Advertisement {
    fun localName(vin: String): String {
        val digest = sha1(vin.toByteArray(Charsets.US_ASCII))
        return "S" + digest.copyOf(8).toHex() + "C"
    }
}
