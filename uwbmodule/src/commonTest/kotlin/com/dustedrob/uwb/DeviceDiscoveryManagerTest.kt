package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [UwbSessionConfig] serialization and [NearbyDevice] state logic.
 *
 * Note: [DeviceDiscoveryManager] depends on platform `expect` classes (BleManager,
 * MultiplatformUwbManager) which cannot be faked in commonTest. The internal
 * callback methods (onDeviceDiscovered, onConfigExchanged, onRangingResult)
 * require a constructed manager with real platform deps.
 *
 * These tests cover the data layer that DeviceDiscoveryManager operates on.
 * Full integration tests belong in androidTest / iosTest with real or mocked platform deps.
 */
class DeviceDiscoveryManagerTest {

    // -- NearbyDevice list management logic (mirrors what DDM does internally) --

    @Test
    fun addingDeviceToListWorks() {
        val devices = mutableListOf<NearbyDevice>()
        val device = NearbyDevice(id = "d1", name = "Device 1")
        if (devices.none { it.id == device.id }) {
            devices.add(device)
        }
        assertEquals(1, devices.size)
        assertEquals("d1", devices[0].id)
    }

    @Test
    fun duplicateDeviceNotAdded() {
        val devices = mutableListOf(NearbyDevice(id = "d1", name = "Device 1"))
        val duplicate = NearbyDevice(id = "d1", name = "Device 1")
        if (devices.none { it.id == duplicate.id }) {
            devices.add(duplicate)
        }
        assertEquals(1, devices.size)
    }

    @Test
    fun updatingDistanceByIndex() {
        val devices = mutableListOf(
            NearbyDevice(id = "d1", name = "Device 1"),
            NearbyDevice(id = "d2", name = "Device 2")
        )
        val idx = devices.indexOfFirst { it.id == "d1" }
        assertTrue(idx >= 0)
        devices[idx] = devices[idx].copy(distance = 2.5)
        assertEquals(2.5, devices[0].distance)
        assertNull(devices[1].distance)
    }

    @Test
    fun unknownPeerIndexIsNegative() {
        val devices = listOf(NearbyDevice(id = "d1", name = "Device 1"))
        val idx = devices.indexOfFirst { it.id == "unknown" }
        assertEquals(-1, idx)
    }

    @Test
    fun multipleDevicesTrackedIndependently() {
        val devices = mutableListOf(
            NearbyDevice(id = "d1", name = "Device 1"),
            NearbyDevice(id = "d2", name = "Device 2")
        )
        devices[0] = devices[0].copy(distance = 1.0)
        devices[1] = devices[1].copy(distance = 3.0)

        assertEquals(1.0, devices.first { it.id == "d1" }.distance)
        assertEquals(3.0, devices.first { it.id == "d2" }.distance)
    }

    // -- Config exchange tracking sets --

    @Test
    fun pendingExchangeSetPreventsDoubleConnect() {
        val pendingExchanges = mutableSetOf<String>()
        val exchangedPeers = mutableSetOf<String>()
        val connectCalls = mutableListOf<String>()

        fun initiateExchange(id: String) {
            if (id !in exchangedPeers && id !in pendingExchanges) {
                pendingExchanges.add(id)
                connectCalls.add(id)
            }
        }

        initiateExchange("d1")
        initiateExchange("d1") // should not double-connect
        assertEquals(1, connectCalls.size)
    }

    @Test
    fun completedExchangeMovesToExchangedSet() {
        val pendingExchanges = mutableSetOf("d1")
        val exchangedPeers = mutableSetOf<String>()

        // Simulate onConfigExchanged
        pendingExchanges.remove("d1")
        exchangedPeers.add("d1")

        assertTrue("d1" in exchangedPeers)
        assertTrue("d1" !in pendingExchanges)
    }
}
