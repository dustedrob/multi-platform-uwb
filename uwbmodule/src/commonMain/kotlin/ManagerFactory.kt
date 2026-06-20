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

    /**
     * Create a platform-specific [BleManager] for BLE discovery and GATT exchange.
     *
     * @param config the profiles to scan/serve and the locally advertised identity. Defaults to the
     *   library's vendor-free [BleDiscoveryConfig]; pass a custom one to add vendor profiles
     *   (e.g. [QORVO_NEARBY_PROFILE]) or change which profile this device advertises.
     */
    fun createBleManager(config: BleDiscoveryConfig = BleDiscoveryConfig()): BleManager
}
