package com.dustedrob.uwb

/**
 * Platform factory for creating UWB and BLE manager instances.
 *
 * Each platform (Android / iOS) provides its own `actual` implementation
 * that wires platform-specific dependencies (e.g., Android `Context`,
 * iOS `CBCentralManager`).
 */
expect class ManagerFactory {
    /** Create a platform-specific [MultiplatformUwbManager] for UWB ranging. */
    fun createUwbManager(): MultiplatformUwbManager

    /** Create a platform-specific [BleManager] for BLE discovery and GATT exchange. */
    fun createBleManager(): BleManager
}
