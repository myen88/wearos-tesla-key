package com.myen88.wearkey.protocol.ble

import com.myen88.wearkey.protocol.sha1
import com.myen88.wearkey.protocol.toHex

/**
 * BLE local name `S` + hex(SHA-1(VIN)[:8]) + `C`.
 * The hash is of the VIN's ASCII bytes, matching vehicle-command `VehicleLocalName`.
 *
 * Tesla also advertises an Apple iBeacon (company id 0x004C, type 0x0215).
 * vehicle-command does not define that UUID as a function of the VIN, so an
 * iBeacon alone is not treated as a match for a specific car.
 */
object Advertisement {
    val TESLA_NAME = Regex("^S[0-9a-fA-F]{16}C$")

    fun localName(vin: String): String {
        val digest = sha1(vin.toByteArray(Charsets.US_ASCII))
        return "S" + digest.copyOf(8).toHex() + "C"
    }

    fun sameName(expected: String, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        return expected.equals(candidate.trim(), ignoreCase = true)
    }

    fun isTeslaName(name: String): Boolean = TESLA_NAME.matches(name.trim())

    data class Parsed(val names: List<String>, val appleIBeacon: Boolean)

    /**
     * Walks BLE advertising structures. Complete (0x09) and shortened (0x08)
     * local names are both kept. A zero length ends the packet, which is how
     * Android pads [ScanRecord.getBytes].
     */
    fun parse(raw: ByteArray?): Parsed {
        if (raw == null || raw.isEmpty()) return Parsed(emptyList(), false)
        val names = ArrayList<String>()
        var ibeacon = false
        var index = 0
        while (index < raw.size) {
            val length = raw[index].toInt() and 0xFF
            if (length == 0) break
            if (index + length >= raw.size) break
            val type = raw[index + 1].toInt() and 0xFF
            val start = index + 2
            val end = index + 1 + length
            val data = raw.copyOfRange(start, end)
            when (type) {
                0x08, 0x09 -> {
                    val name = String(data, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                    if (name.isNotEmpty()) names.add(name)
                }
                0xFF -> if (isAppleIBeacon(data)) ibeacon = true
            }
            index = end
        }
        return Parsed(names, ibeacon)
    }

    /** Manufacturer payload begins with the 16-bit company id, little-endian. */
    fun isAppleIBeacon(manufacturerPayload: ByteArray): Boolean {
        if (manufacturerPayload.size < 4) return false
        val company = (manufacturerPayload[0].toInt() and 0xFF) or
            ((manufacturerPayload[1].toInt() and 0xFF) shl 8)
        if (company != 0x004C) return false
        return manufacturerPayload[2] == 0x02.toByte() && manufacturerPayload[3] == 0x15.toByte()
    }

    fun describeScanMiss(deviceCount: Int, teslaNames: List<String>, expected: String): String {
        if (deviceCount <= 0) {
            return "The scan saw no Bluetooth devices. Turn Location on, stand next to the car, and try again."
        }
        if (teslaNames.isEmpty()) {
            return "Saw $deviceCount Bluetooth device${if (deviceCount == 1) "" else "s"}, none with a Tesla name (S…C). Expected $expected."
        }
        val shown = teslaNames.take(4).joinToString(", ")
        val extra = if (teslaNames.size > 4) " +" + (teslaNames.size - 4) else ""
        return "Saw $deviceCount Bluetooth device${if (deviceCount == 1) "" else "s"} and ${teslaNames.size} Tesla name${if (teslaNames.size == 1) "" else "s"} ($shown$extra). None matched this VIN. Expected $expected."
    }
}
