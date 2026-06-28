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
    fun parsesLegacyPayloadWithoutKeyTrailer() {
        // A payload from before the session-key trailer existed: it ends right after
        // the discovery-token block, with no [2B key.size] trailer at all.
        val sid = 42
        val ch = 9
        val pre = 10
        val addr = byteArrayOf(0x01, 0x02)
        val legacy = byteArrayOf(
            1, // version
            (sid shr 24).toByte(), (sid shr 16).toByte(), (sid shr 8).toByte(), sid.toByte(),
            (ch shr 24).toByte(), (ch shr 16).toByte(), (ch shr 8).toByte(), ch.toByte(),
            (pre shr 24).toByte(), (pre shr 16).toByte(), (pre shr 8).toByte(), pre.toByte(),
            (addr.size shr 8).toByte(), addr.size.toByte(), addr[0], addr[1],
            0, 0, // token size = 0
        )
        val restored = UwbSessionConfig.fromByteArray(legacy)
        assertNotNull(restored)
        assertEquals(sid, restored.sessionId)
        assertNull(restored.sessionKey)
        assertNull(restored.accessoryData)
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
}
