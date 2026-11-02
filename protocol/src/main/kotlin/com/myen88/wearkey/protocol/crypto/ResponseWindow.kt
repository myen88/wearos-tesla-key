package com.myen88.wearkey.protocol.crypto

/**
 * Anti-replay window for response counters on a single request.
 * Ported from vehicle-command `updateSlidingWindow` (window size 32).
 * A counter is accepted once. Newer counters slide the window; a modestly
 * older counter is accepted only if that slot is still empty.
 */
class ResponseWindow {
    private var counter: UInt = 0u
    private var history: ULong = 0u
    private var used: Boolean = false

    fun accept(newCounter: Int): Boolean {
        val incoming = newCounter.toUInt()
        if (!used) {
            used = true
            counter = incoming
            return true
        }
        if (counter == incoming) return false
        if (incoming < counter) {
            val age = counter - incoming
            if (age > WINDOW) return false
            val bit = age.toInt() - 1
            if (((history shr bit) and 1uL) == 1uL) return false
            history = history or (1uL shl bit)
            return true
        }
        val shift = incoming - counter
        history = shiftLeft(history, shift)
        history = history or shiftLeft(1uL, shift - 1u)
        counter = incoming
        return true
    }

    private fun shiftLeft(value: ULong, bits: UInt): ULong {
        if (bits == 0u) return value
        if (bits >= 64u) return 0uL
        return value shl bits.toInt()
    }

    private companion object {
        const val WINDOW = 32u
    }
}
