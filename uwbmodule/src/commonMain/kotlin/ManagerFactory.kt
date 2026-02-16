package com.dustedrob.uwb

expect class ManagerFactory {
    fun createUwbManager(): MultiplatformUwbManager
    fun createBleManager(): BleManager
}
