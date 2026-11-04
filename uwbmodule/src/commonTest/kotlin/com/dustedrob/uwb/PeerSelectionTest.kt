package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleDiscoveryConfigFilterTest {

    @Test
    fun noFilterAcceptsEverything() {
        val config = BleDiscoveryConfig()
        assertTrue(config.accepts("Anything"))
        assertTrue(config.accepts(""))
    }

    @Test
    fun substringMatchesTheInterestingPart() {
        // The shape @sdetweil described on #60: one UUID across a fleet, the identity is a substring
        // of the advertised name and the rest is don't-care.
        val config = BleDiscoveryConfig(nameContains = listOf("(42)"))
        assertTrue(config.accepts("DWM3001 (42) rev-c"))
        assertFalse(config.accepts("DWM3001 (43) rev-c"))
    }

    @Test
    fun anyEntryIsEnough() {
        val config = BleDiscoveryConfig(nameContains = listOf("(42)", "(44)"))
        assertTrue(config.accepts("board (42)"))
        assertTrue(config.accepts("board (44)"))
        assertFalse(config.accepts("board (43)"))
    }

    @Test
    fun matchIgnoresCase() {
        val config = BleDiscoveryConfig(nameContains = listOf("dwm3001"))
        assertTrue(config.accepts("DWM3001 rev-c"))
    }

    @Test
    fun filterIsIndependentOfProfileMatching() {
        // activeProfiles still governs which UUIDs are scanned for; the name is a second gate.
        val config = BleDiscoveryConfig(nameContains = listOf("nothing-matches-this"))
        assertTrue(config.activeProfiles.isNotEmpty())
        assertFalse(config.accepts("some device"))
    }

    @Test
    fun configsWithTheSameFilterAreEqual() {
        // The reason this is a list and not a predicate: a lambda field would make equality
        // reference-based, so a config rebuilt in a Compose composition would never equal the
        // previous one and everything keyed on it would be torn down and remade.
        assertTrue(
            BleDiscoveryConfig(nameContains = listOf("a", "b")) ==
                BleDiscoveryConfig(nameContains = listOf("a", "b"))
        )
        assertFalse(
            BleDiscoveryConfig(nameContains = listOf("a")) ==
                BleDiscoveryConfig(nameContains = listOf("b"))
        )
    }
}

class StaleRetentionTest {

    private val stale = 10_000L
    private val suspended = 60_000L
    private val exchanging = 30_000L

    private fun isStale(state: DeviceState, ageMs: Long, discoverOnly: Boolean = false) =
        isStale(state, ageMs, discoverOnly, stale, suspended, exchanging)

    @Test
    fun quietDiscoveredPeerIsPurgedWhenAutoConnecting() {
        assertTrue(isStale(DeviceState.Discovered, ageMs = 10_001))
        assertFalse(isStale(DeviceState.Discovered, ageMs = 9_999))
    }

    @Test
    fun discoveredPeerIsKeptInDiscoverOnlyMode() {
        // The pick list must survive: a Discovered peer is reported once, so lastSeen never moves.
        assertFalse(isStale(DeviceState.Discovered, ageMs = 10_001, discoverOnly = true))
        assertFalse(isStale(DeviceState.Discovered, ageMs = 10_000_000, discoverOnly = true))
    }

    @Test
    fun discoverOnlyDoesNotRetainOtherStates() {
        // Only Discovered is a pick-list entry. A failed or disconnected peer still ages out.
        assertTrue(isStale(DeviceState.Error, ageMs = 10_001, discoverOnly = true))
        assertTrue(isStale(DeviceState.ExchangingConfig, ageMs = 30_001, discoverOnly = true))
        assertTrue(isStale(DeviceState.Disconnected, ageMs = 10_001, discoverOnly = true))
    }

    @Test
    fun rangingAndRecoveringAreNeverPurgedHere() {
        assertFalse(isStale(DeviceState.Ranging, ageMs = 10_000_000))
        assertFalse(isStale(DeviceState.Recovering, ageMs = 10_000_000))
        assertFalse(isStale(DeviceState.Ranging, ageMs = 10_000_000, discoverOnly = true))
    }

    @Test
    fun suspendedPeerGetsTheLongerWindow() {
        assertFalse(isStale(DeviceState.Suspended, ageMs = 59_999))
        assertTrue(isStale(DeviceState.Suspended, ageMs = 60_001))
    }

    @Test
    fun exchangeInFlightSurvivesTheOrdinaryWindow() {
        // The purge used to take the connection away at 10 s and send the peer back through
        // discovery, which reconnected and was purged again, so a slow accessory never got
        // through its handshake (#66).
        assertFalse(isStale(DeviceState.ExchangingConfig, ageMs = 10_001))
        assertFalse(isStale(DeviceState.ExchangingConfig, ageMs = 29_999))
    }

    @Test
    fun exchangeThatNeverCompletesStillAgesOut() {
        // A timeout, not an exemption: otherwise a dead exchange would hold the peer forever and
        // it could never be rediscovered.
        assertTrue(isStale(DeviceState.ExchangingConfig, ageMs = 30_001))
        assertTrue(isStale(DeviceState.ExchangingConfig, ageMs = 10_000_000))
    }
}
