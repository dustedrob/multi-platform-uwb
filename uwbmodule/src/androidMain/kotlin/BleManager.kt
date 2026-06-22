package com.dustedrob.uwb

import android.Manifest
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

actual class BleManager(
    private val context: Context,
    private val config: BleDiscoveryConfig = BleDiscoveryConfig(),
) {
    private val TAG = "BleManager"

    private data class  accessoryDevice (
        val bleDevice: BluetoothDevice? = null,
        val service: ServiceEntry?=null
    )

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var deviceDiscoveredCallback: ((String, String) -> Unit)? = null
    private var configExchangedCallback: ((String, UwbSessionConfig) -> Unit)? = null

    // GATT server state
    private var gattServer: BluetoothGattServer? = null
    private var localConfig: UwbSessionConfig? = null

    // Track discovered peripherals for GATT client connections
    private val discoveredDevices = mutableMapOf<String, accessoryDevice>()

    // ---- Scan callback ----

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceAddress = device.address
            var service: ServiceEntry? = null
            val deviceName = if (hasConnectPermission()) {
                device.name ?: "Unknown Device"
            } else {
                "Unknown Device"
            }
            if(discoveredDevices[deviceAddress] == null) {
                result.scanRecord?.serviceUuids?.forEach { uuid ->
                    config.profiles.forEach { serviceEntry ->
                        if (serviceEntry.discoveryServiceUUID.uppercase() == uuid.toString()
                                .uppercase()
                        )
                            if (service == null) {
                                service = serviceEntry
                            }
                    }
                }
                // Cache the BluetoothDevice for later GATT connection
                discoveredDevices[deviceAddress] = accessoryDevice(device, service)

                Log.d(TAG, "Found device: $deviceName ($deviceAddress)")
                deviceDiscoveredCallback?.invoke(deviceAddress, deviceName)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    // ---- Advertise callback ----

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertising failed with error code: $errorCode")
        }
    }

    // ---- GATT Server callback ----

    private val gattServerCallback = object : BluetoothGattServerCallback() {

       /* @RequiresPermission(BLUETOOTH_CONNECT)
        fun onServiceAdded(status: Int, characteristic: BluetoothGattCharacteristic){
            Log.d(TAG, "GATT server: service added")
            addGattService(status+1)
        }*/

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT server: device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT server: device disconnected: ${device.address}")
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.d(TAG,"Service Added ${service?.uuid.toString()}, status=${status}")
            // addService() is async — chain the next profile's service only after this one is added.
            config.profiles.forEachIndexed { index, entry ->
                if( service?.uuid.toString().equals(entry.discoveryServiceUUID, ignoreCase = true) ||
                    service?.uuid.toString().equals(entry.discoveryServiceUUID.slice(ItnRage(4,7)), ignoreCase = true)
                    ){
                    // if we aren't on the last
                    if(index < config.profiles.lastIndex)
                        // add the next one
                        addGattService(index+1 )
                }
            }

        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            // The connecting central isn't in discoveredDevices (that map is filled by scanning, not
            // the server role), so match the characteristic against any hosted profile's read char.
            val isReadChar = config.profiles.any {
                it.readFromUUID.equals(characteristic.uuid.toString(), ignoreCase = true) ||
                it.readFromUUID.slice(IntRange(4,7)).equals(characteristic.uuid.toString(), ignoreCase = true)
            }
            if (isReadChar) {
                val configBytes = localConfig?.toByteArray() ?: ByteArray(0)
                val responseBytes = if (offset < configBytes.size) {
                    configBytes.copyOfRange(offset, configBytes.size)
                } else {
                    ByteArray(0)
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseBytes)
                Log.d(TAG, "GATT server: sent config to ${device.address} (${configBytes.size} bytes)")
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val isWriteChar = config.profiles.any {
                it.writeToUUID.equals(characteristic.uuid.toString(), ignoreCase = true)
                it.writeToUUID.slice(IntRange(4,7)).equals(characteristic.uuid.toString(), ignoreCase = true)
            }
            if (isWriteChar && value != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

                val remoteConfig = UwbSessionConfig.fromByteArray(value)
                if (remoteConfig != null) {
                    Log.d(TAG, "GATT server: received config from ${device.address}")
                    configExchangedCallback?.invoke(device.address, remoteConfig)
                } else {
                    Log.e(TAG, "GATT server: failed to parse config from ${device.address}")
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
    }

    // ---- Permission helpers ----

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // ---- Public API: Scanning ----

    @RequiresPermission(BLUETOOTH_SCAN)
    actual fun startScanning() {
        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled or not supported")
            return
        }
        if (!hasScanPermission()) {
            Log.e(TAG, "Missing scan permission")
            return
        }

        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
                Log.e(TAG, "BLE Scanner not available")
                return
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            var filters: MutableList<ScanFilter> = mutableListOf()
            config.profiles.forEach { serviceEntry ->
                val scanFilter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(UUID.fromString(serviceEntry.discoveryServiceUUID.uppercase())))
                    .build()
                filters.add(scanFilter)
            }
            scanner.startScan(filters, scanSettings, scanCallback)
            Log.d(TAG, "BLE scanning started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan: ${e.message}")
        }
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    actual fun stopScanning() {
        if (!hasScanPermission()) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "BLE scanning stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan: ${e.message}")
        }
    }

    // ---- Public API: Advertising ----

    @RequiresPermission(BLUETOOTH_CONNECT)
    actual fun advertise() {
        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled or not supported")
            return
        }
        if (!hasAdvertisePermission()) {
            Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission")
            return
        }

        try {
            val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
                Log.e(TAG, "BLE Advertiser not available")
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            // The primary advertisement carries only the service UUID. The device
            // name goes in the scan response: a 128-bit service UUID already uses
            // 18 of the 31-byte legacy advertisement budget, so including the name
            // here too overflows it and fails with ADVERTISE_FAILED_DATA_TOO_LARGE
            // (error code 1). This matters because UWB-interop UUIDs (Nordic UART,
            // Qorvo NI) are full 128-bit values, unlike the base-UUID-derived FFF0
            // which Android compresses to 2 bytes.
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid((UUID.fromString(config.profiles.find { it.name == config.advertiseProfile }?.discoveryServiceUUID?.uppercase()))))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            Log.d(TAG, "BLE advertising started")
        }
        catch (e:Exception){
            Log.e(TAG, "Error starting BLE advertising: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    actual fun stopAdvertising() {
        if (!hasAdvertisePermission()) return
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            Log.d(TAG, "BLE advertising stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertising: ${e.message}")
        }
    }

    // ---- Public API: Callbacks ----

    actual fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit) {
        deviceDiscoveredCallback = callback
    }

    actual fun setConfigExchangedCallback(callback: (peerId: String, remoteConfig: UwbSessionConfig) -> Unit) {
        configExchangedCallback = callback
    }

    // ---- Public API: GATT Server ----

    @RequiresPermission(BLUETOOTH_CONNECT)
    actual fun startGattServer(localConfig: UwbSessionConfig) {
        this.localConfig = localConfig
        if (!hasConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission for GATT server")
            return
        }

        try {
            val server= bluetoothManager.openGattServer(context, gattServerCallback)
            gattServer = server
            addGattService(0)
            Log.d(TAG, "GATT server started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GATT server: ${e.message}")
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    actual fun stopGattServer() {
        try {
            gattServer?.close()
            gattServer = null
            localConfig = null
            Log.d(TAG, "GATT server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GATT server: ${e.message}")
        }
    }

    // ---- Public API: GATT Client (config exchange) ----
    @RequiresPermission(BLUETOOTH_CONNECT)
    fun addGattService(index:Int){

            // Build the UWB config service. All profiles are PRIMARY so each is independently
            // discoverable by a connecting client.
            val service = BluetoothGattService(
                UUID.fromString(config.profiles[index].discoveryServiceUUID.uppercase()),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Readable characteristic: our local config. (Read-only for now; the accessory-protocol
            // tx-notify flow would also need a CCCD descriptor + notifyCharacteristicChanged — future work.)
            val readChar = BluetoothGattCharacteristic(
                UUID.fromString(config.profiles[index].readFromUUID.uppercase()),
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(readChar)

            // Writable characteristic: peer writes their config here
            val writeChar = BluetoothGattCharacteristic(
                UUID.fromString(config.profiles[index].writeToUUID.uppercase()),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(writeChar)
            Log.d(TAG,"adding service in serviceAdded callback")
        gattServer?.addService(service)

    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    actual fun connectAndExchangeConfig(peerId: String, localConfig: UwbSessionConfig) {
        val device = discoveredDevices[peerId]?.bleDevice
        if (device == null) {
            Log.e(TAG, "No BluetoothDevice cached for $peerId")
            return
        }
        if (!hasConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission for GATT client")
            return
        }
        val serviceEntry: ServiceEntry? = discoveredDevices[peerId]?.service ?: null

        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                @RequiresPermission(BLUETOOTH_CONNECT)
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT client: connected to $peerId, discovering services")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "GATT client: disconnected from $peerId")
                        gatt.close()
                    }
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "GATT client: service discovery failed for $peerId")
                        gatt.close()
                        return
                    }
                    gatt.services.forEach { service ->
                        Log.d(TAG, "service name found = ${service.uuid.toString()}")
                    }

                    val service = gatt.getService(UUID.fromString(serviceEntry?.discoveryServiceUUID?.uppercase()))
                    if (service == null) {
                        Log.e(TAG, "GATT client: UWB config service not found on $peerId")
                        gatt.close()
                        return
                    }

                    // Step 1: Read peer's config
                    val readChar = service.getCharacteristic(UUID.fromString(serviceEntry?.readFromUUID?.uppercase()))
                    if (readChar != null) {
                        gatt.readCharacteristic(readChar)
                    } else {
                        Log.e(TAG, "GATT client: read characteristic not found on $peerId")
                        gatt.close()
                    }
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "GATT client: read failed for $peerId")
                        gatt.close()
                        return
                    }
                    val service = gatt.getService(UUID.fromString(serviceEntry?.discoveryServiceUUID?.uppercase()))
                    if (characteristic.uuid == UUID.fromString(serviceEntry?.readFromUUID?.uppercase())) {
                        val remoteConfigBytes = characteristic.value
                        val remoteConfig = remoteConfigBytes?.let { UwbSessionConfig.fromByteArray(it) }

                        if (remoteConfig != null) {
                            Log.d(TAG, "GATT client: received config from $peerId")
                            configExchangedCallback?.invoke(peerId, remoteConfig)
                        } else {
                            Log.e(TAG, "GATT client: failed to parse config from $peerId")
                        }

                        // Step 2: Write our config to peer
                        val writeChar = service?.getCharacteristic(UUID.fromString(serviceEntry?.writeToUUID?.uppercase()))
                        if (writeChar != null) {
                            writeChar.value = localConfig.toByteArray()
                            gatt.writeCharacteristic(writeChar)
                        } else {
                            gatt.close()
                        }
                    }
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "GATT client: wrote config to $peerId")
                    } else {
                        Log.e(TAG, "GATT client: write failed for $peerId, status=$status")
                    }
                    // Exchange complete, disconnect
                    gatt.disconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting GATT to $peerId: ${e.message}")
        }
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    actual fun cleanup() {
        stopScanning()
        stopAdvertising()
        stopGattServer()
        discoveredDevices.clear()
        deviceDiscoveredCallback = null
        configExchangedCallback = null
        Log.d(TAG, "BLE cleanup completed")
    }
}
