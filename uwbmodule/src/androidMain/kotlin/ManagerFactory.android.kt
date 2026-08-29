package com.dustedrob.uwb

import android.content.Context
import android.os.Build
import android.ranging.RangingManager
import androidx.annotation.RequiresApi
import androidx.core.uwb.UwbManager

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
actual class ManagerFactory(private val context: Context) {
    // One shared UWB manager so BleManager and DeviceDiscoveryManager see the same per-peer
    // scopes and connection configs (previously bridged via process-global static maps).
    private val uwbManager: MultiplatformUwbManager by lazy {
        MultiplatformUwbManager(context)
    }

    actual fun createUwbManager(): MultiplatformUwbManager = uwbManager

    actual fun createBleManager(config: BleDiscoveryConfig): BleManager =
        BleManager(context, config, uwbManager)
}
