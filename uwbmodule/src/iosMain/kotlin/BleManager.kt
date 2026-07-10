package com.dustedrob.uwb

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
actual class BleManager(
    private val config: BleDiscoveryConfig = BleDiscoveryConfig(),
) {

    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null

    private var deviceDiscoveredCallback: ((String, String) -> Unit)? = null
    private var configExchangedCallback: ((String, UwbSessionConfig) -> Unit)? = null

    // GATT server state
    private var localConfig: UwbSessionConfig? = null
    private var readCharacteristic: CBMutableCharacteristic? = null

    // Track discovered peripherals for GATT client connections
    private data class AccessoryDevice(
        val bleDevice: CBPeripheral? = null,
        val profile: UwbProfile? = null,
    )
    private val discoveredPeripherals = mutableMapOf<String, AccessoryDevice>()

    // Pending config exchanges, keyed by peripheral UUID
    private val pendingConfigs = mutableMapOf<String, UwbSessionConfig>()

    /** Live accessory connections kept open during ranging, for [sendToPeer] and the stop handshake. */
    private data class AccessoryConnection(
        val peripheral: CBPeripheral,
        val writeChar: CBCharacteristic,
    )
    private val accessoryConnections = mutableMapOf<String, AccessoryConnection>()

    // Deferred operations waiting for poweredOn
    private var scanWhenReady = false
    private var advertiseWhenReady = false
    private var gattServerWhenReady = false

    /** Profiles we host on the local GATT server — only phone-to-phone (read/write) ones. */
    private fun serverProfiles(): List<UwbProfile> =
        config.profiles.filter { it.exchange == ExchangeProtocol.ReadWrite && it.readFromUuid != null }

    /** Deliver a peer's serialized config to the app (single entry point, no duplicate dispatch). */
    private fun deliverRemoteConfig(peerId: String, bytes: ByteArray?) {
        val remoteConfig = bytes?.let { UwbSessionConfig.fromByteArray(it) }
        if (remoteConfig != null) {
            NSLog("BleManager: received config from $peerId")
            configExchangedCallback?.invoke(peerId, remoteConfig)
        } else {
            NSLog("BleManager: failed to parse config from $peerId")
        }
    }

    /** Deliver an accessory's raw configuration blob (opaque — wrapped, not parsed as our envelope). */
    private fun deliverAccessoryConfig(peerId: String, raw: ByteArray) {
        NSLog("BleManager: received accessory config from $peerId (${raw.size} bytes)")
        configExchangedCallback?.invoke(peerId, UwbSessionConfig(0, 0, 0, ByteArray(0), accessoryData = raw))
    }

    /** Find a characteristic on a discovered service by UUID string (CBUUID normalizes short/long). */
    private fun characteristicOf(service: CBService, uuidString: String?): CBCharacteristic? {
        if (uuidString == null) return null
        val target = CBUUID.UUIDWithString(uuidString)
        return service.characteristics?.firstOrNull { (it as? CBCharacteristic)?.UUID == target } as? CBCharacteristic
    }

    private fun addGattService(entry: UwbProfile) {
        // Read-only; server-side accessory tx-notify is out of scope (we only host phone-to-phone).
        val readChar = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(entry.readFromUuid!!),
            properties = CBCharacteristicPropertyRead,
            value = null, // Dynamic value — served via delegate
            permissions = CBAttributePermissionsReadable
        )
        readCharacteristic = readChar

        val writeChar = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(entry.writeToUuid),
            properties = CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable
        )

        val service = CBMutableService(CBUUID.UUIDWithString(entry.discoveryServiceUuid), primary = true)
        service.setCharacteristics(listOf(readChar, writeChar))

        peripheralManager?.addService(service)
        NSLog("BleManager: GATT service added ${entry.discoveryServiceUuid}")
    }

    // ---- Central (scanner/client) delegate ----

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                NSLog("BleManager: Central powered on")
                if (scanWhenReady) {
                    scanWhenReady = false
                    central.scanForPeripheralsWithServices(scanServiceUuids(), null)
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
            if (discoveredPeripherals[deviceId] == null) {
                val deviceName = didDiscoverPeripheral.name ?: "Unknown Device"

                var profile: UwbProfile? = null
                val serviceUuids =
                    advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<CBUUID> ?: return

                // Match advertised service UUIDs against our profiles. CBUUID equality normalizes
                // 16-bit (e.g. FFF0) vs full 128-bit base UUIDs, so no string slicing is needed.
                config.profiles.forEach { p ->
                    val target = CBUUID.UUIDWithString(p.advertisedUuid)
                    if (serviceUuids.any { it == target }) {
                        profile = p
                    }
                }

                // Cache peripheral (must keep strong reference for connection)
                discoveredPeripherals[deviceId] = AccessoryDevice(didDiscoverPeripheral, profile)

                NSLog("BleManager: Discovered $deviceName ($deviceId) profile=${profile?.name}")
                deviceDiscoveredCallback?.invoke(deviceId, deviceName)
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            val deviceId = didConnectPeripheral.identifier.UUIDString
            NSLog("BleManager: Connected to $deviceId, discovering services")
            didConnectPeripheral.delegate = peripheralClientDelegate
            didConnectPeripheral.discoverServices(null)
            didConnectPeripheral.maximumWriteValueLengthForType(64)
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
            val discoveredDevice = discoveredPeripherals[peripheral.identifier.UUIDString]
            val targetServiceUuid = discoveredDevice?.profile?.discoveryServiceUuid?.let { CBUUID.UUIDWithString(it) }
            val service = if (targetServiceUuid == null) null else peripheral.services?.firstOrNull {
                (it as? CBService)?.UUID == targetServiceUuid
            } as? CBService

            if (service == null) {
                NSLog("BleManager: UWB service not found on ${peripheral.identifier.UUIDString}")
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }
            NSLog("BleManager: discovering characteristics")
            peripheral.discoverCharacteristics(null, service)
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
            val device = discoveredPeripherals[peripheral.identifier.UUIDString]
            val profile = device?.profile
            val service = didDiscoverCharacteristicsForService

            when (profile?.exchange) {
                ExchangeProtocol.AccessoryNotify -> {
                    val notifyChar = characteristicOf(service, profile.notifyFromUuid)
                    val writeChar = characteristicOf(service, profile.writeToUuid)
                    if (notifyChar != null && writeChar != null) {
                        // Subscribe to the accessory's tx, then write the init command so it notifies
                        // its config back (handled in didUpdateValueForCharacteristic).
                        peripheral.setNotifyValue(true, notifyChar)
                        val initCmd = profile.initCommand ?: byteArrayOf(NI_ACCESSORY_INIT_COMMAND)
                        peripheral.writeValue(initCmd.toNSData(), writeChar, CBCharacteristicWriteWithoutResponse)
                        // Keep the connection open for the rest of the accessory protocol
                        // (configure-and-start, stop) — see sendToPeer.
                        accessoryConnections[peripheral.identifier.UUIDString] =
                            AccessoryConnection(peripheral, writeChar)
                        NSLog("BleManager: sent accessory init to ${peripheral.identifier.UUIDString}")
                    } else {
                        NSLog("BleManager: accessory characteristics not found")
                        centralManager?.cancelPeripheralConnection(peripheral)
                    }
                }

                else -> {
                    // ReadWrite (phone-to-phone): read the peer's config.
                    val readChar = characteristicOf(service, profile?.readFromUuid)
                    if (readChar != null) {
                        peripheral.readValueForCharacteristic(readChar)
                    } else {
                        NSLog("BleManager: Read characteristic not found")
                        centralManager?.cancelPeripheralConnection(peripheral)
                    }
                }
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) {
                NSLog("BleManager: Characteristic update error: ${error.localizedDescription} ${didUpdateValueForCharacteristic.UUID}")
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }
            val peerId = peripheral.identifier.UUIDString
            val profile = discoveredPeripherals[peerId]?.profile
            val charUuid = didUpdateValueForCharacteristic.UUID

            val readUuid = profile?.readFromUuid?.let { CBUUID.UUIDWithString(it) }
            val notifyUuid = profile?.notifyFromUuid?.let { CBUUID.UUIDWithString(it) }

            when {
                // ReadWrite: the read returned the peer's config — deliver, write ours back, disconnect.
                readUuid != null && charUuid == readUuid -> {
                    deliverRemoteConfig(peerId, didUpdateValueForCharacteristic.value?.toByteArray())

                    val localCfg = pendingConfigs[peerId]
                    val service = peripheral.services?.firstOrNull {
                        (it as? CBService)?.UUID == profile.discoveryServiceUuid.let { u -> CBUUID.UUIDWithString(u) }
                    } as? CBService
                    val writeChar = service?.let { characteristicOf(it, profile.writeToUuid) }
                    if (localCfg != null && writeChar != null) {
                        peripheral.writeValue(localCfg.toByteArray().toNSData(), writeChar, CBCharacteristicWriteWithoutResponse)
                        NSLog("BleManager: Wrote config to $peerId")
                    }
                    centralManager?.cancelPeripheralConnection(peripheral)
                    pendingConfigs.remove(peerId)
                }

                // AccessoryNotify: the accessory notified — first byte is the NI message id.
                notifyUuid != null && charUuid == notifyUuid -> {
                    val bytes = didUpdateValueForCharacteristic.value?.toByteArray()
                    if (bytes == null || bytes.isEmpty()) return
                    when (bytes[0]) {
                        NI_ACCESSORY_CONFIG_DATA -> {
                            // Opaque accessory config — deliver raw; the NI layer builds the session
                            // and replies (via sendToPeer) with configure-and-start. Stay connected.
                            NSLog("BleManager: accessory sent config")
                            deliverAccessoryConfig(peerId, bytes.copyOfRange(1, bytes.size))
                        }
                        NI_ACCESSORY_DID_START -> NSLog("BleManager: accessory did start")
                        NI_ACCESSORY_DID_STOP -> {
                            NSLog("BleManager: accessory did stop")
                            accessoryConnections.remove(peerId)
                            centralManager?.cancelPeripheralConnection(peripheral)
                            pendingConfigs.remove(peerId)
                        }
                        else -> NSLog("BleManager: unexpected accessory response ${bytes[0]}")
                    }
                }
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
                // GATT services can only be added once powered on, else CoreBluetooth rejects them
                // with "API MISUSE … powered on state" and the peer reads invalid handles.
                if (gattServerWhenReady) {
                    gattServerWhenReady = false
                    addAllGattServices()
                }
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
                NSLog("BleManager: GATT service added ${didAddService.UUID}")
                // addService is async — chain the next hosted profile only after this one is added.
                val hosted = serverProfiles()
                val index = hosted.indexOfFirst { didAddService.UUID == CBUUID.UUIDWithString(it.discoveryServiceUuid) }
                if (index in 0 until hosted.lastIndex) {
                    addGattService(hosted[index + 1])
                }
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveReadRequest: CBATTRequest
        ) {
            // The connecting central isn't in discoveredPeripherals (that map is filled by scanning),
            // so match the characteristic against any hosted profile's read char.
            val isReadChar = serverProfiles().any {
                didReceiveReadRequest.characteristic.UUID == CBUUID.UUIDWithString(it.readFromUuid!!)
            }
            if (isReadChar) {
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
                if (request is CBATTRequest) {
                    val isWriteChar = serverProfiles().any {
                        request.characteristic.UUID == CBUUID.UUIDWithString(it.writeToUuid)
                    }
                    if (isWriteChar) {
                        val peerId = request.central.identifier.UUIDString
                        deliverRemoteConfig(peerId, request.value?.toByteArray())
                        peripheral.respondToRequest(request, CBATTErrorSuccess)
                    }
                }
            }
        }
    }

    // ---- Public API: Scanning ----

    private fun scanServiceUuids(): List<CBUUID> =
        config.profiles.map { CBUUID.UUIDWithString(it.advertisedUuid) }

    actual fun startScanning() {
        val central = centralManager ?: CBCentralManager(centralDelegate, null).also {
            centralManager = it
        }

        if (central.state == CBManagerStatePoweredOn) {
            val options = mapOf<Any?, Any?>(
                CBCentralManagerScanOptionAllowDuplicatesKey to NSNumber(false)
            )
            central.scanForPeripheralsWithServices(scanServiceUuids(), options)
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
        val advertisedUuid = config.profiles.find { it.name == config.advertiseProfile }?.advertisedUuid ?: ""
        val advertisementData = mapOf<Any?, Any?>(
            CBAdvertisementDataServiceUUIDsKey to listOf(CBUUID.UUIDWithString(advertisedUuid)),
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

        if (peripheralManager?.state == CBManagerStatePoweredOn) {
            addAllGattServices()
        } else {
            gattServerWhenReady = true
            NSLog("BleManager: GATT server deferred until powered on")
        }
    }

    /** Adds the first hosted profile's service; the rest are chained via the didAddService delegate. */
    private fun addAllGattServices() {
        serverProfiles().firstOrNull()?.let { addGattService(it) }
    }

    actual fun stopGattServer() {
        peripheralManager?.removeAllServices()
        localConfig = null
        readCharacteristic = null
        NSLog("BleManager: GATT server stopped")
    }

    // ---- Public API: GATT Client (config exchange) ----

    actual fun connectAndExchangeConfig(peerId: String, localConfig: UwbSessionConfig) {
        NSLog("BleManager: in connect and config")
        val peripheral = discoveredPeripherals[peerId]?.bleDevice
        if (peripheral == null) {
            NSLog("BleManager: No cached peripheral for $peerId")
            return
        }

        pendingConfigs[peerId] = localConfig
        centralManager?.connectPeripheral(peripheral, null)
        NSLog("BleManager: Connecting to $peerId for config exchange")
    }

    actual fun sendToPeer(peerId: String, data: ByteArray) {
        val conn = accessoryConnections[peerId]
        if (conn == null) {
            NSLog("BleManager: sendToPeer no open accessory connection for $peerId")
            return
        }
        conn.peripheral.writeValue(data.toNSData(), conn.writeChar, CBCharacteristicWriteWithoutResponse)
        NSLog("BleManager: sendToPeer wrote ${data.size} bytes to $peerId")
    }

    actual fun cleanup() {
        stopScanning()
        stopAdvertising()
        stopGattServer()
        accessoryConnections.values.forEach { centralManager?.cancelPeripheralConnection(it.peripheral) }
        accessoryConnections.clear()
        discoveredPeripherals.clear()
        pendingConfigs.clear()
        deviceDiscoveredCallback = null
        configExchangedCallback = null
        centralManager = null
        peripheralManager = null
        NSLog("BleManager: Cleanup completed")
    }
}
