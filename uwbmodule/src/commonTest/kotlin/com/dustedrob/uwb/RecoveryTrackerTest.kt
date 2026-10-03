package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecoveryTrackerTest {

    private fun retry(decision: RecoveryDecision): RecoveryDecision.Retry {
        assertIs<RecoveryDecision.Retry>(decision)
        return decision
    }

    @Test
    fun backoffDoublesPerAttemptThenGivesUp() {
        val tracker = RecoveryTracker(RecoveryPolicy(maxAttempts = 3, backoffMs = 100))
        assertEquals(RecoveryDecision.Retry(100, 1), tracker.onEnded("p"))
        assertEquals(RecoveryDecision.Retry(200, 2), tracker.onEnded("p"))
        assertEquals(RecoveryDecision.Retry(400, 3), tracker.onEnded("p"))
        assertEquals(RecoveryDecision.GiveUp, tracker.onEnded("p"))
        assertEquals(RecoveryDecision.GiveUp, tracker.onEnded("p"))
    }

    @Test
    fun attemptsAreTrackedPerPeer() {
        val tracker = RecoveryTracker(RecoveryPolicy(maxAttempts = 1, backoffMs = 50))
        assertEquals(RecoveryDecision.Retry(50, 1), tracker.onEnded("a"))
        assertEquals(RecoveryDecision.GiveUp, tracker.onEnded("a"))
        assertEquals(RecoveryDecision.Retry(50, 1), tracker.onEnded("b"))
    }

    @Test
    fun recoveredResetsTheCounter() {
        val tracker = RecoveryTracker(RecoveryPolicy(maxAttempts = 2, backoffMs = 10))
        tracker.onEnded("p")
        assertEquals(2, retry(tracker.onEnded("p")).attempt)
        tracker.onRecovered("p")
        assertEquals(RecoveryDecision.Retry(10, 1), tracker.onEnded("p"))
    }

    @Test
    fun resetAndClearForgetPeers() {
        val tracker = RecoveryTracker(RecoveryPolicy(maxAttempts = 1, backoffMs = 10))
        tracker.onEnded("p")
        tracker.reset("p")
        assertEquals(1, retry(tracker.onEnded("p")).attempt)
        tracker.onEnded("q")
        tracker.clear()
        assertEquals(1, retry(tracker.onEnded("p")).attempt)
        assertEquals(1, retry(tracker.onEnded("q")).attempt)
    }

    @Test
    fun autoRecoverOffGivesUpImmediately() {
        val tracker = RecoveryTracker(RecoveryPolicy(autoRecover = false))
        assertEquals(RecoveryDecision.GiveUp, tracker.onEnded("p"))
    }

    @Test
    fun silentPeersOnlyIncludesQuietRangingPeers() {
        val now = 100_000L
        val devices = listOf(
            NearbyDevice("quiet", "q", state = DeviceState.Ranging, lastSeen = now - 15_000),
            NearbyDevice("fresh", "f", state = DeviceState.Ranging, lastSeen = now - 1_000),
            NearbyDevice("suspended", "s", state = DeviceState.Suspended, lastSeen = now - 50_000),
            NearbyDevice("recovering", "r", state = DeviceState.Recovering, lastSeen = now - 50_000),
            NearbyDevice("discovered", "d", state = DeviceState.Discovered, lastSeen = now - 50_000),
        )
        val silent = RecoveryTracker.silentPeers(devices, now, timeoutMs = 10_000)
        assertEquals(listOf("quiet"), silent.map { it.id })
    }

    @Test
    fun silentPeersRespectsTheExactTimeout() {
        val now = 50_000L
        val atLimit = NearbyDevice("edge", "e", state = DeviceState.Ranging, lastSeen = now - 10_000)
        assertTrue(RecoveryTracker.silentPeers(listOf(atLimit), now, 10_000).isEmpty())
        assertEquals(1, RecoveryTracker.silentPeers(listOf(atLimit), now + 1, 10_000).size)
    }
}
