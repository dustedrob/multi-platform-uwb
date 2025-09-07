expect class BleManager {
    fun startScanning()
    fun stopScanning()
    fun advertise()
    fun stopAdvertising()
    fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit)
}