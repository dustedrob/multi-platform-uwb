package com.dustedrob.uwb

actual class ManagerFactory {
    actual fun createUwbManager(): MultiplatformUwbManager {
        return MultiplatformUwbManager()
    }

    actual fun createBleManager(config: BleDiscoveryConfig): BleManager {
        return BleManager(config)
    }
}
