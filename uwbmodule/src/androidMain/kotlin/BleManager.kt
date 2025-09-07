import android.Manifest
import android.Manifest.permission.BLUETOOTH_SCAN
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

actual class BleManager(private val context: Context) {
    private val TAG = "BleManager"

    // UWB service UUID - you should define a specific one for your app
    private val UWB_SERVICE_UUID = UUID.fromString("00000000-0000-1000-8000-00805F9B34FB")

    // Bluetooth components
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var deviceDiscoveredCallback: ((String, String) -> Unit)? = null

    // Scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Process scan results
            val device = result.device
            val deviceName = device.name ?: "Unknown Device"
            val deviceAddress = device.address

            Log.d(TAG, "Found device: $deviceName ($deviceAddress)")

            deviceDiscoveredCallback?.invoke(deviceAddress, deviceName)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    // Advertise callback
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertising failed with error code: $errorCode")
        }
    }

    actual fun startScanning() {
        // Check if Bluetooth is available and enabled
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or not supported")
            return
        }

        // Check for scan permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing BLUETOOTH_SCAN permission")
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing ACCESS_FINE_LOCATION permission")
                return
            }
        }

        try {
            // Get scanner
            val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "Bluetooth LE Scanner not available")
                return
            }

            // Create scan settings
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Create scan filter for UWB service
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UWB_SERVICE_UUID))
                .build()


            if (context.checkSelfPermission(BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner.startScan(
                    listOf(scanFilter),
                    scanSettings,
                    scanCallback
                )
                Log.d(TAG, "BLE scanning started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan: ${e.message}")
        }
    }

    actual fun stopScanning() {
        // Check for scan permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing BLUETOOTH_SCAN permission")
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing ACCESS_FINE_LOCATION permission")
                return
            }
        }

        if (context.checkSelfPermission(BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                Log.d(TAG, "BLE scanning stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping BLE scan: ${e.message}")
            }
        }
    }

    actual fun advertise() {
        // Check if Bluetooth is available and enabled
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled or not supported")
            return
        }

        // Check for advertise permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission")
                return
            }
        }

        try {
            // Get advertiser
            val bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (bluetoothLeAdvertiser == null) {
                Log.e(TAG, "Bluetooth LE Advertiser not available")
                return
            }

            // Create advertise settings
            val advertiseSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            // Create advertise data with UWB service UUID
            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(UWB_SERVICE_UUID))
                .build()

            // Start advertising

            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeAdvertiser.startAdvertising(
                    advertiseSettings,
                    advertiseData,
                    advertiseCallback
                )
                Log.d(TAG, "BLE advertising started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE advertising: ${e.message}")
        }
    }

    actual fun stopAdvertising() {
        // Check for advertise permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission")
                return
            }
        }

        try {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                Log.d(TAG, "BLE advertising stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertising: ${e.message}")
        }
    }

    actual fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit) {
        deviceDiscoveredCallback = callback
    }
}