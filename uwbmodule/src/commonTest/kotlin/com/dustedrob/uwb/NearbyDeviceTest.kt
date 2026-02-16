package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NearbyDeviceTest {

    @Test
    fun defaultDistanceIsNull() {
        val device = NearbyDevice(id = "abc", name = "Test")
        assertNull(device.distance)
    }

    @Test
    fun copyUpdatesDistance() {
        val device = NearbyDevice(id = "abc", name = "Test")
        val updated = device.copy(distance = 1.5)
        assertEquals(1.5, updated.distance)
        assertEquals("abc", updated.id)
    }

    @Test
    fun equalityById() {
        val a = NearbyDevice(id = "x", name = "A", lastSeen = 100)
        val b = NearbyDevice(id = "x", name = "A", lastSeen = 100)
        assertEquals(a, b)
    }

    @Test
    fun inequalityByDifferentId() {
        val a = NearbyDevice(id = "x", name = "A", lastSeen = 100)
        val b = NearbyDevice(id = "y", name = "A", lastSeen = 100)
        assertNotEquals(a, b)
    }
}
