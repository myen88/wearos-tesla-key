package com.myen88.wearkey.protocol.crypto

import com.myen88.wearkey.protocol.concat
import com.tesla.generated.signatures.Signatures
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tag-length-value metadata used as HMAC input and, after SHA-256, as AES-GCM
 * associated data. Tags are added in ascending order and a single 0xFF closes
 * the string. Integer fields are 4-byte big-endian. See protocol.md.
 */
class Metadata private constructor(private val body: ByteArray) {
    fun bytes(): ByteArray = body.copyOf()

    companion object {
        fun build(block: Builder.() -> Unit): ByteArray = Builder().apply(block).finish()
    }

    class Builder {
        private val parts = ArrayList<ByteArray>()
        private var lastTag = -1

        fun add(tag: Signatures.Tag, value: ByteArray) {
            require(value.size <= 255) { "Metadata field ${tag.name} is longer than 255 bytes." }
            val number = tag.number
            require(number > lastTag) { "Metadata tags must be added in order." }
            lastTag = number
            parts.add(byteArrayOf(number.toByte(), value.size.toByte()))
            parts.add(value)
        }

        fun addUInt32(tag: Signatures.Tag, value: Long) {
            require(value in 0..0xFFFF_FFFFL) { "Metadata integer out of range." }
            val encoded = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
            add(tag, encoded)
        }

        fun addUInt32(tag: Signatures.Tag, value: Int) {
            addUInt32(tag, value.toLong() and 0xFFFF_FFFFL)
        }

        fun finish(): ByteArray = concat(*parts.toTypedArray(), byteArrayOf(0xFF.toByte()))
    }
}
