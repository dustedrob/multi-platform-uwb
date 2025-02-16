import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

class DeviceDiscoveryManager(
    private val uwbManager: UwbManager,
    private val bleManager: BleManager
) {
    private val _nearbyDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices: Flow<List<NearbyDevice>> = _nearbyDevices.asStateFlow()

    private var isScanning = false

    fun startScanning() {
        if (isScanning) return
        isScanning = true

        // Start BLE scanning and advertising
        bleManager.startScanning()
        bleManager.advertise()

        // Initialize UWB
        uwbManager.initialize()
    }

    fun stopScanning() {
        if (!isScanning) return
        isScanning = false

        // Stop BLE scanning and advertising
        bleManager.stopScanning()
        bleManager.stopAdvertising()

        // Stop all active UWB ranging sessions
        _nearbyDevices.value.forEach { device ->
            uwbManager.stopRanging(device.id)
        }

        // Clear device list
        _nearbyDevices.value = emptyList()
    }

    // Called when a new device is discovered via BLE
    internal fun onDeviceDiscovered(id: String, name: String) {
        val existingDevices = _nearbyDevices.value.toMutableList()
        val existingDevice = existingDevices.find { it.id == id }

        if (existingDevice == null) {
            existingDevices.add(NearbyDevice(id, name))
            _nearbyDevices.value = existingDevices

            // Start UWB ranging with the new device
            uwbManager.startRanging(id)
        }
    }

    // Called when UWB ranging data is received
    internal fun onRangingResult(peerId: String, distance: Double) {
        val existingDevices = _nearbyDevices.value.toMutableList()
        val deviceIndex = existingDevices.indexOfFirst { it.id == peerId }

        if (deviceIndex != -1) {
            existingDevices[deviceIndex] = existingDevices[deviceIndex].copy(
                distance = distance,
                lastSeen = Clock.System.now().toEpochMilliseconds()
            )
            _nearbyDevices.value = existingDevices
        }
    }
}
