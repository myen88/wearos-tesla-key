package com.myen88.wearkey.protocol.presence

/**
 * RSSI fallback for walk-up unlock and walk-away lock.
 *
 * RSSI is in dBm and must be negative. Zero, positive, or missing readings
 * are not "very close"; they are ignored for unlock and break a near streak.
 * A greater value (closer to zero) is a stronger signal. [PresenceConfig.nearRssi]
 * must be greater than [PresenceConfig.farRssi].
 *
 * Unlock requires [PresenceConfig.consecutiveNearRequired] fresh samples in a
 * row, each at or above Near, with no gap longer than
 * [PresenceConfig.sampleFreshMillis]. The average that gates that decision is
 * the near streak itself, not older far samples still sitting in the display
 * window.
 *
 * One unlock is sent per approach. That latch clears only on a walk-away lock
 * or [noteManualLock]. One lock is sent per departure, and only after a near
 * or unlock event. Far samples on a new link do not lock. Both latches and
 * the unlock timestamps survive [onLinkRestart].
 */
class PresenceController(config: PresenceConfig) {
    private var config = config

    init {
        checkConfig(config)
    }

    private val samples = ArrayDeque<Int>()
    private var phase = Phase.AWAY
    private var farSince: Long? = null
    private var lastValidAt: Long = 0L
    private var lastSamplePlausible = false
    private var consecutiveNear = 0
    private val unlockTimes = ArrayDeque<Long>()
    var suppressReason: String? = null
        private set

    val smoothed: Int?
        @Synchronized get() = if (samples.isEmpty()) null else samples.sum() / samples.size

    @Synchronized
    fun updateConfig(updated: PresenceConfig) {
        checkConfig(updated)
        config = updated
    }

    /**
     * New GATT link. Drop stale samples. The approach latch and the departure
     * latch both stay as they are, so a reconnect cannot unlock or lock again.
     */
    @Synchronized
    fun onLinkRestart() {
        samples.clear()
        consecutiveNear = 0
        farSince = null
        lastValidAt = 0L
        lastSamplePlausible = false
        suppressReason = null
    }

    /** Button lock. Arms the next approach and blocks another auto-lock. */
    @Synchronized
    fun noteManualLock() {
        phase = Phase.AWAY
        farSince = null
        consecutiveNear = 0
    }

    /** Button unlock. Blocks another auto-unlock and arms a later walk-away lock. */
    @Synchronized
    fun noteManualUnlock() {
        phase = Phase.NEAR
        farSince = null
        consecutiveNear = 0
    }

    /** Call only after an unlock RKE command is actually written. */
    @Synchronized
    fun commitSentUnlock(nowMillis: Long) {
        unlockTimes.addLast(nowMillis)
        while (unlockTimes.size > config.maxUnlocksPerWindow) unlockTimes.removeFirst()
    }

    @Synchronized
    fun isFreshNear(nowMillis: Long): Boolean {
        if (!lastSamplePlausible) return false
        if (lastValidAt == 0L || nowMillis - lastValidAt > config.sampleFreshMillis) return false
        val level = smoothed ?: return false
        val last = samples.lastOrNull() ?: return false
        return last <= -1 && level >= config.nearRssi && last >= config.nearRssi
    }

    @Synchronized
    fun onRssi(rssi: Int, nowMillis: Long, emitCommands: Boolean = true): PresenceCommand? {
        suppressReason = null
        if (!isPlausible(rssi)) {
            consecutiveNear = 0
            lastSamplePlausible = false
            return null
        }
        lastSamplePlausible = true
        if (lastValidAt != 0L && nowMillis - lastValidAt > config.sampleFreshMillis) {
            consecutiveNear = 0
            samples.clear()
        }
        lastValidAt = nowMillis
        samples.addLast(rssi)
        while (samples.size > DISPLAY_WINDOW) samples.removeFirst()
        if (rssi >= config.nearRssi) consecutiveNear += 1 else consecutiveNear = 0
        if (!emitCommands) return null

        if (consecutiveNear >= config.consecutiveNearRequired && phase != Phase.NEAR) {
            val level = streakAverage() ?: return null
            if (level < config.nearRssi) return null
            if (unlocksSince(nowMillis) >= config.maxUnlocksPerWindow) {
                suppressReason = "rate"
                return null
            }
            phase = Phase.NEAR
            farSince = null
            return PresenceCommand.UNLOCK
        }

        if (rssi <= config.farRssi) {
            if (phase != Phase.NEAR) return null
            val started = farSince ?: nowMillis.also { farSince = it }
            if (nowMillis - started >= config.farHoldMillis) {
                farSince = null
                consecutiveNear = 0
                phase = Phase.AWAY
                return PresenceCommand.LOCK
            }
            return null
        }
        farSince = null
        return null
    }

    private fun streakAverage(): Int? {
        if (consecutiveNear == 0 || samples.isEmpty()) return null
        val count = minOf(consecutiveNear, samples.size)
        var sum = 0
        val start = samples.size - count
        for (index in start until samples.size) sum += samples.elementAt(index)
        return sum / count
    }

    private fun unlocksSince(nowMillis: Long): Int {
        while (unlockTimes.isNotEmpty() && nowMillis - unlockTimes.first() > config.unlockWindowMillis) {
            unlockTimes.removeFirst()
        }
        return unlockTimes.size
    }

    private fun checkConfig(value: PresenceConfig) {
        require(value.nearRssi > value.farRssi) { "Near RSSI must be stronger than far RSSI." }
        require(value.nearRssi < 0 && value.farRssi < 0) { "Thresholds are dBm and must be negative." }
        require(value.consecutiveNearRequired >= 1)
        require(value.farHoldMillis >= 0)
        require(value.maxUnlocksPerWindow >= 1)
    }

    private enum class Phase { AWAY, NEAR }

    companion object {
        const val DISPLAY_WINDOW = 5

        fun isPlausible(rssi: Int): Boolean = rssi in -120..-1

        /**
         * VehicleStatus gate. A null lock state means the read failed and the
         * one-shot command may still be sent. The approach and departure
         * latches are already consumed by [PresenceController.onRssi].
         */
        fun gate(command: PresenceCommand, vehicleLocked: Boolean?): PresenceGate = when {
            command == PresenceCommand.LOCK && vehicleLocked == true -> PresenceGate.SKIP_LOCKED
            command == PresenceCommand.UNLOCK && vehicleLocked == false -> PresenceGate.SKIP_UNLOCKED
            else -> PresenceGate.SEND
        }
    }
}

enum class PresenceGate { SEND, SKIP_LOCKED, SKIP_UNLOCKED }

enum class PresenceCommand { UNLOCK, LOCK }

data class PresenceConfig(
    val nearRssi: Int = -65,
    val farRssi: Int = -85,
    val farHoldMillis: Long = 8_000,
    val consecutiveNearRequired: Int = 3,
    val sampleFreshMillis: Long = 2_000,
    val maxUnlocksPerWindow: Int = 2,
    val unlockWindowMillis: Long = 5 * 60 * 1000L,
)
