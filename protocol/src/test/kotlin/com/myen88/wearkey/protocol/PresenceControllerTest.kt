package com.myen88.wearkey.protocol

import com.myen88.wearkey.protocol.presence.PresenceCommand
import com.myen88.wearkey.protocol.presence.PresenceConfig
import com.myen88.wearkey.protocol.presence.PresenceController
import com.myen88.wearkey.protocol.presence.PresenceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceControllerTest {
    private val legacy = PresenceConfig(
        nearRssi = -65,
        farRssi = -85,
        farHoldMillis = 8_000,
        consecutiveNearRequired = 1,
        maxUnlocksPerWindow = 10,
    )

    @Test
    fun approachUnlocksOnceUntilALock() {
        val presence = PresenceController(legacy)
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-55, 1_000))
        assertNull(presence.onRssi(-50, 5_000))
    }

    @Test
    fun briefFarDipDoesNotLock() {
        val presence = PresenceController(legacy)
        presence.onRssi(-60, 0)
        assertNull(presence.onRssi(-90, 5_000))
        assertNull(presence.onRssi(-70, 6_000))
        assertNull(presence.onRssi(-90, 20_000))
    }

    @Test
    fun stayingFarForTheHoldLocksThenApproachUnlocksAgain() {
        val presence = PresenceController(legacy)
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-90, 5_000))
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 13_000))
        assertNull(presence.onRssi(-92, 20_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 21_000))
    }

    @Test
    fun middleBandDoesNotFlap() {
        val presence = PresenceController(legacy)
        assertNull(presence.onRssi(-75, 0))
        assertNull(presence.onRssi(-75, 10_000))
    }

    @Test
    fun zeroOrMissingRssiNeverUnlocks() {
        val presence = PresenceController(PresenceConfig())
        assertNull(presence.onRssi(0, 0))
        assertNull(presence.onRssi(0, 1_000))
        assertNull(presence.onRssi(0, 2_000))
        assertNull(presence.onRssi(Int.MIN_VALUE, 3_000))
        assertNull(presence.onRssi(127, 4_000))
        assertNull(presence.onRssi(Int.MAX_VALUE, 5_000))
        assertFalse(presence.isFreshNear(5_000))
        assertNull(presence.smoothed)
    }

    @Test
    fun aZeroReadingAfterNearSamplesIsFar() {
        val presence = PresenceController(PresenceConfig())
        presence.onRssi(-60, 0)
        presence.onRssi(-60, 1_000)
        assertTrue(presence.isFreshNear(1_000))
        assertNull(presence.onRssi(0, 1_500))
        assertFalse(presence.isFreshNear(1_500))
        assertNull(presence.onRssi(-60, 2_000))
        assertNull(presence.onRssi(-60, 3_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 4_000))
    }

    @Test
    fun farSamplesOnAFreshLinkNeverLock() {
        val presence = PresenceController(legacy)
        assertNull(presence.onRssi(-90, 0))
        assertNull(presence.onRssi(-95, 8_000))
        assertNull(presence.onRssi(-100, 20_000))
        presence.onLinkRestart()
        assertNull(presence.onRssi(-95, 30_000))
        assertNull(presence.onRssi(-100, 50_000))
    }

    @Test
    fun lockIsOncePerDepartureAndSurvivesReconnect() {
        val presence = PresenceController(legacy)
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-90, 1_000))
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 9_000))
        assertNull(presence.onRssi(-92, 10_000))
        assertNull(presence.onRssi(-95, 20_000))
        presence.onLinkRestart()
        assertNull(presence.onRssi(-95, 30_000))
        assertNull(presence.onRssi(-100, 50_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 51_000))
        assertNull(presence.onRssi(-58, 52_000))
        assertNull(presence.onRssi(-95, 53_000))
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-95, 61_000))
        presence.onLinkRestart()
        assertNull(presence.onRssi(-95, 70_000))
    }

    @Test
    fun manualUnlockArmsOneWalkAwayLock() {
        val presence = PresenceController(legacy)
        presence.noteManualUnlock()
        assertNull(presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-90, 1_000))
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 9_000))
        assertNull(presence.onRssi(-90, 10_000))
        presence.onLinkRestart()
        assertNull(presence.onRssi(-90, 20_000))
    }

    @Test
    fun manualLockClearsTheUnlockLatchAndBlocksAnotherAutoLock() {
        val presence = PresenceController(legacy)
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        presence.noteManualUnlock()
        assertNull(presence.onRssi(-55, 1_000))
        presence.noteManualLock()
        assertNull(presence.onRssi(-95, 2_000))
        assertNull(presence.onRssi(-95, 20_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 21_000))
        assertNull(presence.onRssi(-58, 22_000))
    }

    @Test
    fun vehicleStatusSkipsACommandTheCarAlreadyHas() {
        assertEquals(PresenceGate.SKIP_LOCKED, PresenceController.gate(PresenceCommand.LOCK, true))
        assertEquals(PresenceGate.SEND, PresenceController.gate(PresenceCommand.LOCK, false))
        assertEquals(PresenceGate.SEND, PresenceController.gate(PresenceCommand.LOCK, null))
        assertEquals(PresenceGate.SKIP_UNLOCKED, PresenceController.gate(PresenceCommand.UNLOCK, false))
        assertEquals(PresenceGate.SEND, PresenceController.gate(PresenceCommand.UNLOCK, true))
        assertEquals(PresenceGate.SEND, PresenceController.gate(PresenceCommand.UNLOCK, null))
    }

    @Test
    fun threeConsecutiveNearSamplesAreRequired() {
        val presence = PresenceController(PresenceConfig())
        assertNull(presence.onRssi(-60, 0))
        assertNull(presence.onRssi(-62, 1_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-64, 2_000))
        assertNull(presence.onRssi(-60, 3_000))
    }

    @Test
    fun aFarSampleBreaksTheNearStreak() {
        val presence = PresenceController(PresenceConfig())
        presence.onRssi(-60, 0)
        presence.onRssi(-60, 1_000)
        assertNull(presence.onRssi(-90, 2_000))
        assertNull(presence.onRssi(-60, 3_000))
        assertNull(presence.onRssi(-60, 4_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 5_000))
    }

    @Test
    fun aGapLongerThanTwoSecondsRestartsTheStreak() {
        val presence = PresenceController(PresenceConfig())
        presence.onRssi(-60, 0)
        presence.onRssi(-60, 1_000)
        assertNull(presence.onRssi(-60, 4_000))
        assertNull(presence.onRssi(-60, 5_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 6_000))
        assertTrue(presence.isFreshNear(7_000))
        assertFalse(presence.isFreshNear(9_500))
    }

    @Test
    fun reconnectDoesNotResetTheUnlockLatch() {
        val presence = PresenceController(PresenceConfig(consecutiveNearRequired = 1, maxUnlocksPerWindow = 10))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        presence.onLinkRestart()
        assertNull(presence.onRssi(-60, 1_000))
        assertNull(presence.onRssi(-60, 2_000))
        assertNull(presence.onRssi(-60, 3_000))
        assertNull(presence.onRssi(-90, 12_000))
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 21_000))
        presence.onLinkRestart()
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 22_000))
    }

    @Test
    fun twoUnlocksPerFiveMinutes() {
        val presence = PresenceController(
            PresenceConfig(
                consecutiveNearRequired = 1,
                farHoldMillis = 0,
                maxUnlocksPerWindow = 2,
                unlockWindowMillis = 300_000,
            ),
        )
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 0))
        presence.commitSentUnlock(0)
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 1_000))
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 2_000))
        presence.commitSentUnlock(2_000)
        assertEquals(PresenceCommand.LOCK, presence.onRssi(-90, 3_000))
        assertNull(presence.onRssi(-60, 4_000))
        assertEquals("rate", presence.suppressReason)
        assertEquals(PresenceCommand.UNLOCK, presence.onRssi(-60, 310_000))
    }
}
