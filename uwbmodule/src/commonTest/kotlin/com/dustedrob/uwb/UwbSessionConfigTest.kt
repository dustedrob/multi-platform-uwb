package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UwbSessionConfigTest {

    @Test
    fun roundtripWithoutDiscoveryToken() {
        val config = UwbSessionConfig(
            sessionId = 42,
            channel = 9,
            preambleIndex = 10,
            uwbAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            discoveryToken = null
        )
        val bytes = config.toByteArray()
        val restored = UwbSessionConfig.fromByteArray(bytes)
        assertNotNull(restored)
        assertEquals(config.sessionId, restored.sessionId)
        assertEquals(config.channel, restored.channel)
        assertEquals(config.preambleIndex, restored.preambleIndex)
        assertTrue(config.uwbAddress.contentEquals(restored.uwbAddress))
        assertNull(restored.discoveryToken)
    }

    @Test
    fun roundtripWithDiscoveryToken() {
        val token = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val config = UwbSessionConfig(
            sessionId = 1000,
            channel = 5,
            preambleIndex = 3,
            uwbAddress = byteArrayOf(0x10, 0x20),
            discoveryToken = token
        )
        val bytes = config.toByteArray()
        val restored = UwbSessionConfig.fromByteArray(bytes)
        assertNotNull(restored)
        assertEquals(config.sessionId, restored.sessionId)
        assertEquals(config.channel, restored.channel)
        assertEquals(config.preambleIndex, restored.preambleIndex)
        assertTrue(config.uwbAddress.contentEquals(restored.uwbAddress))
        assertNotNull(restored.discoveryToken)
        assertTrue(token.contentEquals(restored.discoveryToken!!))
    }

    @Test
    fun roundtripWithEmptyUwbAddress() {
        val config = UwbSessionConfig(
            sessionId = 99,
            channel = 9,
            preambleIndex = 10,
            uwbAddress = ByteArray(0),
            discoveryToken = byteArrayOf(0x01)
        )
        val bytes = config.toByteArray()
        val restored = UwbSessionConfig.fromByteArray(bytes)
        assertNotNull(restored)
        assertEquals(0, restored.uwbAddress.size)
    }

    @Test
    fun roundtripWithLargeSessionId() {
        val config = UwbSessionConfig(
            sessionId = Int.MAX_VALUE,
            channel = Int.MAX_VALUE,
            preambleIndex = Int.MAX_VALUE,
            uwbAddress = byteArrayOf(0xFF.toByte()),
        )
        val bytes = config.toByteArray()
        val restored = UwbSessionConfig.fromByteArray(bytes)
        assertNotNull(restored)
        assertEquals(Int.MAX_VALUE, restored.sessionId)
        assertEquals(Int.MAX_VALUE, restored.channel)
        assertEquals(Int.MAX_VALUE, restored.preambleIndex)
    }

    @Test
    fun fromByteArrayReturnNullOnTooShort() {
        assertNull(UwbSessionConfig.fromByteArray(ByteArray(5)))
    }

    @Test
    fun fromByteArrayReturnNullOnWrongVersion() {
        val config = UwbSessionConfig(42, 9, 10, byteArrayOf(1))
        val bytes = config.toByteArray()
        bytes[0] = 99 // corrupt version
        assertNull(UwbSessionConfig.fromByteArray(bytes))
    }

    @Test
    fun equalityWithByteArrays() {
        val a = UwbSessionConfig(1, 2, 3, byteArrayOf(1, 2), byteArrayOf(3, 4))
        val b = UwbSessionConfig(1, 2, 3, byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun roundtripWithSessionKey() {
        val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val config = UwbSessionConfig(
            sessionId = 4382,
            channel = 9,
            preambleIndex = 10,
            uwbAddress = byteArrayOf(0x86.toByte(), 0xE4.toByte()),
            discoveryToken = null,
            sessionKey = key,
        )
        val bytes = config.toByteArray()
        val restored = UwbSessionConfig.fromByteArray(bytes)
        assertNotNull(restored)
        assertNotNull(restored.sessionKey)
        assertTrue(key.contentEquals(restored.sessionKey!!))
        assertEquals(config, restored)
    }

    @Test
    fun roundtripWithBothTokenAndKey() {
        val token = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val key = ByteArray(8) { it.toByte() }
        val config = UwbSessionConfig(7, 9, 10, byteArrayOf(0x01), token, key)
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertTrue(token.contentEquals(restored.discoveryToken!!))
        assertTrue(key.contentEquals(restored.sessionKey!!))
    }

    @Test
    fun sessionKeyNullWhenAbsent() {
        val config = UwbSessionConfig(1, 2, 3, byteArrayOf(9), discoveryToken = byteArrayOf(1, 2))
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertNull(restored.sessionKey)
    }

    @Test
    fun parsesShortPayloadWithoutKeyTrailer() {
        // A minimal payload that ends right after the discovery-token block, with no [2B key.size]
        // trailer at all. Bytes are little-endian (LSB first), matching the wire format.
        val sid = 42
        val ch = 9
        val pre = 10
        val addr = byteArrayOf(0x01, 0x02)
        val short = byteArrayOf(
            1, // version
            sid.toByte(), (sid shr 8).toByte(), (sid shr 16).toByte(), (sid shr 24).toByte(),
            ch.toByte(), (ch shr 8).toByte(), (ch shr 16).toByte(), (ch shr 24).toByte(),
            pre.toByte(), (pre shr 8).toByte(), (pre shr 16).toByte(), (pre shr 24).toByte(),
            addr.size.toByte(), (addr.size shr 8).toByte(), addr[0], addr[1],
            0, 0, // token size = 0
        )
        val restored = UwbSessionConfig.fromByteArray(short)
        assertNotNull(restored)
        assertEquals(sid, restored.sessionId)
        assertNull(restored.sessionKey)
        assertNull(restored.accessoryData)
    }

    @Test
    fun serializesMultiByteFieldsLittleEndian() {
        // Pins the wire contract: multi-byte integers are little-endian (LSB first) so accessory
        // firmware can lay out its FiRa struct natively (session_id = 0x691A4B22, channel = 9,
        // preamble = 10, address_size = 2) without byte-swapping.
        val config = UwbSessionConfig(
            sessionId = 0x691A4B22,
            channel = 9,
            preambleIndex = 10,
            uwbAddress = byteArrayOf(0x02, 0xC9.toByte()),
        )
        val b = config.toByteArray()
        assertEquals(1.toByte(), b[0]) // version
        // sessionId 0x691A4B22 -> 22 4B 1A 69
        assertEquals(0x22.toByte(), b[1]); assertEquals(0x4B.toByte(), b[2])
        assertEquals(0x1A.toByte(), b[3]); assertEquals(0x69.toByte(), b[4])
        // channel 9 -> 09 00 00 00
        assertEquals(0x09.toByte(), b[5]); assertEquals(0.toByte(), b[6])
        assertEquals(0.toByte(), b[7]); assertEquals(0.toByte(), b[8])
        // preambleIndex 10 -> 0A 00 00 00
        assertEquals(0x0A.toByte(), b[9]); assertEquals(0.toByte(), b[10])
        assertEquals(0.toByte(), b[11]); assertEquals(0.toByte(), b[12])
        // address size 2 -> 02 00, then the 2 address bytes verbatim
        assertEquals(0x02.toByte(), b[13]); assertEquals(0.toByte(), b[14])
        assertEquals(0x02.toByte(), b[15]); assertEquals(0xC9.toByte(), b[16])
        // and it round-trips
        assertEquals(config, UwbSessionConfig.fromByteArray(b))
    }

    @Test
    fun roundtripWithAccessoryData() {
        val acc = ByteArray(40) { (it * 7).toByte() }
        val config = UwbSessionConfig(
            sessionId = 0,
            channel = 0,
            preambleIndex = 0,
            uwbAddress = ByteArray(0),
            accessoryData = acc,
        )
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertNotNull(restored.accessoryData)
        assertTrue(acc.contentEquals(restored.accessoryData!!))
        assertNull(restored.discoveryToken)
        assertNull(restored.sessionKey)
        assertEquals(config, restored)
    }

    @Test
    fun accessoryDataNullWhenAbsent() {
        val config = UwbSessionConfig(1, 2, 3, byteArrayOf(9), sessionKey = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertNull(restored.accessoryData)
        assertTrue(restored.sessionKey!!.size == 8)
    }

    @Test
    fun sessionIdZeroRoundTrips() {
        // Regression guard: fromByteArray must NOT treat sessionId == 0 as "not a config".
        val config = UwbSessionConfig(
            sessionId = 0,
            channel = 9,
            preambleIndex = 10,
            uwbAddress = byteArrayOf(0x0A, 0x0B),
        )
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertEquals(0, restored.sessionId)
        assertEquals(config, restored)
    }

    @Test
    fun iosStyleConfigRoundTrips() {
        // iOS local configs use sessionId = 0, empty address, and a discovery token; this must parse
        // on the receiving (e.g. Android) side.
        val token = ByteArray(64) { (it * 3).toByte() }
        val config = UwbSessionConfig(
            sessionId = 0,
            channel = 0,
            preambleIndex = 0,
            uwbAddress = ByteArray(0),
            discoveryToken = token,
        )
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertEquals(0, restored.sessionId)
        assertTrue(token.contentEquals(restored.discoveryToken!!))
        assertNull(restored.sessionKey)
        assertNull(restored.accessoryData)
    }

    @Test
    fun roundtripWithAllTrailers() {
        val token = byteArrayOf(0x01, 0x02, 0x03)
        val key = ByteArray(8) { (it + 1).toByte() }
        val acc = ByteArray(20) { (0xF0 - it).toByte() }
        val config = UwbSessionConfig(
            sessionId = 4242,
            channel = 9,
            preambleIndex = 11,
            uwbAddress = byteArrayOf(0x86.toByte(), 0xE4.toByte()),
            discoveryToken = token,
            sessionKey = key,
            accessoryData = acc,
        )
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertEquals(config, restored)
        assertTrue(token.contentEquals(restored.discoveryToken!!))
        assertTrue(key.contentEquals(restored.sessionKey!!))
        assertTrue(acc.contentEquals(restored.accessoryData!!))
    }

    @Test
    fun negativeSessionIdRoundTripsLittleEndian() {
        // High-bit-set values must survive the little-endian read/write symmetrically.
        for (sid in intArrayOf(-1, Int.MIN_VALUE, 0x80000000.toInt(), -123456)) {
            val config = UwbSessionConfig(sid, 9, 10, byteArrayOf(1, 2))
            val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
            assertNotNull(restored)
            assertEquals(sid, restored.sessionId)
        }
    }

    @Test
    fun emptyAccessoryDataParsesAsNull() {
        // A zero-length trailer is indistinguishable from absent, so it comes back null.
        val config = UwbSessionConfig(1, 2, 3, byteArrayOf(9), accessoryData = ByteArray(0))
        val restored = UwbSessionConfig.fromByteArray(config.toByteArray())
        assertNotNull(restored)
        assertNull(restored.accessoryData)
    }

    @Test
    fun truncatedKeyTrailerParsesConfigWithNullKey() {
        // Payload declares an 8-byte key trailer but includes no key bytes. Parsing must not crash
        // or reject the config; the trailer is simply treated as absent.
        val sid = 5
        val ch = 9
        val pre = 10
        val truncated = byteArrayOf(
            1, // version
            sid.toByte(), (sid shr 8).toByte(), (sid shr 16).toByte(), (sid shr 24).toByte(),
            ch.toByte(), (ch shr 8).toByte(), (ch shr 16).toByte(), (ch shr 24).toByte(),
            pre.toByte(), (pre shr 8).toByte(), (pre shr 16).toByte(), (pre shr 24).toByte(),
            0, 0, // addr size = 0
            0, 0, // token size = 0
            8, 0, // key size = 8, but no key bytes follow
        )
        val restored = UwbSessionConfig.fromByteArray(truncated)
        assertNotNull(restored)
        assertEquals(sid, restored.sessionId)
        assertNull(restored.sessionKey)
        assertNull(restored.accessoryData)
    }
}
