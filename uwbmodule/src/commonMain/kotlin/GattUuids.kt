package com.dustedrob.uwb

/**
 * BLE GATT UUIDs for the UWB configuration exchange protocol.
 *
 * Protocol flow:
 * 1. Responder starts a GATT server and advertises [UWB_CONFIG_SERVICE_UUID].
 * 2. Initiator discovers responder via BLE scan, connects to GATT server.
 * 3. Initiator reads [UWB_CONFIG_CHAR_UUID] to get responder's [UwbSessionConfig].
 * 4. Initiator writes its own [UwbSessionConfig] to [UWB_CONFIG_WRITE_CHAR_UUID].
 * 5. Both sides now have each other's config → start UWB ranging.
 */

data class ServiceEntry (
    val name: String,
    val discoveryServiceUUID: String,
    val readFromUUID: String,
    val writeToUUID: String
)
    /** Primary GATT service for UWB config exchange. */
    const val UWB_CONFIG_SERVICE_UUID =  "0000FFF0-0000-1000-8000-00805F9B34FB"

    /** Readable characteristic: the GATT server's local UWB config (serialized [UwbSessionConfig]). */
    const val UWB_CONFIG_CHAR_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"

    /** Writable characteristic: the GATT client writes its UWB config here. */
    const val UWB_CONFIG_WRITE_CHAR_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"
    const val UWB_DISCOVERY_SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"

var  GattUuids = arrayOf<ServiceEntry>(

    ServiceEntry("us", UWB_CONFIG_SERVICE_UUID, UWB_CONFIG_CHAR_UUID,UWB_CONFIG_WRITE_CHAR_UUID ),
    ServiceEntry("QorvoNearbyFixed", "2E938FD0-6A61-11ED-A1EB-0242AC120002", "2E939AF2-6A61-11ED-A1EB-0242AC120002" ,"2E93998A-6A61-11ED-A1EB-0242AC120002" ),
    ServiceEntry("NearbyFixed", "6E400001-B5A3-F393-E0A9-E50E24DCCA9E", "6E400003-B5A3-F393-E0A9-E50E24DCCA9E", "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" ),

    /** BLE service UUID used for advertising/discovery (same as before, for scan filters). */   
)
