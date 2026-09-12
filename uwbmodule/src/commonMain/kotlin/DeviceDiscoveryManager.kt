package com.dustedrob.uwb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Types of lifecycle events emitted during discovery → ranging.
 */
enum class EventType {
    DeviceDiscovered,
    ConfigExchangeStarted,
    ConfigExchangeComplete,
    RangingStarted,
    RangingUpdate,
    Error
}

/**
 * A timestamped lifecycle event from the discovery pipeline.
 */
data class DiscoveryEvent(
    val timestamp: Long,
    val type: EventType,
    val peerId: String,
    val message: String
)


/**
 * Orchestrates the full device discovery → BLE config exchange → UWB ranging pipeline.
 *
 * Flow:
 * 1. BLE scan discovers a nearby device.
 * 2. BLE GATT exchange trades UWB session configs between the two devices.
 * 3. UWB ranging starts with the agreed-upon parameters.
 * 4. Distance updates are emitted via [nearbyDevices].
 */
class DeviceDiscoveryManager(
    private val multiplatformUwbManager: MultiplatformUwbManager,
    private val bleManager: BleManager
) {
    private val _nearbyDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices: Flow<List<NearbyDevice>> = _nearbyDevices.asStateFlow()

    private val _events = MutableSharedFlow<DiscoveryEvent>(extraBufferCapacity = 64)
    /** Lifecycle events for UI debugging. */
    val events: Flow<DiscoveryEvent> = _events.asSharedFlow()

    private var isScanning = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cleanupJob: Job? = null

    /** Protects all shared mutable state (device list, peer tracking sets). */
    private val mutex = Mutex()

    /** Peers that have completed config exchange and are ranging or about to range. */
    private val exchangedPeers = mutableSetOf<String>()

    /** Peers we've initiated a config exchange with (to avoid duplicates). */
    private val pendingExchanges = mutableSetOf<String>()

    /** Accessory peers — these stay BLE-connected during ranging and need a stop handshake. */
    private val accessoryPeers = mutableSetOf<String>()

    /**
     * Maps a peer's stable identity key (hex) to the peerId currently ranging with it.
     *
     * On Android a single phone appears under several randomized BLE addresses because it both scans
     * and runs a GATT server, so the same physical device arrives under different peerIds. The UWB
     * address is no longer stable across those identities (each peer gets its own session scope, so
     * a phone hands each of our BLE identities a different address), but the static-STS session key
     * is generated once per manager and shipped in every config, so it identifies the device. Unused
     * on iOS, where the CoreBluetooth UUID is already stable and the config carries no key.
     */
    private val identityKeyToPeer = mutableMapOf<String, String>()

    companion object {
        /** Devices not seen within this window are considered stale and removed. */
        private const val STALE_THRESHOLD_MS = 10_000L
        private const val CLEANUP_INTERVAL_MS = 5_000L

        /**
         * Stable identity of the device behind a config, or null when the platform gives none (iOS).
         *
         * Phones: the session key (see [identityKeyToPeer]). Accessories: their UWB address, which is
         * a fixed hardware address; the "remote" config for an accessory is our own config with the
         * accessory's address patched in, so its key would be ours and every accessory would collide.
         */
        internal fun identityKeyFor(remoteConfig: UwbSessionConfig): String? = when {
            remoteConfig.isAccessoryDevice ->
                remoteConfig.uwbAddress.takeIf { it.isNotEmpty() }?.let { "acc:" + it.toHexString() }
            else ->
                remoteConfig.sessionKey?.takeIf { it.isNotEmpty() }?.let { "key:" + it.toHexString() }
        }
    }

    init {
        // Route all callbacks through the coroutine scope to serialize state access
        bleManager.setDeviceDiscoveredCallback { id, name ->
            scope.launch { onDeviceDiscovered(id, name) }
        }

        bleManager.setConfigExchangedCallback { peerId, remoteConfig ->
            scope.launch { onConfigExchanged(peerId, remoteConfig) }
        }

        multiplatformUwbManager.setRangingCallback { peerId , distance , azimuth, elevation ->
            scope.launch { onRangingResult(peerId, distance, azimuth, elevation ) }
        }

        // Return path for the accessory protocol: data the UWB layer generates (e.g. iOS shareable
        // configuration data) is written back to the accessory over the still-open BLE connection.
        multiplatformUwbManager.setSendToPeerCallback { peerId, data ->
            bleManager.sendToPeer(peerId, data)
        }

        multiplatformUwbManager.setErrorCallback { peerId, error ->
            emitEvent(EventType.Error, peerId ?: "", error)
            // A per-peer failure only takes that peer out of Ranging, so stale cleanup can drop it
            // and a re-discovery can start over, while the other sessions carry on.
            if (peerId != null) scope.launch { onRangingError(peerId, error) }
        }
    }

    fun getConnectionConfig(peerId:String): UwbSessionConfig?{
        return multiplatformUwbManager.getConnectionConfig((peerId))
    }

    /** Get the local UWB config (address, session ID, channel) for display. */
    //suspend fun getLocalConfig(): UwbSessionConfig? = multiplatformUwbManager.getLocalConfig(false)

    suspend fun startScanning() {
        if (isScanning) return
        isScanning = true

        // Initialize UWB and wait for it to complete before reading local config
        multiplatformUwbManager.initialize()

        // Start GATT server so peers can exchange configs with us
        bleManager.startGattServer()

        // Start BLE scanning and advertising
        bleManager.startScanning()
        bleManager.advertise()

        // Start periodic cleanup of stale devices
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                removeStaleDevices()
            }
        }
    }

    suspend fun stopScanning() {
        if (!isScanning) return
        isScanning = false

        // Stop stale device cleanup
        cleanupJob?.cancel()
        cleanupJob = null

        // Stop BLE
        bleManager.stopScanning()
        bleManager.stopAdvertising()
        bleManager.stopGattServer()

        // Stop all active UWB ranging sessions. Accessory peers also get a BLE stop command so the
        // accessory ends ranging and the BLE layer disconnects after its didStop confirmation.
        _nearbyDevices.value.forEach { device ->
            multiplatformUwbManager.stopRanging(device.id)
            if (device.id in accessoryPeers) {
                bleManager.sendToPeer(device.id, byteArrayOf(NI_ACCESSORY_STOP))
            }
        }

        // Clear state
        _nearbyDevices.value = emptyList()
        exchangedPeers.clear()
        pendingExchanges.clear()
        accessoryPeers.clear()
        identityKeyToPeer.clear()
    }

    /**
     * Clean up all resources, including UWB manager and BLE manager.
     * Should be called when the manager is no longer needed (e.g., in ViewModel.onCleared).
     */
    suspend fun cleanup() {
        stopScanning()
        multiplatformUwbManager.cleanup()
        bleManager.cleanup()
        scope.cancel()
    }

    private suspend fun removeStaleDevices() = mutex.withLock {
        val now = getCurrentTimeMillis()
        val currentDevices = _nearbyDevices.value
        val (stale, active) = currentDevices.partition { device ->
            device.state != DeviceState.Ranging && (now - device.lastSeen) > STALE_THRESHOLD_MS
        }
        if (stale.isNotEmpty()) {
            stale.forEach { device ->
                pendingExchanges.remove(device.id)
                exchangedPeers.remove(device.id)
                identityKeyToPeer.entries.removeAll { it.value == device.id }
                emitEvent(EventType.Error, device.id, "Device stale, removed: ${device.name}")
            }
            _nearbyDevices.value = active
        }
    }

    /** A ranging session for one peer failed or ended; mark just that device so it can age out. */
    internal suspend fun onRangingError(peerId: String, error: String) = mutex.withLock {
        val devices = _nearbyDevices.value
        val idx = devices.indexOfFirst { it.id == peerId }
        if (idx == -1 || devices[idx].state != DeviceState.Ranging) return@withLock
        updateDeviceStateLocked(peerId, DeviceState.Error, error)
    }

    private fun emitEvent(type: EventType, peerId: String, message: String) {
        _events.tryEmit(DiscoveryEvent(getCurrentTimeMillis(), type, peerId, message))
    }

    /**
     * Called when a new device is discovered via BLE scan.
     * Initiates GATT config exchange if we haven't already.
     */
    internal suspend fun onDeviceDiscovered(id: String, name: String) = mutex.withLock {
        // Add to device list if not already present
        val existingDevices = _nearbyDevices.value.toMutableList()
        val existingIdx = existingDevices.indexOfFirst { it.id == id }
        if (existingIdx == -1) {
            existingDevices.add(NearbyDevice(id, name, state = DeviceState.Discovered))
            _nearbyDevices.value = existingDevices
            emitEvent(EventType.DeviceDiscovered, id, "BLE device discovered: $name")
        } else {
            // Update lastSeen on re-discovery (Bug 10 fix)
            existingDevices[existingIdx] = existingDevices[existingIdx].copy(
                lastSeen = getCurrentTimeMillis()
            )
            _nearbyDevices.value = existingDevices
        }

        // Initiate config exchange if not already done/pending
        if (id !in exchangedPeers && id !in pendingExchanges) {
            val connectionConfig = multiplatformUwbManager.getConnectionConfig(id)
            if (connectionConfig != null) {
                pendingExchanges.add(id)
                emitEvent(EventType.ConfigExchangeStarted, id, "Starting GATT config exchange")
                updateDeviceStateLocked(id, DeviceState.ExchangingConfig)
                bleManager.connectAndExchangeConfig(id, connectionConfig)
            } else {
                emitEvent(EventType.DeviceDiscovered, id, "no config entry found")
            }
        }
    }

    /**
     * Called when BLE GATT config exchange completes with a peer.
     * Starts UWB ranging with the exchanged config.
     */
    internal suspend fun onConfigExchanged(peerId: String, remoteConfig: UwbSessionConfig) {
        var duplicateOf: String? = null
        val shouldStart = mutex.withLock {
            // Guard against duplicate callbacks (both GATT client read and server write fire this)
            if (peerId in exchangedPeers) return@withLock false
            pendingExchanges.remove(peerId)
            exchangedPeers.add(peerId)
            if (remoteConfig.isAccessoryDevice || remoteConfig.accessoryData!=null) accessoryPeers.add(peerId)

            // Collapse duplicate BLE identities of the same physical device. A phone both scans and
            // serves under randomized BLE addresses, so the same device arrives under several peerIds;
            // the session key in the exchanged config is the stable identity (see identityKeyToPeer).
            // If we're already ranging that device, keep the first session and ignore the duplicate
            // rather than tearing the live one down (which churned the session and cancelled its
            // coroutine). Skipped on iOS (no key), where the peerId is already stable.
            val identityKey = identityKeyFor(remoteConfig)
            if (identityKey != null) {
                val prevPeerId = identityKeyToPeer[identityKey]
                if (prevPeerId != null && prevPeerId != peerId) {
                    // Already ranging this device under another BLE identity. Keep the live session and
                    // drop this identity's placeholder entry so the UI shows one device, not two.
                    // peerId stays in exchangedPeers so we don't re-exchange with the duplicate.
                    _nearbyDevices.value = _nearbyDevices.value.filterNot { it.id == peerId }
                    emitEvent(EventType.DeviceDiscovered, peerId, "Ignored duplicate identity of $prevPeerId (key $identityKey)")
                    duplicateOf = prevPeerId
                    return@withLock false
                }
                identityKeyToPeer[identityKey] = peerId
            }

            emitEvent(
                EventType.ConfigExchangeComplete, peerId,
                "Config exchanged — session=${remoteConfig.sessionId?.toHexString()} ch=${remoteConfig.channel} addr=${remoteConfig.uwbAddress.toHexString()}"
            )

            // Ensure peer is in our device list and update with config info
            val existingDevices = _nearbyDevices.value.toMutableList()
            val idx = existingDevices.indexOfFirst { it.id == peerId }
            if (idx != -1) {
                existingDevices[idx] = existingDevices[idx].copy(
                    state = DeviceState.Ranging,
                    sessionId = remoteConfig.sessionId,
                    channel = remoteConfig.channel
                )
            } else {
                existingDevices.add(
                    NearbyDevice(
                        peerId, "UWB Device",
                        state = DeviceState.Ranging,
                        sessionId = remoteConfig.sessionId,
                        channel = remoteConfig.channel
                    )
                )
            }
            _nearbyDevices.value = existingDevices

            emitEvent(EventType.RangingStarted, peerId, "UWB ranging started")
            true
        }

        // Start UWB ranging outside the lock: with several peers, one session start must not hold up
        // the callbacks of the others.
        if (shouldStart) multiplatformUwbManager.startRanging(peerId, remoteConfig)
        // The duplicate identity never ranges, so hand back the session resources (on Android the
        // per-peer scopes and their addresses) that were minted for it at discovery.
        if (duplicateOf != null) multiplatformUwbManager.stopRanging(peerId)
    }


    /** Called when UWB ranging data is received. */
    internal suspend fun onRangingResult(peerId: String, distance: Double, azimuth: Double?, elevation: Double?) = mutex.withLock {
        val existingDevices = _nearbyDevices.value.toMutableList()
        val deviceIndex = existingDevices.indexOfFirst { it.id == peerId }

        if (deviceIndex != -1) {                        
            existingDevices[deviceIndex] = existingDevices[deviceIndex].copy(                
                distance = distance,
                azimuth = azimuth,
                elevation = elevation,
                lastSeen = getCurrentTimeMillis(),
                state = DeviceState.Ranging
            )
            _nearbyDevices.value = existingDevices
        }
    }

    /** Must be called while holding [mutex]. */
    private fun updateDeviceStateLocked(id: String, state: DeviceState, error: String? = null) {
        val devices = _nearbyDevices.value.toMutableList()
        val idx = devices.indexOfFirst { it.id == id }
        if (idx != -1) {
            devices[idx] = devices[idx].copy(state = state, errorMessage = error)
            _nearbyDevices.value = devices
        }
    }
}
