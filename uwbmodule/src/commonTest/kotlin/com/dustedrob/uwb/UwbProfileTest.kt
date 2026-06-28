package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class UwbProfileTest {

    @Test
    fun serviceEntryDefaults() {
        val entry = ServiceEntry(
            name = "local",
            discoveryServiceUuid = UWB_CONFIG_SERVICE_UUID,
            writeToUuid = UWB_CONFIG_WRITE_CHAR_UUID,
            readFromUuid = UWB_CONFIG_CHAR_UUID,
        )
        // advertisedUuid falls back to the discovery service UUID when not overridden
        assertEquals(UWB_CONFIG_SERVICE_UUID, entry.advertisedUuid)
        // exchange defaults to phone-to-phone read/write
        assertEquals(ExchangeProtocol.ReadWrite, entry.exchange)
        // accessory-only fields default to null
        assertNull(entry.notifyFromUuid)
        assertNull(entry.initCommand)
    }

    @Test
    fun defaultProfilesAreVendorFree() {
        assertEquals(1, DEFAULT_PROFILES.size)
        assertSame(LOCAL_PROFILE, DEFAULT_PROFILES.first())
        assertEquals("local", LOCAL_PROFILE.name)
    }

    @Test
    fun bleDiscoveryConfigDefaultsToLocal() {
        val config = BleDiscoveryConfig()
        assertEquals(DEFAULT_PROFILES, config.profiles)
        assertEquals("local", config.advertiseProfile)
    }

    @Test
    fun customProfileCanOverrideAdvertisedUuidAndProtocol() {
        val accessory = object : UwbProfile {
            override val name = "accessory"
            override val discoveryServiceUuid = "2E938FD0-6A61-11ED-A1EB-0242AC120002"
            override val advertisedUuid = "11000000-27B9-42F0-82AA-2E951747BBF9"
            override val readFromUuid: String? = null
            override val writeToUuid = "2E93998A-6A61-11ED-A1EB-0242AC120002"
            override val notifyFromUuid = "2E939AF2-6A61-11ED-A1EB-0242AC120002"
            override val exchange = ExchangeProtocol.AccessoryNotify
        }
        assertEquals("11000000-27B9-42F0-82AA-2E951747BBF9", accessory.advertisedUuid)
        assertEquals(ExchangeProtocol.AccessoryNotify, accessory.exchange)
    }
}
