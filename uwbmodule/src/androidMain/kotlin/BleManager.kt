package com.dustedrob.uwb

import android.Manifest
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import androidx.core.content.ContextCompat
import java.util.UUID

private const val PREFERRED_MTU: Int = 256;
actual class BleManager(

    private val context: Context,
    private val config: BleDiscoveryConfig = BleDiscoveryConfig(),
) {
    private val TAG = "BleManager"

    /** Client Characteristic Configuration Descriptor — used to subscribe to notifications. */
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")


    private data class AccessoryDevice(
        val bleDevice: BluetoothDevice? = null,
        val profile: UwbProfile? = null,
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
    private val discoveredDevices = mutableMapOf<String, AccessoryDevice>()

    /** A live accessory connection kept open during ranging, for [sendToPeer] and the stop handshake. */
    private class AccessoryConnection(
        val gatt: BluetoothGatt,
        val writeChar: BluetoothGattCharacteristic,
        val queue: BleQueueManager,
    )
    private val accessoryConnections = mutableMapOf<String, AccessoryConnection>()

    /** Profiles we host on the local GATT server — only phone-to-phone (read/write) ones. */
    private fun serverProfiles(): List<UwbProfile> =
        config.profiles.filter { it.exchange == ExchangeProtocol.ReadWrite && it.readFromUuid != null }

    /** Deliver a peer's serialized config to the app (single entry point, no duplicate dispatch). */
    private fun deliverRemoteConfig(peerId: String, bytes: ByteArray?) {
        val remoteConfig = bytes?.let { UwbSessionConfig.fromByteArray(it) }
        if (remoteConfig != null) {
            Log.d(TAG, "received config from $peerId")
            configExchangedCallback?.invoke(peerId, remoteConfig)
        } else {
            Log.e(TAG, "failed to parse config from $peerId")
        }
    }

    /** Deliver an accessory's raw configuration blob (opaque — wrapped, not parsed as our envelope). */
    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun deliverAccessoryConfig(peerId: String, raw: ByteArray) {
        Log.d(TAG, "received accessory config from $peerId (${raw.size} bytes)")
        val remoteConfig = raw.let { UwbSessionConfig.fromByteArray(it, true) }
        if (remoteConfig != null) {
            if (localConfig == null) { // we need our own address to send to the accessory (controller)
                Log.e(TAG, "local config not created")
                return
            }

            configExchangedCallback?.invoke(peerId, remoteConfig) // this starts ranging
        } else {
            Log.e(TAG, "failed to parse config from $peerId")
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun cleanUpGatt(gatt:BluetoothGatt ) {
        gatt.disconnect();
        gatt.close();
    }
    // ---- Scan callback ----

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceAddress = device.address
            val deviceName = if (hasConnectPermission()) device.name ?: "Unknown Device" else "Unknown Device"
            if (discoveredDevices[deviceAddress] != null) return

            var profile: UwbProfile? = null
            result.scanRecord?.serviceUuids?.forEach { uuid ->
                config.profiles.forEach { p ->
                    // Android normalizes 16-bit advertisements to the full base UUID, so a plain
                    // case-insensitive full-string compare matches both short and long forms.
                    if (profile == null && p.advertisedUuid.equals(uuid.toString(), ignoreCase = true)) {
                        profile = p
                    }
                }
            }
            discoveredDevices[deviceAddress] = AccessoryDevice(device, profile)
            Log.d(TAG, "Found device: $deviceName ($deviceAddress) profile=${profile?.name}")
            deviceDiscoveredCallback?.invoke(deviceAddress, deviceName)
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
            Log.d(TAG, "Service added ${service?.uuid}, status=$status")
            // addService() is async — chain the next profile's service only after this one is added.
            val added = service?.uuid?.toString() ?: return
            val hosted = serverProfiles()
            val index = hosted.indexOfFirst { it.discoveryServiceUuid.equals(added, ignoreCase = true) }
            if (index in 0 until hosted.lastIndex) {
                addGattService(hosted[index + 1])
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
            val isReadChar = serverProfiles().any {
                it.readFromUuid.equals(characteristic.uuid.toString(), ignoreCase = true)
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
            val isWriteChar = serverProfiles().any {
                it.writeToUuid.equals(characteristic.uuid.toString(), ignoreCase = true)
            }
            if (isWriteChar && value != null) {
                // ACK the write so the client's write completes (the write char is PROPERTY_WRITE).
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                deliverRemoteConfig(device.address, value)
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
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

            val filters: MutableList<ScanFilter> = mutableListOf()
            config.profiles.forEach { profile ->
                val scanFilter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(UUID.fromString(profile.advertisedUuid.uppercase())))
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
            // (error code 1).
            val advertisedUuid = config.profiles.find { it.name == config.advertiseProfile }?.advertisedUuid
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(UUID.fromString(advertisedUuid?.uppercase())))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            Log.d(TAG, "BLE advertising started")
        } catch (e: Exception) {
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
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            // Host only phone-to-phone profiles; accessory profiles are client-only (the accessory
            // is the server). The first service is added here; the rest chain via onServiceAdded.
            serverProfiles().firstOrNull()?.let { addGattService(it) }
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

    @RequiresPermission(BLUETOOTH_CONNECT)
    private fun addGattService(entry: UwbProfile) {
        // Build the UWB config service. All profiles are PRIMARY so each is independently
        // discoverable by a connecting client.
        val service = BluetoothGattService(
            UUID.fromString(entry.discoveryServiceUuid.uppercase()),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Readable characteristic: our local config (read-only; server-side notify is out of scope).
        val readChar = BluetoothGattCharacteristic(
            UUID.fromString(entry.readFromUuid!!.uppercase()),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(readChar)

        // Writable characteristic: peer writes their config here.
        val writeChar = BluetoothGattCharacteristic(
            UUID.fromString(entry.writeToUuid.uppercase()),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(writeChar)
        gattServer?.addService(service)
    }

    // ---- Public API: GATT Client (config exchange) ----

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
        val profile: UwbProfile? = discoveredDevices[peerId]?.profile

        // Serializes GATT writes for this connection; created on service discovery.
        var queue: BleQueueManager? = null

        try {
            device.connectGatt(context, false, object : BluetoothGattCallback()  {
                @RequiresPermission(BLUETOOTH_CONNECT)
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status == 133) {
                        // Handle Error 133 cleanly
                        cleanUpGatt(gatt);
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT client: connected to $peerId, discovering services")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "GATT client: disconnected from $peerId")
                        //gatt.close()
                    }
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "GATT client: service discovery failed for $peerId")
                        gatt.close()
                        return
                    }
                    if (profile == null) {
                        Log.e(TAG, "GATT client: no matched profile for $peerId")
                        gatt.close()
                        return
                    }
                    val service = gatt.getService(UUID.fromString(profile.discoveryServiceUuid.uppercase()))
                    if (service == null) {
                        Log.e(TAG, "GATT client: UWB config service not found on $peerId")
                        gatt.close()
                        return
                    }
                    // only instantiate once
                    if(queue == null)
                       queue = BleQueueManager(gatt)
                    gatt.requestMtu(PREFERRED_MTU);
                    when (profile.exchange) {
                        ExchangeProtocol.ReadWrite -> {
                            val readChar = profile.readFromUuid
                                ?.let { service.getCharacteristic(UUID.fromString(it.uppercase())) }
                            if (readChar != null) {
                                gatt.readCharacteristic(readChar)
                            } else {
                                Log.e(TAG, "GATT client: read characteristic not found on $peerId")
                                gatt.close()
                            }
                        }

                        ExchangeProtocol.AccessoryNotify -> {
                            val notifyChar = profile.notifyFromUuid
                                ?.let { service.getCharacteristic(UUID.fromString(it.uppercase())) }
                            val writeChar = service.getCharacteristic(UUID.fromString(profile.writeToUuid.uppercase()))
                            if (notifyChar != null && writeChar != null) {
                                // Subscribe to the accessory's tx, then write the init command so it
                                // notifies its config back (handled in onCharacteristicChanged).
                                gatt.setCharacteristicNotification(notifyChar, true)
                                notifyChar.getDescriptor(cccdUuid)?.let { cccd ->
                                    queue.enqueue(
                                        BleCommand.WriteDescriptor(
                                            cccd,
                                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        )
                                    )
                                }
                                val initCmd = profile.initCommand ?: byteArrayOf(ANDROID_ACCESSORY_INIT_COMMAND)
                                queue.enqueue(
                                    BleCommand.WriteCharacteristic(
                                        writeChar, initCmd,
                                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                    )
                                )
                                // Keep the connection open for the rest of the accessory protocol
                                // (configure-and-start, stop) — see sendToPeer.
                                queue.let { accessoryConnections[peerId] = AccessoryConnection(gatt, writeChar, it) }
                                Log.d(TAG, "GATT client: sent accessory init to $peerId")
                            } else {
                                Log.e(TAG, "GATT client: accessory characteristics not found on $peerId")
                                gatt.close()
                            }
                        }
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
                    val readUuid = profile?.readFromUuid?.let { UUID.fromString(it.uppercase()) }
                    if (characteristic.uuid == readUuid) {
                        deliverRemoteConfig(peerId, characteristic.value)

                        // Step 2: write our config back to the peer.
                        val service = gatt.getService(UUID.fromString(profile!!.discoveryServiceUuid.uppercase()))
                        val writeChar = service?.getCharacteristic(UUID.fromString(profile.writeToUuid.uppercase()))
                        if (writeChar != null) {
                            queue?.enqueue(
                                BleCommand.WriteCharacteristic(
                                    writeChar, localConfig.toByteArray(),
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                )
                            )
                        } else {
                            gatt.close()
                        }
                    }
                }

                // Accessory notify. Override BOTH signatures: API 33+ dispatches the 3-arg form and
                // does NOT fall back to the deprecated 2-arg, while older devices call the 2-arg.
                @RequiresPermission(BLUETOOTH_CONNECT)
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleAccessoryNotify(gatt, value)
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    handleAccessoryNotify(gatt, characteristic.value ?: return)
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                private fun handleAccessoryNotify(gatt: BluetoothGatt, value: ByteArray) {
                    // First byte is the NI message id, the rest is the payload.
                    if (value.isEmpty()) return
                    Log.d(TAG, "notify received ${value.size} bytes")
                    when (value[0]) {
                        NI_ACCESSORY_CONFIG_DATA -> {
                            // Opaque accessory config — deliver raw; the UWB layer builds the session
                            // and replies (via sendToPeer) with configure-and-start. Stay connected.
                            Log.d(TAG, "GATT client: accessory sent config, ${value.toHexString()}")
                            deliverAccessoryConfig(peerId, value.copyOfRange(1, value.size))
                        }
                        NI_ACCESSORY_DID_START -> Log.d(TAG, "GATT client: accessory did start")
                        NI_ACCESSORY_DID_STOP -> {
                            Log.d(TAG, "GATT client: accessory did stop")
                            accessoryConnections.remove(peerId)
                            gatt.disconnect()
                            gatt.close()
                        }
                        else -> Log.d(TAG, "GATT client: unexpected accessory response ${value[0]}")
                    }
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int
                ) {
                    queue?.operationComplete()
                }

                @RequiresPermission(BLUETOOTH_CONNECT)
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    queue?.operationComplete()
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "GATT client: wrote to $peerId")
                    } else {
                        Log.e(TAG, "GATT client: write failed for $peerId, status=$status")
                    }
                    // For phone-to-phone, the exchange ends once we've written our config back.
                    if (profile?.exchange == ExchangeProtocol.ReadWrite) {
                        gatt.disconnect()
                        gatt.close()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting GATT to $peerId: ${e.message}")
        }
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    actual fun sendToPeer(peerId: String, data: ByteArray) {
        val conn = accessoryConnections[peerId]
        if (conn == null) {
            Log.d(TAG, "sendToPeer: no open accessory connection for $peerId")
            return
        }
        conn.queue.enqueue(
            BleCommand.WriteCharacteristic(
                conn.writeChar, data,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        )
        Log.d(TAG, "sendToPeer: wrote ${data.size} bytes to $peerId")
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    actual fun cleanup() {
        stopScanning()
        stopAdvertising()
        stopGattServer()
        if (hasConnectPermission()) {
            accessoryConnections.values.forEach { it.gatt.close() }
        }
        accessoryConnections.clear()
        discoveredDevices.clear()
        deviceDiscoveredCallback = null
        configExchangedCallback = null
        Log.d(TAG, "BLE cleanup completed")
    }
}
