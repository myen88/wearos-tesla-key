package com.myen88.wearkey.protocol

import com.myen88.wearkey.protocol.presence.PresenceCommand
import com.myen88.wearkey.protocol.presence.PresenceConfig
import com.myen88.wearkey.protocol.presence.PresenceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenceControllerTest {
    private val config = PresenceConfig(
        nearRssi = -65,
        farRssi = -85,
        farHoldMillis = 8_000,
        window = 1,
        minCommandIntervalMillis = 4_000,
    )

    @Test
    fun approachUnlocksOnceUntilALock() {
        val presence = PresenceController(config)
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-55, 1_000))
        assertNull(presence.onRssi(-50, 5_000))
    }

    @Test
    fun briefFarDipDoesNotLock() {
        val presence = PresenceController(config)
        presence.onRssi(-60, 0)
        assertNull(presence.onRssi(-90, 5_000))
        assertNull(presence.onRssi(-70, 6_000))
        assertNull(presence.onRssi(-90, 20_000))
    }

    @Test
    fun stayingFarForTheHoldLocksThenApproachUnlocksAgain() {
        val presence = PresenceController(config)
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-90, 5_000))
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 13_000))
        assertNull(presence.onRssi(-92, 20_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 21_000))
    }

    @Test
    fun middleBandDoesNotFlap() {
        val presence = PresenceController(config)
        assertNull(presence.onRssi(-75, 0))
        assertNull(presence.onRssi(-75, 10_000))
    }

    @Test
    fun rateLimitHoldsTheLockUntilTheIntervalPasses() {
        val presence = PresenceController(
            config.copy(farHoldMillis = 0, minCommandIntervalMillis = 5_000),
        )
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-90, 1_000))
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 6_000))
    }

    @Test
    fun aPendingLockIsDroppedWhenTheSignalReturns() {
        val presence = PresenceController(
            config.copy(farHoldMillis = 0, minCommandIntervalMillis = 5_000),
        )
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-90, 1_000))
        assertNull(presence.onRssi(-60, 2_000))
        assertNull(presence.onRssi(-60, 10_000))
    }
}
