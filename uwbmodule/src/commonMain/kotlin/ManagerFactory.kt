expect class ManagerFactory {
    fun createUwbManager(): MultiplatformUwbManager
    fun createBleManager(): BleManager
}