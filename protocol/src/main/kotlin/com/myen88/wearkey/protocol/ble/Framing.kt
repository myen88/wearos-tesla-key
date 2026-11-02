package com.myen88.wearkey.protocol.ble

import java.util.ArrayDeque

/**
 * Tesla BLE frames are a 2-byte big-endian length followed by one protobuf
 * message. Notifications are chunked to ATT_MTU - 3, and a chunk may split
 * the length prefix or contain more than one frame.
 *
 * The official client drops a partial frame if the next chunk arrives more
 * than a second later. [Reassembler.reset] is that hook.
 */
object BleFrames {
    const val MAX_MESSAGE_BYTES = 4096

    fun encode(message: ByteArray): ByteArray {
        require(message.size <= 0xFFFF) { "BLE message is too long." }
        val out = ByteArray(message.size + 2)
        out[0] = (message.size ushr 8).toByte()
        out[1] = message.size.toByte()
        message.copyInto(out, destinationOffset = 2)
        return out
    }

    fun chunks(frame: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0)
        if (frame.isEmpty()) return emptyList()
        val parts = ArrayList<ByteArray>((frame.size + chunkSize - 1) / chunkSize)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + chunkSize, frame.size)
            parts.add(frame.copyOfRange(offset, end))
            offset = end
        }
        return parts
    }
}

class Reassembler {
    private val buffer = ArrayDeque<Byte>()

    fun reset() {
        buffer.clear()
    }

    fun push(chunk: ByteArray): List<ByteArray> {
        chunk.forEach { buffer.addLast(it) }
        val messages = mutableListOf<ByteArray>()
        while (buffer.size >= 2) {
            val length = ((buffer.elementAt(0).toInt() and 0xFF) shl 8) or
                (buffer.elementAt(1).toInt() and 0xFF)
            if (length <= 0 || length > BleFrames.MAX_MESSAGE_BYTES) {
                buffer.clear()
                throw ProtocolFramingException("Car sent a BLE frame length of $length.")
            }
            if (buffer.size < length + 2) break
            repeat(2) { buffer.removeFirst() }
            val message = ByteArray(length)
            for (index in 0 until length) {
                message[index] = buffer.removeFirst()
            }
            messages.add(message)
        }
        return messages
    }
}

class ProtocolFramingException(message: String) : IllegalArgumentException(message)
