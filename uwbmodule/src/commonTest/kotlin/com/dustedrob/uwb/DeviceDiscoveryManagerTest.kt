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

    // -- Peer-identity dedup (identityKeyFor + the keep-first rule in onConfigExchanged) --

    private val keyA = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val keyB = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)

    private fun phoneConfig(addr: ByteArray, key: ByteArray) =
        UwbSessionConfig(1, 9, 11, addr, sessionKey = key)

    @Test
    fun samePhoneUnderTwoBleIdsCollapsesToOne() {
        // A phone that both scans and serves shows up under two randomized BLE ids. With one session
        // scope per peer it also hands each of our identities a different UWB address, so the address
        // can't be the identity any more; the session key (one per device) is. First one is kept, the
        // later duplicate is ignored (no teardown of the running session).
        val identityKeyToPeer = mutableMapOf<String, String>()
        val devices = mutableListOf(
            NearbyDevice("58:CD:3D:CF:63:7F", "UWB Device"),
            NearbyDevice("43:E3:E1:1B:D4:97", "UWB Device"),
        )
        val ignored = mutableListOf<String>()

        fun onConfigExchanged(peerId: String, remote: UwbSessionConfig) {
            val key = DeviceDiscoveryManager.identityKeyFor(remote) ?: return
            val prev = identityKeyToPeer[key]
            if (prev != null && prev != peerId) {
                ignored.add(peerId)
                devices.removeAll { it.id == peerId }
                return
            }
            identityKeyToPeer[key] = peerId
        }

        onConfigExchanged("58:CD:3D:CF:63:7F", phoneConfig(byteArrayOf(0x09, 0x4d), keyA)) // kept
        onConfigExchanged("43:E3:E1:1B:D4:97", phoneConfig(byteArrayOf(0x05, 0x88.toByte()), keyA)) // same phone, other address

        assertEquals(1, devices.size)
        assertEquals("58:CD:3D:CF:63:7F", devices[0].id)
        assertEquals(listOf("43:E3:E1:1B:D4:97"), ignored)
    }

    @Test
    fun distinctPhonesAreKeptSeparate() {
        val a = DeviceDiscoveryManager.identityKeyFor(phoneConfig(byteArrayOf(0x09, 0x4d), keyA))
        val b = DeviceDiscoveryManager.identityKeyFor(phoneConfig(byteArrayOf(0x05, 0x88.toByte()), keyB))
        assertTrue(a != null && b != null && a != b)
    }

    @Test
    fun accessoriesKeyOnTheirAddressNotOurKey() {
        // The "remote" config for an accessory is our own config with the accessory address patched
        // in, so two accessories carry the same (our) session key. They must still be two devices.
        val acc1 = UwbSessionConfig(1, 9, 11, byteArrayOf(0x11, 0x11), sessionKey = keyA, isAccessoryDevice = true)
        val acc2 = UwbSessionConfig(1, 9, 11, byteArrayOf(0x22, 0x22), sessionKey = keyA, isAccessoryDevice = true)
        val k1 = DeviceDiscoveryManager.identityKeyFor(acc1)
        val k2 = DeviceDiscoveryManager.identityKeyFor(acc2)
        assertTrue(k1 != null && k2 != null && k1 != k2)
        // And an accessory never collides with the phone whose key it echoes.
        assertTrue(k1 != DeviceDiscoveryManager.identityKeyFor(phoneConfig(byteArrayOf(0x11, 0x11), keyA)))
    }

    @Test
    fun iosConfigsHaveNoIdentityKey() {
        // iOS configs carry neither a UWB address nor a key, so identity dedup is bypassed entirely.
        val ios = UwbSessionConfig(0, 0, 0, ByteArray(0), discoveryToken = byteArrayOf(1, 2, 3))
        assertNull(DeviceDiscoveryManager.identityKeyFor(ios))
    }
}
