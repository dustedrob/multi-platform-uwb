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

    // -- Connection choice: both phones must range over the same BLE connection --

    @Test
    fun connectionScoreIsSymmetric() {
        // On one connection A holds (its config, B's config) and B holds (B's config, A's config);
        // the score must not depend on which end computes it.
        val a = phoneConfig(byteArrayOf(0x12, 0x34), keyA)
        val b = phoneConfig(byteArrayOf(0x09, 0x4d), keyB)
        assertEquals(
            DeviceDiscoveryManager.connectionScore(a, b),
            DeviceDiscoveryManager.connectionScore(b, a),
        )
    }

    @Test
    fun connectionScoreDistinguishesConnections() {
        val a1 = phoneConfig(byteArrayOf(0x12, 0x34), keyA)
        val a2 = phoneConfig(byteArrayOf(0x56, 0x78), keyA)
        val b1 = phoneConfig(byteArrayOf(0x09, 0x4d), keyB)
        assertTrue(DeviceDiscoveryManager.connectionScore(a1, b1) != DeviceDiscoveryManager.connectionScore(a2, b1))
    }

    /**
     * Mirror of onConfigExchanged's keep-preferred rule: a binding is replaced only when the new
     * connection scores strictly lower. Returns the peerId ranged with after each arrival.
     */
    private class ConnectionChooser {
        private var bound: Pair<String, String>? = null // peerId, score
        fun onExchange(peerId: String, local: UwbSessionConfig, remote: UwbSessionConfig): String {
            val score = DeviceDiscoveryManager.connectionScore(local, remote)
            val prev = bound
            if (prev == null || score < prev.second) bound = peerId to score
            return bound!!.first
        }
    }

    @Test
    fun bothPhonesConvergeOnTheSameConnectionInAnyOrder() {
        // Phone A and phone B each mint one address pair per BLE identity of the other. Conn 1 is
        // A(client)->B(server): A uses A1 for "B-adv", B uses B2 for "A-central". Conn 2 is the
        // reverse: B uses B1 for "A-adv", A uses A2 for "B-central". Each side sees the client-side
        // exchange first, so with keep-first A would keep conn 1 and B conn 2 and neither session
        // would pair. With the score both must land on the same connection whatever the order.
        val a1 = phoneConfig(byteArrayOf(0x40, 0x01), keyA)
        val a2 = phoneConfig(byteArrayOf(0x40, 0x02), keyA)
        val b1 = phoneConfig(byteArrayOf(0x30, 0x01), keyB)
        val b2 = phoneConfig(byteArrayOf(0x30, 0x02), keyB)

        // A's view: conn 1 = (A1, B2) under "B-adv", conn 2 = (A2, B1) under "B-central".
        // B's view: conn 2 = (B1, A2) under "A-adv", conn 1 = (B2, A1) under "A-central".
        val aFirstConn1 = ConnectionChooser().apply { onExchange("B-adv", a1, b2) }.onExchange("B-central", a2, b1)
        val aFirstConn2 = ConnectionChooser().apply { onExchange("B-central", a2, b1) }.onExchange("B-adv", a1, b2)
        val bFirstConn2 = ConnectionChooser().apply { onExchange("A-adv", b1, a2) }.onExchange("A-central", b2, a1)
        val bFirstConn1 = ConnectionChooser().apply { onExchange("A-central", b2, a1) }.onExchange("A-adv", b1, a2)

        // Order must not matter on either phone.
        assertEquals(aFirstConn1, aFirstConn2)
        assertEquals(bFirstConn2, bFirstConn1)
        // And both must have picked the same physical connection: A on "B-adv" pairs with B on
        // "A-central" (conn 1); A on "B-central" pairs with B on "A-adv" (conn 2).
        val expectedOnB = if (aFirstConn1 == "B-adv") "A-central" else "A-adv"
        assertEquals(expectedOnB, bFirstConn2)
    }

    @Test
    fun iosConfigsHaveNoIdentityKey() {
        // iOS configs carry neither a UWB address nor a key, so identity dedup is bypassed entirely.
        val ios = UwbSessionConfig(0, 0, 0, ByteArray(0), discoveryToken = byteArrayOf(1, 2, 3))
        assertNull(DeviceDiscoveryManager.identityKeyFor(ios))
    }
}
