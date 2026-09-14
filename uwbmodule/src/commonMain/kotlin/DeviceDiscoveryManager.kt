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
import kotlinx.coroutines.channels.BufferOverflow
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
    val message: String,
    val distance: Double? = null,
    val azimuth: Double? = null,
    val elevation: Double? = null,
    val name: String? = null,
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

    private val _events = MutableSharedFlow<DiscoveryEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Lifecycle events for UI debugging. */
    val events: Flow<DiscoveryEvent> = _events.asSharedFlow()

    private var isScanning = false

    /** When set, only BLE peers for which this returns true are connected and ranged. */
    private var deviceFilter: ((bleId: String, advertisedName: String) -> Boolean)? = null

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

    /** The BLE identity (peerId) we range with for one physical device, and how it scored. */
    private class PeerBinding(val peerId: String, val score: String)

    /**
     * Maps a peer's stable identity key (hex) to the BLE identity currently ranging with it.
     *
     * On Android a single phone appears under several randomized BLE addresses because it both scans
     * and runs a GATT server, so the same physical device arrives under different peerIds. The UWB
     * address is no longer stable across those identities (each peer gets its own session scope, so
     * a phone hands each of our BLE identities a different address), but the static-STS session key
     * is generated once per manager and shipped in every config, so it identifies the device. Unused
     * on iOS, where the CoreBluetooth UUID is already stable and the config carries no key.
     */
    private val identityKeyToPeer = mutableMapOf<String, PeerBinding>()

    companion object {
        /** Devices not seen within this window are considered stale and removed. */
        private const val STALE_THRESHOLD_MS = 10_000L
        /** A suspended session gets longer: iOS keeps them for a while and re-runs them on resume. */
        private const val SUSPENDED_THRESHOLD_MS = 60_000L
        private const val CLEANUP_INTERVAL_MS = 5_000L

        /**
         * Which of a device's BLE connections to range over, decided the same way on both ends.
         *
         * With one session scope per BLE identity, the two phones must range over the same
         * connection or they target addresses the other side never uses. Neither side knows which
         * connection the other saw first (the GATT client always completes an exchange one round trip
         * before the server), but both see the same two configs on a given connection, so a score
         * built from the unordered pair of addresses is identical on both ends. Lower wins.
         */
        internal fun connectionScore(local: UwbSessionConfig, remote: UwbSessionConfig): String =
            listOf(local.uwbAddress.toHexString(), remote.uwbAddress.toHexString())
                .sorted()
                .joinToString("|")

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

        multiplatformUwbManager.setSessionEventCallback { peerId, event ->
            scope.launch { onSessionEvent(peerId, event) }
        }
    }

    fun getConnectionConfig(peerId:String): UwbSessionConfig?{
        return multiplatformUwbManager.getConnectionConfig((peerId))
    }

    /** Get the local UWB config (address, session ID, channel) for display. */
    //suspend fun getLocalConfig(): UwbSessionConfig? = multiplatformUwbManager.getLocalConfig(false)

    /**
     * @param deviceFilter Optional app predicate. Called with the platform BLE id (Android MAC or
     *   iOS peripheral UUID) and the advertised GAP name. Return true to connect/range this peer.
     *   Null accepts every discovery.
     * @param advertise When false, this phone does not advertise as a UWB peer (accessory-controller mode).
     * @param hostGattServer When false, skip the local GATT server (not needed when we only connect to accessories).
     */
    suspend fun startScanning(
        deviceFilter: ((bleId: String, advertisedName: String) -> Boolean)? = null,
        advertise: Boolean = true,
        hostGattServer: Boolean = true,
    ) {
        if (isScanning) return
        isScanning = true
        this.deviceFilter = deviceFilter

        // Initialize UWB and wait for it to complete before reading local config
        multiplatformUwbManager.initialize()

        if (hostGattServer) {
            bleManager.startGattServer()
        }

        bleManager.startScanning()
        if (advertise) {
            bleManager.advertise()
        }

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
        // exchangedPeers covers sessions whose device entry is gone (purged, or a duplicate identity).
        (_nearbyDevices.value.map { it.id } + exchangedPeers).distinct().forEach { peerId ->
            multiplatformUwbManager.stopRanging(peerId)
            if (peerId in accessoryPeers) {
                bleManager.sendToPeer(peerId, byteArrayOf(NI_ACCESSORY_STOP))
            }
        }

        // Clear state
        _nearbyDevices.value = emptyList()
        exchangedPeers.clear()
        pendingExchanges.clear()
        accessoryPeers.clear()
        identityKeyToPeer.clear()
        deviceFilter = null
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

    private suspend fun removeStaleDevices() {
        val stale = mutex.withLock {
            val now = getCurrentTimeMillis()
            val currentDevices = _nearbyDevices.value
            val (stale, active) = currentDevices.partition { device ->
                val threshold = if (device.state == DeviceState.Suspended) SUSPENDED_THRESHOLD_MS else STALE_THRESHOLD_MS
                device.state != DeviceState.Ranging && (now - device.lastSeen) > threshold
            }
            stale.forEach { device ->
                pendingExchanges.remove(device.id)
                exchangedPeers.remove(device.id)
                accessoryPeers.remove(device.id)
                identityKeyToPeer.entries.removeAll { it.value.peerId == device.id }
                emitEvent(EventType.Error, device.id, "Device stale, removed: ${device.name}")
            }
            if (stale.isNotEmpty()) _nearbyDevices.value = active
            stale
        }
        // Actually let go of the peer: release its session (a purged entry would otherwise keep a
        // live session no one can stop) and the BLE cache entry, so it can be discovered again.
        stale.forEach { device ->
            multiplatformUwbManager.stopRanging(device.id)
            bleManager.forgetDevice(device.id)
        }
    }

    /** A ranging session for one peer failed or ended; mark just that device so it can age out. */
    internal suspend fun onRangingError(peerId: String, error: String) = mutex.withLock {
        val devices = _nearbyDevices.value
        val idx = devices.indexOfFirst { it.id == peerId }
        if (idx == -1 || devices[idx].state == DeviceState.Error) return@withLock
        updateDeviceStateLocked(peerId, DeviceState.Error, error)
    }

    /**
     * Non-fatal session events. The session is still alive, so the device is shown as paused
     * rather than failed and gets a longer stale window; accessories are told to hold while the
     * session is suspended, over a BLE link that stays open for the resume.
     */
    internal suspend fun onSessionEvent(peerId: String, event: SessionEvent) {
        val isAccessory = mutex.withLock {
            val now = getCurrentTimeMillis()
            val devices = _nearbyDevices.value.toMutableList()
            val idx = devices.indexOfFirst { it.id == peerId }
            if (idx == -1) return
            val state = if (event == SessionEvent.Resumed) DeviceState.Ranging else DeviceState.Suspended
            devices[idx] = devices[idx].copy(state = state, lastSeen = now, errorMessage = null)
            _nearbyDevices.value = devices
            emitEvent(EventType.RangingUpdate, peerId, "Session ${event.name.lowercase()}")
            peerId in accessoryPeers
        }
        if (isAccessory && event == SessionEvent.Suspended) {
            // A suspended session stops answering, so an accessory left running would range into a
            // void until it fails. The link is kept so the resume's configure-and-start can reach it.
            bleManager.retainAccessoryLink(peerId)
            bleManager.sendToPeer(peerId, byteArrayOf(NI_ACCESSORY_STOP))
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
        val filter = deviceFilter
        if (filter != null && !filter(id, name)) {
            emitEvent(EventType.DeviceDiscovered, id, "Ignored by app filter: $name")
            return@withLock
        }

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
        var supersedes: String? = null
        val shouldStart = mutex.withLock {
            // Guard against duplicate callbacks (both GATT client read and server write fire this)
            if (peerId in exchangedPeers) return@withLock false
            pendingExchanges.remove(peerId)
            exchangedPeers.add(peerId)
            if (remoteConfig.isAccessoryDevice || remoteConfig.accessoryData!=null) accessoryPeers.add(peerId)

            // Collapse duplicate BLE identities of the same physical device. A phone both scans and
            // serves under randomized BLE addresses, so the same device arrives under several peerIds;
            // the session key in the exchanged config is the stable identity (see identityKeyToPeer).
            // Both ends must settle on the same connection (see connectionScore): the better-scoring
            // one wins whichever order they arrive in, so at most one switch happens, and it happens
            // on both phones. Skipped on iOS (no key), where the peerId is already stable.
            val identityKey = identityKeyFor(remoteConfig)
            if (identityKey != null) {
                val local = multiplatformUwbManager.getConnectionConfig(peerId)
                val score = if (local != null) connectionScore(local, remoteConfig) else ""
                val prev = identityKeyToPeer[identityKey]
                if (prev != null && prev.peerId != peerId) {
                    if (local == null || score >= prev.score) {
                        // Keep the live session; drop this identity's placeholder entry so the UI
                        // shows one device, not two. peerId stays in exchangedPeers so we don't
                        // re-exchange with the duplicate.
                        _nearbyDevices.value = _nearbyDevices.value.filterNot { it.id == peerId }
                        emitEvent(EventType.DeviceDiscovered, peerId, "Ignored duplicate identity of ${prev.peerId} (key $identityKey)")
                        duplicateOf = prev.peerId
                        return@withLock false
                    }
                    // This connection scores better: the peer will pick it too, so move over.
                    _nearbyDevices.value = _nearbyDevices.value.filterNot { it.id == prev.peerId }
                    accessoryPeers.remove(prev.peerId)
                    emitEvent(EventType.DeviceDiscovered, peerId, "Switching from identity ${prev.peerId} (key $identityKey)")
                    supersedes = prev.peerId
                }
                identityKeyToPeer[identityKey] = PeerBinding(peerId, score)
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

        // Outside the lock: with several peers, one session start must not hold up the callbacks of
        // the others. A superseded identity is stopped first so its scopes are released before the
        // replacement starts.
        supersedes?.let { multiplatformUwbManager.stopRanging(it) }
        if (shouldStart) multiplatformUwbManager.startRanging(peerId, remoteConfig)
        // The duplicate identity never ranges, so hand back the session resources (on Android the
        // per-peer scopes and their addresses) that were minted for it at discovery.
        if (duplicateOf != null) multiplatformUwbManager.stopRanging(peerId)
    }


    /** Called when UWB ranging data is received. */
    internal suspend fun onRangingResult(peerId: String, distance: Double, azimuth: Double?, elevation: Double?) = mutex.withLock {
        val existingDevices = _nearbyDevices.value.toMutableList()
        val deviceIndex = existingDevices.indexOfFirst { it.id == peerId }

        val name = if (deviceIndex != -1) existingDevices[deviceIndex].name else peerId
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
        emitEvent(
            EventType.RangingUpdate,
            peerId,
            "distance=$distance azimuth=$azimuth",
            distance = distance,
            azimuth = azimuth,
            elevation = elevation,
            name = name,
        )
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
