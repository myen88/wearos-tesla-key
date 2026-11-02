package com.myen88.wearkey.protocol.presence

/**
 * RSSI fallback for walk-up unlock and walk-away lock.
 *
 * RSSI is negative; a greater value (closer to zero) is a stronger signal.
 * [PresenceConfig.nearRssi] must be greater than [PresenceConfig.farRssi].
 * The smoothed value has to cross the near line to unlock, and stay at or
 * below the far line for [PresenceConfig.farHoldMillis] to lock. One unlock
 * is not sent again until a lock has been sent.
 */
class PresenceController(private val config: PresenceConfig) {
    init {
        require(config.nearRssi > config.farRssi) { "Near RSSI must be stronger than far RSSI." }
        require(config.window >= 1)
        require(config.farHoldMillis >= 0)
        require(config.minCommandIntervalMillis >= 0)
    }

    private val samples = ArrayDeque<Int>()
    private var phase = Phase.AWAY
    private var farSince: Long? = null
    private var lastCommandAt: Long? = null
    private var pending: PresenceCommand? = null

    val smoothed: Int?
        get() = if (samples.isEmpty()) null else samples.sum() / samples.size

    fun onRssi(rssi: Int, nowMillis: Long): PresenceCommand? {
        samples.addLast(rssi)
        while (samples.size > config.window) samples.removeFirst()
        val level = smoothed ?: return null
        val edge = desired(level, nowMillis)
        val command = when {
            edge != null -> edge
            pending == PresenceCommand.UNLOCK && level >= config.nearRssi && phase != Phase.NEAR -> PresenceCommand.UNLOCK
            pending == PresenceCommand.LOCK && level <= config.farRssi && phase == Phase.NEAR -> PresenceCommand.LOCK
            else -> null
        }
        pending = null
        if (command == null) return null
        val last = lastCommandAt
        if (last != null && nowMillis - last < config.minCommandIntervalMillis) {
            pending = command
            return null
        }
        lastCommandAt = nowMillis
        phase = if (command == PresenceCommand.UNLOCK) Phase.NEAR else Phase.AWAY
        if (phase == Phase.NEAR) farSince = null
        return command
    }

    private fun desired(level: Int, nowMillis: Long): PresenceCommand? {
        if (level >= config.nearRssi) {
            farSince = null
            return if (phase != Phase.NEAR) PresenceCommand.UNLOCK else null
        }
        if (level <= config.farRssi) {
            if (phase != Phase.NEAR) return null
            val started = farSince ?: nowMillis.also { farSince = it }
            if (nowMillis - started >= config.farHoldMillis) {
                farSince = null
                return PresenceCommand.LOCK
            }
            return null
        }
        farSince = null
        return null
    }

    private enum class Phase { AWAY, NEAR }
}

enum class PresenceCommand { UNLOCK, LOCK }

data class PresenceConfig(
    val nearRssi: Int = -65,
    val farRssi: Int = -85,
    val farHoldMillis: Long = 8_000,
    val window: Int = 5,
    val minCommandIntervalMillis: Long = 4_000,
)
