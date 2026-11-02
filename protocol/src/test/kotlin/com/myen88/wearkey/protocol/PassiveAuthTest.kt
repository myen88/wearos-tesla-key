package com.myen88.wearkey.protocol

import com.myen88.wearkey.protocol.message.PassiveAuth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PassiveAuthTest {
    @Test
    fun parsesThePublicChallengeLayout() {
        val token = ByteArray(20) { 0x11 }
        val body = byteArrayOf(0x1a, 0x1a, 0x12, 0x16, 0x0a, 0x14) + token + byteArrayOf(0x18, 0x02)
        val challenge = PassiveAuth.parseChallenge(body)!!
        assertArrayEquals(token, challenge.token)
        assertEquals(PassiveAuth.LEVEL_DRIVE, challenge.requestedLevel)
    }

    @Test
    fun ignoresOrdinaryStatusPayloads() {
        assertNull(PassiveAuth.parseChallenge(byteArrayOf(0x0a, 0x00)))
    }

    @Test
    fun signedResponseRoundTripsWithTheSessionKey() {
        val key = ByteArray(16) { index -> index.toByte() }
        val publicKey = ByteArray(65) { 0x04 }
        val token = ByteArray(20) { 0x22 }
        val message = PassiveAuth.signedResponse(
            sharedKey = key,
            counter = 7,
            publicKey = publicKey,
            token = token,
            level = PassiveAuth.LEVEL_UNLOCK,
            estimatedDistance = 800,
        )
        assertEquals(0x0a, message[0].toInt() and 0xFF)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, PassiveAuth.nonce(7)),
        )
        cipher.updateAAD(token)
        val plain = PassiveAuth.authenticationResponse(PassiveAuth.LEVEL_UNLOCK, 800)
        val encrypted = cipherAt(message)
        assertArrayEquals(plain, cipher.doFinal(encrypted.ciphertext + encrypted.tag))
        assertArrayEquals(PassiveAuth.keyId(publicKey), encrypted.keyId)
    }

    @Test
    fun farWatchDoesNotGrantDrive() {
        assertEquals(PassiveAuth.LEVEL_NONE, PassiveAuth.responseLevel(PassiveAuth.LEVEL_DRIVE, near = false))
        assertEquals(PassiveAuth.LEVEL_DRIVE, PassiveAuth.responseLevel(PassiveAuth.LEVEL_DRIVE, near = true))
        assertEquals(0, PassiveAuth.distanceHint(-30))
        assertEquals(2_000, PassiveAuth.distanceHint(-80))
    }

    private fun cipherAt(message: ByteArray): Encrypted {
        var index = 0
        fun varint(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val byte = message[index].toInt() and 0xFF
                index += 1
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
        }
        fun field(): Triple<Int, Int, ByteArray> {
            val key = varint()
            val field = key ushr 3
            val wire = key and 7
            val payload = if (wire == 2) {
                val length = varint()
                message.copyOfRange(index, index + length).also { index += length }
            } else {
                byteArrayOf()
            }
            return Triple(field, wire, payload)
        }
        val outer = field()
        val signed = outer.third
        index = 0
        val local = signed
        fun varintLocal(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val byte = local[index].toInt() and 0xFF
                index += 1
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
        }
        var ciphertext = ByteArray(0)
        var tag = ByteArray(0)
        var keyId = ByteArray(0)
        while (index < local.size) {
            val key = varintLocal()
            val field = key ushr 3
            val wire = key and 7
            if (wire == 2) {
                val length = varintLocal()
                val payload = local.copyOfRange(index, index + length)
                index += length
                when (field) {
                    2 -> ciphertext = payload
                    4 -> tag = payload
                    5 -> keyId = payload
                }
            } else {
                varintLocal()
            }
        }
        return Encrypted(ciphertext, tag, keyId)
    }

    private data class Encrypted(val ciphertext: ByteArray, val tag: ByteArray, val keyId: ByteArray)
}
