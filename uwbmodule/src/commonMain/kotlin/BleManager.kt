package com.dustedrob.uwb

/**
 * Platform BLE manager handling device discovery via scanning/advertising
 * and UWB session configuration exchange via GATT.
 */
expect class BleManager {
    /** Start scanning for nearby UWB-capable devices. */
    fun startScanning()

    /** Stop BLE scanning. */
    fun stopScanning()

    /** Start BLE advertising as a UWB-capable device. */
    fun advertise()

    /** Stop BLE advertising. */
    fun stopAdvertising()

    /** Register callback invoked when a new BLE device is discovered. */
    fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit)

    // ---- GATT-based UWB config exchange ----

    /**
     * Start a GATT server that exposes our local [UwbSessionConfig].
     * Remote peers can read our config and write theirs.
     */
    fun startGattServer(localConfig: UwbSessionConfig)

    /** Stop the GATT server. */
    fun stopGattServer()

    /**
     * As a GATT client, connect to [peerId] and exchange UWB configs.
     * Reads the peer's config and writes our own.
     * Result delivered via [setConfigExchangedCallback].
     */
    fun connectAndExchangeConfig(peerId: String, localConfig: UwbSessionConfig)

    /**
     * Register callback invoked when a config exchange completes (on either side).
     * Called with the peer's ID and their [UwbSessionConfig].
     */
    fun setConfigExchangedCallback(callback: (peerId: String, remoteConfig: UwbSessionConfig) -> Unit)

    /** Clean up BLE resources. Call when done using the manager. */
    fun cleanup()
}
