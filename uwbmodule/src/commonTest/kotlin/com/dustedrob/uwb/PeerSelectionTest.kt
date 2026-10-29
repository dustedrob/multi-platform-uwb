package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleDiscoveryConfigFilterTest {

    @Test
    fun noFilterAcceptsEverything() {
        val config = BleDiscoveryConfig()
        assertTrue(config.accepts("AA:BB:CC:DD:EE:FF", "Anything"))
        assertTrue(config.accepts("", ""))
    }

    @Test
    fun substringFilterMatchesTheInterestingPart() {
        // The shape @sdetweil described on #60: one UUID across a fleet, the identity is a substring
        // of the advertised name and the rest is don't-care.
        val config = BleDiscoveryConfig(deviceFilter = { _, name -> "(42)" in name })
        assertTrue(config.accepts("id-1", "DWM3001 (42) rev-c"))
        assertFalse(config.accepts("id-2", "DWM3001 (43) rev-c"))
    }

    @Test
    fun filterSeesBothIdAndName() {
        val config = BleDiscoveryConfig(deviceFilter = { id, name -> id == "keep" || name == "keep" })
        assertTrue(config.accepts("keep", "no"))
        assertTrue(config.accepts("no", "keep"))
        assertFalse(config.accepts("no", "no"))
    }

    @Test
    fun filterIsIndependentOfProfileMatching() {
        // activeProfiles still governs which UUIDs are scanned for; the filter is a second gate.
        val config = BleDiscoveryConfig(deviceFilter = { _, _ -> false })
        assertTrue(config.activeProfiles.isNotEmpty())
        assertFalse(config.accepts("id", "name"))
    }
}

class StaleRetentionTest {

    private val stale = 10_000L
    private val suspended = 60_000L

    private fun isStale(state: DeviceState, ageMs: Long, discoverOnly: Boolean = false) =
        isStale(state, ageMs, discoverOnly, stale, suspended)

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
        assertTrue(isStale(DeviceState.ExchangingConfig, ageMs = 10_001, discoverOnly = true))
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
}
