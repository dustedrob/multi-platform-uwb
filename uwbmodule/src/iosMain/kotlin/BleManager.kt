package com.dustedrob.uwb

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
actual class BleManager {

    private val serviceUUID = CBUUID.UUIDWithString(GattUuids.UWB_DISCOVERY_SERVICE_UUID)
    private val configReadUUID = CBUUID.UUIDWithString(GattUuids.UWB_CONFIG_CHAR_UUID)
    private val configWriteUUID = CBUUID.UUIDWithString(GattUuids.UWB_CONFIG_WRITE_CHAR_UUID)

    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null

    private var deviceDiscoveredCallback: ((String, String) -> Unit)? = null
    private var configExchangedCallback: ((String, UwbSessionConfig) -> Unit)? = null

    // GATT server state
    private var localConfig: UwbSessionConfig? = null
    private var readCharacteristic: CBMutableCharacteristic? = null

    // Track discovered peripherals for GATT client connections
    private val discoveredPeripherals = mutableMapOf<String, CBPeripheral>()

    // Pending config exchange state
    private var pendingLocalConfig: UwbSessionConfig? = null
    private var pendingPeerId: String? = null

    // Deferred operations waiting for poweredOn
    private var scanWhenReady = false
    private var advertiseWhenReady = false

    // ---- Central (scanner/client) delegate ----

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                NSLog("BleManager: Central powered on")
                if (scanWhenReady) {
                    scanWhenReady = false
                    central.scanForPeripheralsWithServices(listOf(serviceUUID), null)
                    NSLog("BleManager: Deferred scan started")
                }
            } else {
                NSLog("BleManager: Central state = ${central.state}")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            val deviceId = didDiscoverPeripheral.identifier.UUIDString
            val deviceName = didDiscoverPeripheral.name ?: "Unknown Device"

            // Cache peripheral (must keep strong reference for connection)
            discoveredPeripherals[deviceId] = didDiscoverPeripheral

            NSLog("BleManager: Discovered $deviceName ($deviceId)")
            deviceDiscoveredCallback?.invoke(deviceId, deviceName)
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            val deviceId = didConnectPeripheral.identifier.UUIDString
            NSLog("BleManager: Connected to $deviceId, discovering services")
            didConnectPeripheral.delegate = peripheralClientDelegate
            didConnectPeripheral.discoverServices(listOf(serviceUUID))
        }

        // Note: didFailToConnect and didDisconnect omitted to avoid ObjC selector conflicts
    }

    // ---- Peripheral (GATT client role) delegate — used when WE connect to a peer's GATT server ----

    private val peripheralClientDelegate = object : NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                NSLog("BleManager: Service discovery error: ${didDiscoverServices.localizedDescription}")
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }

            val service = peripheral.services?.firstOrNull {
                (it as? CBService)?.UUID == serviceUUID
            } as? CBService

            if (service == null) {
                NSLog("BleManager: UWB service not found on ${peripheral.identifier.UUIDString}")
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }

            peripheral.discoverCharacteristics(listOf(configReadUUID, configWriteUUID), service)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            if (error != null) {
                NSLog("BleManager: Characteristic discovery error: ${error.localizedDescription}")
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }

            // Step 1: Read the peer's config
            val readChar = didDiscoverCharacteristicsForService.characteristics?.firstOrNull {
                (it as? CBCharacteristic)?.UUID == configReadUUID
            } as? CBCharacteristic

            if (readChar != null) {
                peripheral.readValueForCharacteristic(readChar)
            } else {
                NSLog("BleManager: Read characteristic not found")
                centralManager?.cancelPeripheralConnection(peripheral)
            }
        }


        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) {
                NSLog("BleManager: Characteristic update error: ${error.localizedDescription}")
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }

            if (didUpdateValueForCharacteristic.UUID == configReadUUID) {
                val data = didUpdateValueForCharacteristic.value
                if (data != null) {
                    val remoteConfig = UwbSessionConfig.fromByteArray(data.toByteArray())
                    val peerId = peripheral.identifier.UUIDString

                    if (remoteConfig != null) {
                        NSLog("BleManager: Received config from $peerId")
                        configExchangedCallback?.invoke(peerId, remoteConfig)
                    } else {
                        NSLog("BleManager: Failed to parse config from $peerId")
                    }
                }

                // Step 2: Write our config to the peer (using WriteWithoutResponse to avoid needing didWriteValue callback)
                val localCfg = pendingLocalConfig
                if (localCfg != null) {
                    val service = peripheral.services?.firstOrNull {
                        (it as? CBService)?.UUID == serviceUUID
                    } as? CBService

                    val writeChar = service?.characteristics?.firstOrNull {
                        (it as? CBCharacteristic)?.UUID == configWriteUUID
                    } as? CBCharacteristic

                    if (writeChar != null) {
                        val configData = localCfg.toByteArray().toNSData()
                        peripheral.writeValue(configData, writeChar, CBCharacteristicWriteWithoutResponse)
                        NSLog("BleManager: Wrote config to ${peripheral.identifier.UUIDString}")
                    }
                }
                // Exchange complete, disconnect
                centralManager?.cancelPeripheralConnection(peripheral)
                pendingLocalConfig = null
                pendingPeerId = null
            }
        }
        // Note: didWriteValueForCharacteristic omitted to avoid ObjC selector conflict
        // Using WriteWithoutResponse instead
    }

    // ---- Peripheral Manager (GATT server) delegate ----

    private val peripheralManagerDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {

        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheral.state == CBManagerStatePoweredOn) {
                NSLog("BleManager: Peripheral manager powered on")
                if (advertiseWhenReady) {
                    advertiseWhenReady = false
                    startAdvertisingInternal()
                }
            } else {
                NSLog("BleManager: Peripheral manager state = ${peripheral.state}")
            }
        }

        override fun peripheralManagerDidStartAdvertising(
            peripheral: CBPeripheralManager,
            error: NSError?
        ) {
            if (error != null) {
                NSLog("BleManager: Advertising error: ${error.localizedDescription}")
            } else {
                NSLog("BleManager: Advertising started")
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didAddService: CBService,
            error: NSError?
        ) {
            if (error != null) {
                NSLog("BleManager: Failed to add service: ${error.localizedDescription}")
            } else {
                NSLog("BleManager: GATT service added")
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveReadRequest: CBATTRequest
        ) {
            if (didReceiveReadRequest.characteristic.UUID == configReadUUID) {
                val configBytes = localConfig?.toByteArray() ?: ByteArray(0)
                val offset = didReceiveReadRequest.offset.toInt()
                if (offset < configBytes.size) {
                    val responseBytes = configBytes.copyOfRange(offset, configBytes.size)
                    didReceiveReadRequest.value = responseBytes.toNSData()
                    peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorSuccess)
                    NSLog("BleManager: Sent config via GATT read (${configBytes.size} bytes)")
                } else {
                    didReceiveReadRequest.value = ByteArray(0).toNSData()
                    peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorSuccess)
                }
            } else {
                peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorAttributeNotFound)
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>
        ) {
            didReceiveWriteRequests.forEach { request ->
                if (request is CBATTRequest && request.characteristic.UUID == configWriteUUID) {
                    val data = request.value
                    if (data != null) {
                        val remoteConfig = UwbSessionConfig.fromByteArray(data.toByteArray())
                        if (remoteConfig != null) {
                            val peerId = request.central.identifier.UUIDString
                            NSLog("BleManager: Received config via GATT write from $peerId")
                            configExchangedCallback?.invoke(peerId, remoteConfig)
                        } else {
                            NSLog("BleManager: Failed to parse written config")
                        }
                    }
                    peripheral.respondToRequest(request, CBATTErrorSuccess)
                }
            }
        }
    }

    // ---- Public API: Scanning ----

    actual fun startScanning() {
        val central = CBCentralManager(centralDelegate, null)
        centralManager = central

        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(listOf(serviceUUID), null)
            NSLog("BleManager: Scan started immediately")
        } else {
            scanWhenReady = true
            NSLog("BleManager: Scan deferred until powered on")
        }
    }

    actual fun stopScanning() {
        centralManager?.stopScan()
        NSLog("BleManager: Scan stopped")
    }

    actual fun advertise() {
        if (peripheralManager == null) {
            peripheralManager = CBPeripheralManager(peripheralManagerDelegate, null)
        }

        if (peripheralManager?.state == CBManagerStatePoweredOn) {
            startAdvertisingInternal()
        } else {
            advertiseWhenReady = true
            NSLog("BleManager: Advertise deferred until powered on")
        }
    }

    private fun startAdvertisingInternal() {
        val advertisementData = mapOf<Any?, Any?>(
            CBAdvertisementDataServiceUUIDsKey to listOf(serviceUUID),
            CBAdvertisementDataLocalNameKey to "UWB Device"
        )
        peripheralManager?.startAdvertising(advertisementData)
    }

    actual fun stopAdvertising() {
        peripheralManager?.stopAdvertising()
        NSLog("BleManager: Advertising stopped")
    }

    // ---- Public API: Callbacks ----

    actual fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit) {
        deviceDiscoveredCallback = callback
    }

    actual fun setConfigExchangedCallback(callback: (peerId: String, remoteConfig: UwbSessionConfig) -> Unit) {
        configExchangedCallback = callback
    }

    // ---- Public API: GATT Server ----

    actual fun startGattServer(localConfig: UwbSessionConfig) {
        this.localConfig = localConfig

        if (peripheralManager == null) {
            peripheralManager = CBPeripheralManager(peripheralManagerDelegate, null)
        }

        // Build the GATT service
        val readChar = CBMutableCharacteristic(
            type = configReadUUID,
            properties = CBCharacteristicPropertyRead,
            value = null, // Dynamic value — served via delegate
            permissions = CBAttributePermissionsReadable
        )
        readCharacteristic = readChar

        val writeChar = CBMutableCharacteristic(
            type = configWriteUUID,
            properties = CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable
        )

        val service = CBMutableService(serviceUUID, primary = true)
        service.setCharacteristics(listOf(readChar, writeChar))

        peripheralManager?.addService(service)
        NSLog("BleManager: GATT server started")
    }

    actual fun stopGattServer() {
        peripheralManager?.removeAllServices()
        localConfig = null
        readCharacteristic = null
        NSLog("BleManager: GATT server stopped")
    }

    // ---- Public API: GATT Client (config exchange) ----

    actual fun connectAndExchangeConfig(peerId: String, localConfig: UwbSessionConfig) {
        val peripheral = discoveredPeripherals[peerId]
        if (peripheral == null) {
            NSLog("BleManager: No cached peripheral for $peerId")
            return
        }

        pendingLocalConfig = localConfig
        pendingPeerId = peerId

        centralManager?.connectPeripheral(peripheral, null)
        NSLog("BleManager: Connecting to $peerId for config exchange")
    }

    actual fun cleanup() {
        stopScanning()
        stopAdvertising()
        stopGattServer()
        discoveredPeripherals.clear()
        pendingLocalConfig = null
        pendingPeerId = null
        deviceDiscoveredCallback = null
        configExchangedCallback = null
        centralManager = null
        peripheralManager = null
        NSLog("BleManager: Cleanup completed")
    }
}
