package com.myen88.wearkey.protocol.crypto

import com.myen88.wearkey.protocol.concat
import com.myen88.wearkey.protocol.constantTimeEquals
import com.myen88.wearkey.protocol.sha1
import com.myen88.wearkey.protocol.sha256
import com.tesla.generated.signatures.Signatures
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Session crypto from vehicle-command protocol.md and
 * internal/authentication.
 *
 * Shared key K is the first 16 bytes of SHA-1 of the ECDH X coordinate.
 * SHA-1 is what the vehicles already implement; it is a KDF, not a signature.
 * Command payloads use AES-GCM. The session-info tag is HMAC-SHA256.
 */
object SessionCrypto {
    private const val SESSION_INFO_LABEL = "session info"
    private val gcmTag = Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED
    private val hmacTag = Signatures.SignatureType.SIGNATURE_TYPE_HMAC
    private val responseTag = Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE

    fun sharedKeyFromX(xCoordinate: ByteArray): ByteArray {
        require(xCoordinate.size == 32) { "ECDH X coordinate must be 32 bytes." }
        return sha1(xCoordinate).copyOf(16)
    }

    fun sessionInfoKey(sharedKey: ByteArray): ByteArray =
        hmacSha256(sharedKey, SESSION_INFO_LABEL.toByteArray(Charsets.US_ASCII))

    fun sessionInfoTag(
        sharedKey: ByteArray,
        vin: String,
        challenge: ByteArray,
        sessionInfo: ByteArray,
    ): ByteArray {
        val metadata = Metadata.build {
            add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(hmacTag.number.toByte()))
            add(Signatures.Tag.TAG_PERSONALIZATION, vin.toByteArray(Charsets.US_ASCII))
            add(Signatures.Tag.TAG_CHALLENGE, challenge)
        }
        return hmacSha256(sessionInfoKey(sharedKey), concat(metadata, sessionInfo))
    }

    fun sessionInfoTagValid(
        sharedKey: ByteArray,
        vin: String,
        challenge: ByteArray,
        sessionInfo: ByteArray,
        tag: ByteArray,
    ): Boolean = constantTimeEquals(sessionInfoTag(sharedKey, vin, challenge, sessionInfo), tag)

    fun commandMetadata(
        domain: Int,
        vin: String,
        epoch: ByteArray,
        counter: Int,
        expiresAt: Long,
        flags: Int,
    ): ByteArray = Metadata.build {
        add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(gcmTag.number.toByte()))
        add(Signatures.Tag.TAG_DOMAIN, byteArrayOf(domain.toByte()))
        add(Signatures.Tag.TAG_PERSONALIZATION, vin.toByteArray(Charsets.US_ASCII))
        add(Signatures.Tag.TAG_EPOCH, epoch)
        addUInt32(Signatures.Tag.TAG_EXPIRES_AT, expiresAt)
        addUInt32(Signatures.Tag.TAG_COUNTER, counter)
        if (flags != 0) {
            addUInt32(Signatures.Tag.TAG_FLAGS, flags)
        }
    }

    fun responseMetadata(
        domain: Int,
        vin: String,
        counter: Int,
        flags: Int,
        requestHash: ByteArray,
        fault: Int,
    ): ByteArray = Metadata.build {
        add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(responseTag.number.toByte()))
        add(Signatures.Tag.TAG_DOMAIN, byteArrayOf(domain.toByte()))
        add(Signatures.Tag.TAG_PERSONALIZATION, vin.toByteArray(Charsets.US_ASCII))
        addUInt32(Signatures.Tag.TAG_COUNTER, counter)
        addUInt32(Signatures.Tag.TAG_FLAGS, flags)
        add(Signatures.Tag.TAG_REQUEST_HASH, requestHash)
        addUInt32(Signatures.Tag.TAG_FAULT, fault)
    }

    data class SealedCommand(
        val ciphertext: ByteArray,
        val tag: ByteArray,
        val nonce: ByteArray,
        val requestHash: ByteArray,
    )

    fun seal(
        sharedKey: ByteArray,
        metadata: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
    ): SealedCommand {
        val (ciphertext, tag) = aesGcm(Cipher.ENCRYPT_MODE, sharedKey, nonce, plaintext, sha256(metadata), tag = null)
        val requestHash = concat(byteArrayOf(gcmTag.number.toByte()), tag)
        return SealedCommand(ciphertext, tag, nonce, requestHash)
    }

    fun open(
        sharedKey: ByteArray,
        metadata: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
    ): ByteArray = try {
        aesGcm(Cipher.DECRYPT_MODE, sharedKey, nonce, ciphertext, sha256(metadata), tag).first
    } catch (error: GeneralSecurityException) {
        throw ProtocolException("The car's response failed authentication.", error)
    }

    private fun aesGcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
        tag: ByteArray?,
    ): Pair<ByteArray, ByteArray> {
        require(key.size == 16) { "AES-GCM key must be 16 bytes." }
        require(nonce.size == 12) { "AES-GCM nonce must be 12 bytes." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val payload = if (mode == Cipher.DECRYPT_MODE) concat(input, tag ?: error("Missing tag")) else input
        val out = cipher.doFinal(payload)
        if (mode == Cipher.DECRYPT_MODE) return out to ByteArray(0)
        require(out.size >= 16)
        return out.copyOf(out.size - 16) to out.copyOfRange(out.size - 16, out.size)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

open class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NotEnrolledException(message: String = "This key is not paired to the car.") : ProtocolException(message)

class SessionOutOfSyncException(
    message: String,
    val resynced: Boolean,
) : ProtocolException(message)
