package com.dustedrob.uwb

actual class ManagerFactory(actual val options: UwbOptions = UwbOptions()) {
    // One shared UWB manager so BleManager and DeviceDiscoveryManager see the same per-peer
    // sessions and connection configs.
    private val uwbManager = MultiplatformUwbManager(options)

    actual fun createUwbManager(): MultiplatformUwbManager = uwbManager

    actual fun createBleManager(config: BleDiscoveryConfig): BleManager =
        BleManager(config, uwbManager)
}
