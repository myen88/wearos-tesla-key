package com.myen88.wearkey.protocol

import com.myen88.wearkey.protocol.ble.Advertisement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisementParseTest {
    private val expected = Advertisement.localName("5YJS0000000000000")

    @Test
    fun readsCompleteAndShortenedLocalNames() {
        val packet = ad(0x01, byteArrayOf(0x06)) +
            ad(0x09, expected.toByteArray()) +
            ad(0x08, "Sshort".toByteArray())
        val parsed = Advertisement.parse(packet)
        assertEquals(listOf(expected, "Sshort"), parsed.names)
        assertFalse(parsed.appleIBeacon)
    }

    @Test
    fun matchesTeslaNamesWithoutCase() {
        assertTrue(Advertisement.sameName(expected, expected.uppercase()))
        assertTrue(Advertisement.isTeslaName(expected.uppercase()))
        assertFalse(Advertisement.isTeslaName("Sshort"))
        assertFalse(Advertisement.isTeslaName("S" + "a".repeat(15) + "C"))
        assertFalse(Advertisement.sameName(expected, "S" + "b".repeat(16) + "C"))
    }

    @Test
    fun paddedScanRecordStopsAtZeroLength() {
        val packet = ad(0x09, expected.toByteArray()) + ByteArray(12)
        assertEquals(listOf(expected), Advertisement.parse(packet).names)
    }

    @Test
    fun appleIBeaconIsNotAVinMatch() {
        val uuid = ByteArray(16) { it.toByte() }
        val payload = byteArrayOf(0x4C, 0x00, 0x02, 0x15) + uuid +
            byteArrayOf(0x00, 0x01, 0x00, 0x02, 0xC5.toByte())
        val packet = ad(0xFF.toByte(), payload)
        val parsed = Advertisement.parse(packet)
        assertTrue(parsed.appleIBeacon)
        assertTrue(parsed.names.isEmpty())
        assertTrue(Advertisement.isAppleIBeacon(payload))
        assertFalse(Advertisement.isAppleIBeacon(byteArrayOf(0x4C, 0x00, 0x01, 0x15)))
    }

    @Test
    fun scanMissTextSeparatesEmptyAirFromOtherTeslas() {
        val empty = Advertisement.describeScanMiss(0, emptyList(), expected)
        assertTrue(empty.contains("no Bluetooth devices"))
        val others = Advertisement.describeScanMiss(4, emptyList(), expected)
        assertTrue(others.contains("Saw 4"))
        assertTrue(others.contains("none with a Tesla name"))
        val wrong = Advertisement.describeScanMiss(3, listOf("S0123456789abcdefC"), expected)
        assertTrue(wrong.contains("1 Tesla name"))
        assertTrue(wrong.contains("None matched"))
        assertTrue(wrong.contains(expected))
    }

    private fun ad(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf((payload.size + 1).toByte(), type.toByte()) + payload

    private fun ad(type: Byte, payload: ByteArray): ByteArray = ad(type.toInt() and 0xFF, payload)
}
