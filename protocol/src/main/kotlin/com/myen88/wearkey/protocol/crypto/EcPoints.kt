package com.myen88.wearkey.protocol.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** Uncompressed SEC1 P-256 points: 0x04 || X || Y, 65 bytes. */
object EcPoints {
    private val parameters: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    fun encode(point: ECPoint): ByteArray {
        val x = unsigned32(point.affineX)
        val y = unsigned32(point.affineY)
        return ByteArray(65).also { encoded ->
            encoded[0] = 0x04
            x.copyInto(encoded, destinationOffset = 1)
            y.copyInto(encoded, destinationOffset = 33)
        }
    }

    fun encode(publicKey: ECPublicKey): ByteArray = encode(publicKey.w)

    fun toPublicKey(uncompressed: ByteArray): PublicKey {
        require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) {
            "Vehicle public key must be a 65-byte uncompressed P-256 point."
        }
        val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), parameters)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    /**
     * Java and Android Keystore ECDH return the X coordinate, sometimes
     * stripped of leading zeros and sometimes with a sign byte. Tesla hashes
     * the fixed 32-byte big-endian X.
     */
    fun sharedX(secret: ByteArray): ByteArray = when {
        secret.size == 32 -> secret.copyOf()
        secret.size == 33 && secret[0] == 0.toByte() -> secret.copyOfRange(1, 33)
        secret.size == 65 && secret[0] == 0x04.toByte() -> secret.copyOfRange(1, 33)
        secret.size in 1..31 -> ByteArray(32 - secret.size) + secret
        else -> throw IllegalArgumentException("Unexpected ECDH secret length ${secret.size}.")
    }

    private fun unsigned32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val stripped = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33) else raw
        require(stripped.size <= 32) { "Coordinate does not fit in 32 bytes." }
        return ByteArray(32 - stripped.size) + stripped
    }
}
