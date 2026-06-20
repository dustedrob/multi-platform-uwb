package com.dustedrob.uwb

/**
 * BLE GATT service profiles for the UWB configuration exchange.
 *
 * A [ServiceEntry] describes one BLE "profile": the service that is advertised/scanned for, plus the
 * two characteristics used to exchange a serialized [UwbSessionConfig].
 *
 * Protocol flow (per matched profile):
 * 1. Responder starts a GATT server and advertises [ServiceEntry.discoveryServiceUUID].
 * 2. Initiator discovers the responder via BLE scan, connects to the GATT server.
 * 3. Initiator reads [ServiceEntry.readFromUUID] to get the responder's [UwbSessionConfig].
 * 4. Initiator writes its own [UwbSessionConfig] to [ServiceEntry.writeToUUID].
 * 5. Both sides now have each other's config → start UWB ranging.
 *
 * The set of profiles and the locally advertised identity are supplied by the implementing app via
 * [BleDiscoveryConfig]; the library only ships vendor-free defaults.
 */
data class ServiceEntry(
    /** App-facing label used to select which profile this device advertises (see [BleDiscoveryConfig.advertiseProfile]). */
    val name: String,
    /** Service UUID advertised and scanned for. */
    val discoveryServiceUUID: String,
    /** Characteristic the client reads to obtain the peer's config (the peer's "tx"). */
    val readFromUUID: String,
    /** Characteristic the client writes its own config to (the peer's "rx"). */
    val writeToUUID: String,
)

/** This project's own (vendor-neutral) GATT service for UWB config exchange. */
const val UWB_CONFIG_SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"

/** Readable characteristic: the GATT server's local UWB config (serialized [UwbSessionConfig]). */
const val UWB_CONFIG_CHAR_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"

/** Writable characteristic: the GATT client writes its UWB config here. */
const val UWB_CONFIG_WRITE_CHAR_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"

/** The library's own profile — the default identity a device advertises and exchanges config over. */
val LOCAL_PROFILE = ServiceEntry(
    name = "local",
    discoveryServiceUUID = UWB_CONFIG_SERVICE_UUID,
    readFromUUID = UWB_CONFIG_CHAR_UUID,
    writeToUUID = UWB_CONFIG_WRITE_CHAR_UUID,
)

/**
 * Opt-in profile for Qorvo Nearby-Interaction accessories. Not included in [DEFAULT_PROFILES] — add it
 * explicitly via [BleDiscoveryConfig] if the app needs to talk to Qorvo NI hardware. The phone writes
 * to the accessory's rx ([writeToUUID]) and reads/notifies from its tx ([readFromUUID]).
 */
val QORVO_NEARBY_PROFILE = ServiceEntry(
    name = "QorvoNearbyFixed",
    discoveryServiceUUID = "2E938FD0-6A61-11ED-A1EB-0242AC120002",
    readFromUUID = "2E939AF2-6A61-11ED-A1EB-0242AC120002",
    writeToUUID = "2E93998A-6A61-11ED-A1EB-0242AC120002",
)

/**
 * Opt-in profile for the Nordic UART Service — Apple's common Nearby-Interaction accessory transport.
 * Not included in [DEFAULT_PROFILES]; add it explicitly when targeting NUS-based accessories.
 */
val NORDIC_NEARBY_PROFILE = ServiceEntry(
    name = "NearbyFixed",
    discoveryServiceUUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
    readFromUUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
    writeToUUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
)

/** Vendor-free default: only the library's own [LOCAL_PROFILE]. Apps add vendor profiles explicitly. */
val DEFAULT_PROFILES = listOf(LOCAL_PROFILE)

/**
 * App-owned configuration for BLE discovery and config exchange.
 *
 * @property profiles the service profiles to scan for and host on the local GATT server.
 * @property advertiseProfile the [ServiceEntry.name] of the profile this device advertises as its own
 *   identity. Must match one of [profiles].
 */
data class BleDiscoveryConfig(
    val profiles: List<ServiceEntry> = DEFAULT_PROFILES,
    val advertiseProfile: String = DEFAULT_PROFILES.first().name,
)
