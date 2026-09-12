package com.dustedrob.uwb

/**
 * Platform UWB manager for ranging with nearby peers.
 *
 * Typical flow:
 * 1. [initialize] — prepare the UWB subsystem and obtain local parameters.
 * 2. [getLocalConfig] — retrieve our local UWB config to share with the peer via BLE.
 * 3. [startRanging] — begin ranging using both local and exchanged remote configs.
 * 4. [stopRanging] — stop a specific ranging session.
 */
expect class MultiplatformUwbManager {
    /** Initialize the UWB subsystem. Must be called before [getLocalConfig]. */
    suspend fun initialize()

    /**
     * Get a UWB session configuration for this connection to a peer.
     * Returns null if [initialize] hasn't completed or UWB is unavailable.
     *
     * On Android: contains local UWB address, proposed session ID, channel, preamble.
     * On iOS: contains serialized NI discovery token.
     */

    fun createConnectionConfig(peerId:String, isAccessory:Boolean): UwbSessionConfig?
    fun getConnectionConfig(peerId: String): UwbSessionConfig?
    /**
     * Start ranging with a peer using exchanged configurations.
     *
     * @param peerId Identifier for the peer (BLE device address/UUID).
     * @param remoteConfig The peer's [UwbSessionConfig] received via BLE GATT exchange.
     */
    suspend fun startRanging(peerId: String, remoteConfig: UwbSessionConfig)

    /** Stop ranging with the given peer. */
    suspend fun stopRanging(peerId: String)

    /** Register callback for ranging distance updates. */
    fun setRangingCallback(callback: (peerId: String, distance: Double, azimuth: Double?, elevation: Double?) -> Unit)

    /**
     * Register the outbound channel used to send data back to a peer over BLE.
     *
     * Needed for accessory ranging on iOS: `NINearbyAccessoryConfiguration` produces shareable
     * configuration data *after* the session runs, which must be written to the accessory. The
     * orchestrator wires this to [BleManager.sendToPeer]. Unused on Android (no data is generated
     * post-run there).
     */
    fun setSendToPeerCallback(callback: (peerId: String, data: ByteArray) -> Unit)

    /**
     * Register callback for errors. [peerId] names the session the error belongs to, or is null for
     * device-wide failures (UWB unsupported, initialization), so the orchestrator can mark one peer
     * as failed instead of treating every error as global.
     */
    fun setErrorCallback(callback: (peerId: String?, error: String) -> Unit)

    /** Clean up resources and unbind services. Call when done using the manager. */
    suspend fun cleanup()

}
