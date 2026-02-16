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
}
