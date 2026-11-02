package com.myen88.wearkey.protocol

import java.security.MessageDigest
import java.security.SecureRandom

private val secureRandom = SecureRandom()

fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val cleaned = replace(" ", "").removePrefix("0x")
    require(cleaned.length % 2 == 0) { "Hex string has an odd length." }
    return ByteArray(cleaned.length / 2) { index ->
        cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

/** Constant-time comparison. Required when checking Tesla HMAC tags. */
fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
