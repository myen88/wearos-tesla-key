package com.myen88.wearkey.protocol.message

import com.myen88.wearkey.protocol.sha1
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone-key challenge format reconstructed from public VCSEC captures
 * (app protos around 4.20 and the NDSS 2023 phone-key paper). It is not
 * part of the vehicle-command specification. Field numbers match those
 * public protos. The AES-GCM nonce is the 4-byte counter in the last four
 * bytes of a 12-byte IV, which is what Java's GCM implementation accepts;
 * older write-ups only say the counter is four bytes.
 *
 * The shared key passed in is the vehicle-command session key
 * SHA-1(ECDH X)[:16]. Whether current cars use that same key for
 * SIGNATURE_TYPE_AES_GCM_TOKEN is not confirmed.
 */
object PassiveAuth {
    const val LEVEL_NONE = 0
    const val LEVEL_UNLOCK = 1
    const val LEVEL_DRIVE = 2
    const val SIGNATURE_AES_GCM_TOKEN = 3

    data class Challenge(val token: ByteArray, val requestedLevel: Int)

    fun parseChallenge(body: ByteArray): Challenge? {
        val top = readFields(body)
        val request = top[3] as? ByteArray ?: return null
        val inner = readFields(request)
        val session = inner[2] as? ByteArray ?: return null
        val token = readFields(session)[1] as? ByteArray ?: return null
        if (token.isEmpty()) return null
        val level = (inner[3] as? Long)?.toInt() ?: LEVEL_NONE
        return Challenge(token, level)
    }

    fun keyId(publicKey: ByteArray): ByteArray = sha1(publicKey).copyOf(4)

    fun nonce(counter: Int): ByteArray {
        val out = ByteArray(12)
        out[8] = (counter ushr 24).toByte()
        out[9] = (counter ushr 16).toByte()
        out[10] = (counter ushr 8).toByte()
        out[11] = counter.toByte()
        return out
    }

    /**
     * Grant the level the car asked for only when the watch believes it is
     * near. Otherwise answer NONE so a far-away connection does not authorize
     * drive. Distance is a proximity hint, not a calibrated meter value.
     */
    fun responseLevel(requestedLevel: Int, near: Boolean): Int {
        if (!near) return LEVEL_NONE
        return requestedLevel.coerceIn(LEVEL_NONE, LEVEL_DRIVE)
    }

    fun distanceHint(rssi: Int): Int {
        val clamped = rssi.coerceIn(-100, -30)
        return (-30 - clamped) * 40
    }

    fun signedResponse(
        sharedKey: ByteArray,
        counter: Int,
        publicKey: ByteArray,
        token: ByteArray,
        level: Int,
        estimatedDistance: Int,
    ): ByteArray {
        val plain = authenticationResponse(level, estimatedDistance)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sharedKey.copyOf(16), "AES"),
            GCMParameterSpec(128, nonce(counter)),
        )
        cipher.updateAAD(token)
        val sealed = cipher.doFinal(plain)
        val ciphertext = sealed.copyOf(sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        val signed = proto(
            fieldBytes(2, ciphertext),
            fieldVarint(3, SIGNATURE_AES_GCM_TOKEN.toLong()),
            fieldBytes(4, tag),
            fieldBytes(5, keyId(publicKey)),
            fieldVarint(6, counter.toLong() and 0xffff_ffffL),
        )
        return proto(fieldBytes(1, signed))
    }

    fun authenticationResponse(level: Int, estimatedDistance: Int): ByteArray = proto(
        fieldVarint(1, level.toLong()),
        fieldVarint(2, estimatedDistance.toLong()),
    )

    private fun readFields(data: ByteArray): Map<Int, Any> {
        val out = HashMap<Int, Any>()
        var index = 0
        while (index < data.size) {
            val (key, next) = readVarint(data, index) ?: return out
            index = next
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when (wire) {
                0 -> {
                    val (value, after) = readVarint(data, index) ?: return out
                    index = after
                    out.putIfAbsent(field, value)
                }
                2 -> {
                    val (length, afterLen) = readVarint(data, index) ?: return out
                    val start = afterLen
                    val end = start + length.toInt()
                    if (end > data.size || length < 0) return out
                    out.putIfAbsent(field, data.copyOfRange(start, end))
                    index = end
                }
                else -> return out
            }
        }
        return out
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = start
        while (index < data.size && shift < 64) {
            val byte = data[index].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            index += 1
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private fun proto(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun fieldVarint(field: Int, value: Long): ByteArray =
        varint(((field shl 3) or 0).toLong()) + varint(value)

    private fun fieldBytes(field: Int, value: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var rest = value
        while (rest > 0x7F) {
            out.write(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write((rest and 0x7F).toInt())
        return out.toByteArray()
    }
}
