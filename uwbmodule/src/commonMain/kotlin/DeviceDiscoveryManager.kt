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

    companion object {
        /** Devices not seen within this window are considered stale and removed. */
        private const val STALE_THRESHOLD_MS = 10_000L
        private const val CLEANUP_INTERVAL_MS = 5_000L
    }

    init {
        // Route all callbacks through the coroutine scope to serialize state access
        bleManager.setDeviceDiscoveredCallback { id, name ->
            scope.launch { onDeviceDiscovered(id, name) }
        }

        bleManager.setConfigExchangedCallback { peerId, remoteConfig ->
            scope.launch { onConfigExchanged(peerId, remoteConfig) }
        }

        multiplatformUwbManager.setRangingCallback { peerId, distance ->
            scope.launch { onRangingResult(peerId, distance) }
        }

        multiplatformUwbManager.setErrorCallback { error ->
            emitEvent(EventType.Error, "", error)
        }
    }

    /** Get the local UWB config (address, session ID, channel) for display. */
    fun getLocalConfig(): UwbSessionConfig? = multiplatformUwbManager.getLocalConfig()

    suspend fun startScanning() {
        if (isScanning) return
        isScanning = true

        // Initialize UWB and wait for it to complete before reading local config
        multiplatformUwbManager.initialize()

        // Start GATT server so peers can exchange configs with us
        val localConfig = multiplatformUwbManager.getLocalConfig()
        if (localConfig != null) {
            bleManager.startGattServer(localConfig)
        }

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

    fun stopScanning() {
        if (!isScanning) return
        isScanning = false

        // Stop stale device cleanup
        cleanupJob?.cancel()
        cleanupJob = null

        // Stop BLE
        bleManager.stopScanning()
        bleManager.stopAdvertising()
        bleManager.stopGattServer()

        // Stop all active UWB ranging sessions
        _nearbyDevices.value.forEach { device ->
            multiplatformUwbManager.stopRanging(device.id)
        }

        // Clear state
        _nearbyDevices.value = emptyList()
        exchangedPeers.clear()
        pendingExchanges.clear()
    }

    /**
     * Clean up all resources, including UWB manager and BLE manager.
     * Should be called when the manager is no longer needed (e.g., in ViewModel.onCleared).
     */
    fun cleanup() {
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
                emitEvent(EventType.Error, device.id, "Device stale, removed: ${device.name}")
            }
            _nearbyDevices.value = active
        }
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
            val localConfig = multiplatformUwbManager.getLocalConfig()
            if (localConfig != null) {
                pendingExchanges.add(id)
                emitEvent(EventType.ConfigExchangeStarted, id, "Starting GATT config exchange")
                updateDeviceStateLocked(id, DeviceState.ExchangingConfig)
                bleManager.connectAndExchangeConfig(id, localConfig)
            }
        }
    }

    /**
     * Called when BLE GATT config exchange completes with a peer.
     * Starts UWB ranging with the exchanged config.
     */
    internal suspend fun onConfigExchanged(peerId: String, remoteConfig: UwbSessionConfig) {
        mutex.withLock {
            // Guard against duplicate callbacks (both GATT client read and server write fire this)
            if (peerId in exchangedPeers) return
            pendingExchanges.remove(peerId)
            exchangedPeers.add(peerId)

            emitEvent(
                EventType.ConfigExchangeComplete, peerId,
                "Config exchanged — session=${remoteConfig.sessionId} ch=${remoteConfig.channel}"
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
        }

        // Start UWB ranging outside the lock (startRanging may take time)
        multiplatformUwbManager.startRanging(peerId, remoteConfig)
    }

    /** Called when UWB ranging data is received. */
    internal suspend fun onRangingResult(peerId: String, distance: Double) = mutex.withLock {
        val existingDevices = _nearbyDevices.value.toMutableList()
        val deviceIndex = existingDevices.indexOfFirst { it.id == peerId }

        if (deviceIndex != -1) {
            existingDevices[deviceIndex] = existingDevices[deviceIndex].copy(
                distance = distance,
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
