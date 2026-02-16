package com.dustedrob.uwb

actual class ManagerFactory {
    actual fun createUwbManager(): MultiplatformUwbManager {
        return MultiplatformUwbManager()
    }

    actual fun createBleManager(): BleManager {
        return BleManager()
    }
}
