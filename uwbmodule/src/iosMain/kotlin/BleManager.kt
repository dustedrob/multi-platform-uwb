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
    private data class  accessoryDevice (
        val bleDevice: CBPeripheral? = null,
        val service: ServiceEntry?=null
    )
    private val discoveredPeripherals = mutableMapOf<String, accessoryDevice>()

    // Pending config exchanges, keyed by peripheral UUID
    private val pendingConfigs = mutableMapOf<String, UwbSessionConfig>()

    // Deferred operations waiting for poweredOn
    private var scanWhenReady = false
    private var advertiseWhenReady = false
    private var gattServerWhenReady = false

    fun addGattService(entry: ServiceEntry){
        NSLog("BleManager: addGATTService on entry $entry")
        //val entry = config.profiles[index]

        // Read-only for now; accessory-protocol tx-notify is future work (would add CBCharacteristicPropertyNotify).
        val readChar = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(entry.readFromUUID),
            properties = CBCharacteristicPropertyRead,
            value = null, // Dynamic value — served via delegate
            permissions = CBAttributePermissionsReadable
        )
        readCharacteristic = readChar

        val writeChar = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(entry.writeToUUID),
            properties = CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable
        )

        val service = CBMutableService(CBUUID.UUIDWithString(entry.discoveryServiceUUID), primary = true)
        service.setCharacteristics(listOf(readChar, writeChar))

        peripheralManager?.addService(service)
        //NSLog("BleManager: GATT service added index=$index")
    }

    // ---- Central (scanner/client) delegate ----

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                NSLog("BleManager: Central powered on")
                if (scanWhenReady) {
                    scanWhenReady = false
                    var serviceList: MutableList<CBUUID> = mutableListOf()
                    config.profiles.forEach { set ->
                        set.advertisedUUID?.let { serviceList.add(CBUUID.UUIDWithString(it)) }
                    }
                    NSLog("services to scan for are {$serviceList} ")
                    central.scanForPeripheralsWithServices(serviceList, null)
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
            val deviceId = didDiscoverPeripheral.hash.toString()
            if( discoveredPeripherals[deviceId] == null) {
                val deviceName = didDiscoverPeripheral.name ?: "Unknown Device"

                var service: ServiceEntry? = null
                // Extract advertised services from the advertisement data map
                val serviceUuids =
                    advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<CBUUID> ?: return
                serviceUuids.forEach { id ->
                    NSLog("discovered device service UUID= ${id.UUIDString}")
                }

                // Match advertised service UUIDs against our profiles. CBUUID equality normalizes
                // 16-bit (e.g. FFF0) vs full 128-bit base UUIDs, so no string slicing is needed.
                config.profiles.forEach { set ->
                    val target = set.advertisedUUID?.let { CBUUID.UUIDWithString(it) }
                    if (serviceUuids.any { it == target }) {
                        service = set
                    }
                }

                // Cache peripheral (must keep strong reference for connection)
                // need to relate the service (and characteristics) found in response to ble scan services
                discoveredPeripherals[deviceId] = accessoryDevice(didDiscoverPeripheral, service)

                NSLog("BleManager: Discovered $deviceName ($deviceId)")
                deviceDiscoveredCallback?.invoke(deviceId, deviceName)
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            val deviceId = didConnectPeripheral.hash.toString()
            val device = discoveredPeripherals[deviceId]
            NSLog("BleManager: Connected to $deviceId, discovering services")
            didConnectPeripheral.delegate = peripheralClientDelegate
            val services: MutableList<CBUUID> = mutableListOf()
            NSLog("BleManager: discovering services from")
            NSLog( discoveredPeripherals[deviceId]?.service?.advertisedUUID?:"")
            services.add(CBUUID.UUIDWithString((discoveredPeripherals[deviceId]?.service?.advertisedUUID?:"").uppercase()))

            didConnectPeripheral.discoverServices(null) //services)
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
            val discoveredDevice = discoveredPeripherals[peripheral.hash.toString()]
            NSLog("BleManager: device service =${peripheral.services} and service on scan discover was ${discoveredDevice?.service?.advertisedUUID}")
            val targetServiceUuid = discoveredDevice?.service?.advertisedUUID?.let { CBUUID.UUIDWithString(it) }
            NSLog("discover device services=${peripheral.services} targetuuid=${targetServiceUuid}")
            var service:CBService? =null ;
            if (targetServiceUuid == null) null else peripheral.services?.forEach {  it ->
                NSLog(" testing service  = $it against ${discoveredDevice.service.discoveryServiceUUID}")
                if ((it as? CBService)?.UUID.toString() == discoveredDevice.service.discoveryServiceUUID) {
                    service = (it as? CBService)
                }
            }

            if (service == null) {
                NSLog("BleManager: UWB service not found on ${peripheral.hash.toString()}")
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }
            NSLog(("discovering characteristics"))
            peripheral.discoverCharacteristics( null, //listOf(
                   // discoveredDevice?.service?.readFromUUID?.let {theString -> CBUUID.UUIDWithString(theString)},
                   // discoveredDevice?.service?.writeToUUID?.let { theString -> CBUUID.UUIDWithString(theString)}
            //),
             service)
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
            val discovererDevice = discoveredPeripherals[peripheral.hash.toString()]
            // Step 1: Read the peer's config
            var readChar:  CBCharacteristic? = null

            didDiscoverCharacteristicsForService.characteristics?.forEach { c ->
                NSLog("discovered char="+c.toString())
                (c as? CBCharacteristic)?.let { char ->
                    if (char.UUID == discovererDevice?.service?.readFromUUID?.let {
                            CBUUID.UUIDWithString(
                                it.uppercase()
                            )
                        }
                        ){
                        if(readChar==null)
                            readChar = char as CBCharacteristic?
                    }

                    NSLog("characteristic found = ${c.UUID.UUIDString}")
                }
            }

            if (readChar != null) {
                peripheral.readValueForCharacteristic(readChar)
            } else {
                NSLog("BleManager: Read characteristic not found")
                centralManager?.cancelPeripheralConnection(peripheral)
            }
        }

        // in response to notify
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
            val peerId = peripheral.hash.toString()
            val discovererDevice = discoveredPeripherals[peerId]
            NSLog("update characteristic = ${didUpdateValueForCharacteristic.UUID}")
            if (didUpdateValueForCharacteristic.UUID == discovererDevice?.service?.readFromUUID?.let { CBUUID.UUIDWithString(it) }){

                val data = didUpdateValueForCharacteristic.value
                if (data != null) {
                    val remoteConfig = UwbSessionConfig.fromByteArray(data.toByteArray())

                    if (remoteConfig != null) {
                        NSLog("BleManager: Received config from $peerId")
                        configExchangedCallback?.invoke(peerId, remoteConfig)
                    } else {
                        NSLog("BleManager: Failed to parse config from $peerId")
                    }
                }

                // Step 2: Write our config to the peer (using WriteWithoutResponse to avoid needing didWriteValue callback)
                val localCfg = pendingConfigs[peerId]
                if (localCfg != null) {
                    val service = peripheral.services?.firstOrNull {
                        (it as? CBService)?.UUID == discovererDevice.service.discoveryServiceUUID?.let { CBUUID.UUIDWithString(it) }
                    } as? CBService

                    val writeChar = service?.characteristics?.firstOrNull {
                        (it as? CBCharacteristic)?.UUID == discovererDevice.service.writeToUUID?.let { CBUUID.UUIDWithString(it) }
                    } as? CBCharacteristic

                    if (writeChar != null) {
                        val configData = localCfg.toByteArray().toNSData()
                        peripheral.writeValue(configData, writeChar, CBCharacteristicWriteWithoutResponse)
                        NSLog("BleManager: Wrote config to ${peripheral.hash.toString()}")
                    }
                }
                // Exchange complete, disconnect
                centralManager?.cancelPeripheralConnection(peripheral)
                pendingConfigs.remove(peerId)
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
                var device = discoveredPeripherals[peripheral.hash.toString()]
                // addService is async — chain the next profile only after this one is added.
                config.profiles.forEachIndexed { index, service ->
                    if(service.advertisedUUID == device?.service?.advertisedUUID) {
                        NSLog("BleManager: GATT service added previously added=${didAddService.UUID} checking=${service.discoveryServiceUUID}")
                        if (didAddService.UUID == CBUUID.UUIDWithString(service.discoveryServiceUUID)) {
                            if (index < config.profiles.size - 1)
                                NSLog("BleManager: GATT service added index=$index last=${config.profiles.size}")
                            addGattService(config.profiles[index + 1])
                        }
                    }
                }
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveReadRequest: CBATTRequest
        ) {
            // The connecting central isn't in discoveredPeripherals (that map is filled by scanning),
            // so match the characteristic against any hosted profile's read char.
            val isReadChar = config.profiles.any {
                didReceiveReadRequest.characteristic.UUID == CBUUID.UUIDWithString(it.readFromUUID)
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
                  val isWriteChar = config.profiles.any {
                      request.characteristic.UUID == CBUUID.UUIDWithString(it.writeToUUID)
                  }
                  if( isWriteChar ) {
                    val data = request.value
                    if (data != null) {
                        val remoteConfig = UwbSessionConfig.fromByteArray(data.toByteArray())
                        if (remoteConfig != null) {
                            val peerId = request.central.hash.toString()
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

    }

    // ---- Public API: Scanning ----


    actual fun startScanning() {
        val central = centralManager ?: CBCentralManager(centralDelegate, null).also {
            centralManager = it
        }

        if (central.state == CBManagerStatePoweredOn) {
            val services: MutableList<CBUUID> = mutableListOf()
            config.profiles.forEach { set ->
                set.advertisedUUID?.let { services.add(CBUUID.UUIDWithString(it)) }
            }
            val options = mapOf<Any?, Any?>(
                CBCentralManagerScanOptionAllowDuplicatesKey to NSNumber(false)
            )
            central.scanForPeripheralsWithServices(services, options)
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
        val services: MutableList<CBUUID> = mutableListOf()
        val uidstring = config.profiles.find { it.name == config.advertiseProfile }?.discoveryServiceUUID ?: ""
        services.add(CBUUID.UUIDWithString(uidstring))
        val advertisementData = mapOf<Any?, Any?>(
            CBAdvertisementDataServiceUUIDsKey to services,
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

    /** Adds the first profile's service; the rest are chained via the didAddService delegate. */
    private fun addAllGattServices() {
        if (config.profiles.isNotEmpty()) {
            config.profiles.forEach { set ->
                if(set.advertisedUUID == null)
                    set.advertisedUUID = set.discoveryServiceUUID
            }
            addGattService(config.profiles[0])
        }
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
        NSLog("BleManager: starting connect")
        centralManager?.connectPeripheral(peripheral, null)
        NSLog("BleManager: Connecting to $peerId for config exchange")
    }

    actual fun cleanup() {
        stopScanning()
        stopAdvertising()
        stopGattServer()
        discoveredPeripherals.clear()
        pendingConfigs.clear()
        deviceDiscoveredCallback = null
        configExchangedCallback = null
        centralManager = null
        peripheralManager = null
        NSLog("BleManager: Cleanup completed")
    }
}

