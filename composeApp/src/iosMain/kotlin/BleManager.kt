import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

actual class BleManager {
    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null
    private var deviceDiscoveredCallback: ((String, String) -> Unit)? = null
    
    private val uwbServiceUUID = CBUUID.UUIDWithString("00000000-0000-1000-8000-00805F9B34FB")
    
    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            when (central.state) {
                CBManagerStatePoweredOn -> {
                    // Bluetooth is ready
                }
                CBManagerStatePoweredOff -> {
                    // Handle Bluetooth off
                }
                else -> {
                    // Handle other states
                }
            }
        }
        
        override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
            val deviceName = didDiscoverPeripheral.name ?: "Unknown Device"
            val deviceId = didDiscoverPeripheral.identifier.UUIDString
            deviceDiscoveredCallback?.invoke(deviceId, deviceName)
        }
    }
    
    private val peripheralDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            when (peripheral.state) {
                CBManagerStatePoweredOn -> {
                    // Ready to advertise
                }
                else -> {
                    // Handle other states
                }
            }
        }
        
        override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
            if (error != null) {
                // Handle advertising error
            }
        }
    }

    actual fun startScanning() {
        centralManager = CBCentralManager(centralDelegate, null)
        centralManager?.scanForPeripheralsWithServices(listOf(uwbServiceUUID), null)
    }

    actual fun stopScanning() {
        centralManager?.stopScan()
    }

    actual fun advertise() {
        peripheralManager = CBPeripheralManager(peripheralDelegate, null)
        
        val advertisementData = mapOf<Any?, Any?>(
            CBAdvertisementDataServiceUUIDsKey to listOf(uwbServiceUUID),
            CBAdvertisementDataLocalNameKey to "UWB Device"
        )
        
        peripheralManager?.startAdvertising(advertisementData)
    }

    actual fun stopAdvertising() {
        peripheralManager?.stopAdvertising()
    }
    
    actual fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit) {
        deviceDiscoveredCallback = callback
    }
}