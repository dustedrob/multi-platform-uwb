package com.dustedrob.uwb

import android.content.Context
import androidx.core.uwb.UwbManager

actual class ManagerFactory(
    private val context: Context,
    actual val options: UwbOptions = UwbOptions(),
) {
    // One shared UWB manager so BleManager and DeviceDiscoveryManager see the same per-peer
    // scopes and connection configs (previously bridged via process-global static maps).
    private val uwbManager: MultiplatformUwbManager by lazy {
        MultiplatformUwbManager(UwbManager.createInstance(context), options)
    }

    actual fun createUwbManager(): MultiplatformUwbManager = uwbManager

    actual fun createBleManager(config: BleDiscoveryConfig): BleManager =
        BleManager(context, config, uwbManager)
}
