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
    /** A session ended on its own (not via stopScanning); see [RecoveryPolicy]. */
    SessionEnded,
    /** A restart of the peer's session was scheduled. */
    RecoveryStarted,
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
    private val bleManager: BleManager,
    private val recovery: RecoveryPolicy = RecoveryPolicy(),
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

    /** Restart attempt bookkeeping per peer (see [RecoveryPolicy]). Guarded by [mutex]. */
    private val recoveryTracker = RecoveryTracker(recovery)

    /** Pending delayed restarts, so stopScanning can cancel them. Guarded by [mutex]. */
    private val restartJobs = mutableMapOf<String, Job>()

    /** Peers whose restart hasn't produced a ranging result yet; the first one resets their attempts. */
    private val recoveringPeers = mutableSetOf<String>()

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

        // Stop stale device cleanup and any restarts still waiting on their backoff
        cleanupJob?.cancel()
        cleanupJob = null
        mutex.withLock {
            restartJobs.values.forEach { it.cancel() }
            restartJobs.clear()
            recoveringPeers.clear()
            recoveryTracker.clear()
        }

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
        val (stale, silent) = mutex.withLock {
            val now = getCurrentTimeMillis()
            val currentDevices = _nearbyDevices.value
            // A Recovering peer is waiting on its backoff, and a Ranging one is watched for silence
            // below, so neither is purged here.
            val (stale, active) = currentDevices.partition { device ->
                val threshold = if (device.state == DeviceState.Suspended) SUSPENDED_THRESHOLD_MS else STALE_THRESHOLD_MS
                device.state != DeviceState.Ranging && device.state != DeviceState.Recovering &&
                    (now - device.lastSeen) > threshold
            }
            stale.forEach { device ->
                pendingExchanges.remove(device.id)
                exchangedPeers.remove(device.id)
                accessoryPeers.remove(device.id)
                identityKeyToPeer.entries.removeAll { it.value.peerId == device.id }
                recoveringPeers.remove(device.id)
                recoveryTracker.reset(device.id)
                emitEvent(EventType.Error, device.id, "Device stale, removed: ${device.name}")
            }
            if (stale.isNotEmpty()) _nearbyDevices.value = active
            // Silence watchdog: the platform can hold a session it calls running while delivering
            // nothing (iOS after a suspension, NaN-only updates). Treat that as an ended session.
            stale to RecoveryTracker.silentPeers(active, now, recovery.silentTimeoutMs)
        }
        // Actually let go of the peer: release its session (a purged entry would otherwise keep a
        // live session no one can stop) and the BLE cache entry, so it can be discovered again.
        stale.forEach { device ->
            multiplatformUwbManager.stopRanging(device.id)
            bleManager.forgetDevice(device.id)
        }
        silent.forEach { device ->
            multiplatformUwbManager.stopRanging(device.id)
            onSessionEnded(device.id, "No ranging results for ${recovery.silentTimeoutMs} ms")
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
        if (event == SessionEvent.Ended) {
            onSessionEnded(peerId, "Session ended")
            return
        }
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

    /**
     * A session is gone (platform said so, or the silence watchdog did) and the manager has already
     * released it. Either schedule a restart after the policy's backoff or mark the peer failed.
     */
    private suspend fun onSessionEnded(peerId: String, reason: String) {
        val delayMs = mutex.withLock {
            val devices = _nearbyDevices.value
            val idx = devices.indexOfFirst { it.id == peerId }
            if (idx == -1 || devices[idx].state == DeviceState.Recovering || devices[idx].state == DeviceState.Error) {
                return
            }
            emitEvent(EventType.SessionEnded, peerId, reason)
            when (val decision = recoveryTracker.onEnded(peerId)) {
                is RecoveryDecision.Retry -> {
                    recoveringPeers.add(peerId)
                    updateDeviceStateLocked(peerId, DeviceState.Recovering)
                    emitEvent(
                        EventType.RecoveryStarted, peerId,
                        "Restart ${decision.attempt}/${recovery.maxAttempts} in ${decision.delayMs} ms"
                    )
                    decision.delayMs
                }
                RecoveryDecision.GiveUp -> {
                    updateDeviceStateLocked(peerId, DeviceState.Error, reason)
                    return
                }
            }
        }
        scheduleRestart(peerId, delayMs)
    }

    private suspend fun scheduleRestart(peerId: String, delayMs: Long) {
        val job = scope.launch {
            delay(delayMs)
            restart(peerId)
        }
        mutex.withLock {
            restartJobs.remove(peerId)?.cancel()
            restartJobs[peerId] = job
        }
    }

    /**
     * Push [peerId] back through discovery → exchange → ranging. A phone is forgotten at the BLE layer
     * so its next advertisement starts a fresh exchange (both ends do this independently, and the
     * identity dedup settles them on one connection). An accessory keeps its BLE link: its session is
     * released, a fresh local config is minted, and init is sent again over the open link, which yields
     * fresh accessory config data and a fresh session on both sides.
     */
    private suspend fun restart(peerId: String) {
        val isAccessory = mutex.withLock {
            restartJobs.remove(peerId)
            if (_nearbyDevices.value.none { it.id == peerId }) return
            exchangedPeers.remove(peerId)
            pendingExchanges.remove(peerId)
            identityKeyToPeer.entries.removeAll { it.value.peerId == peerId }
            // Back to Discovered with a fresh lastSeen: the peer was silent for a while, and the
            // stale purge must give the re-discovery its full window.
            updateDeviceStateLocked(peerId, DeviceState.Discovered, touch = true)
            peerId in accessoryPeers
        }
        if (isAccessory) {
            // Same handshake as a suspension: tell the accessory to stop but keep the link for the
            // re-init. The stop is harmless if the accessory already ended on its side.
            bleManager.retainAccessoryLink(peerId)
            bleManager.sendToPeer(peerId, byteArrayOf(NI_ACCESSORY_STOP))
            multiplatformUwbManager.stopRanging(peerId)
            val config = multiplatformUwbManager.prepareConnectionConfig(peerId, isAccessory = true)
            if (config == null) {
                onRangingError(peerId, "Could not prepare a UWB config for restart")
                return
            }
            mutex.withLock {
                pendingExchanges.add(peerId)
                updateDeviceStateLocked(peerId, DeviceState.ExchangingConfig, touch = true)
            }
            emitEvent(EventType.ConfigExchangeStarted, peerId, "Re-initializing accessory")
            bleManager.connectAndExchangeConfig(peerId, config)
        } else {
            multiplatformUwbManager.stopRanging(peerId)
            bleManager.forgetDevice(peerId)
            emitEvent(EventType.DeviceDiscovered, peerId, "Waiting for re-discovery")
        }
    }

    /** Restart [peerId] now, resetting its attempt count. For the app's own recovery triggers. */
    suspend fun restartPeer(peerId: String) {
        mutex.withLock {
            if (_nearbyDevices.value.none { it.id == peerId }) return
            recoveryTracker.reset(peerId)
            recoveringPeers.add(peerId)
            restartJobs.remove(peerId)?.cancel()
            updateDeviceStateLocked(peerId, DeviceState.Recovering)
            emitEvent(EventType.RecoveryStarted, peerId, "Restart requested")
        }
        restart(peerId)
    }

    /**
     * Restart every peer in [DeviceState.Error]. Meant for the app to call when it knows a failure
     * cause has cleared, e.g. on returning to the foreground (Android ends UWB sessions of
     * backgrounded apps, so retries made while backgrounded just use up the attempts).
     */
    suspend fun restartFailedPeers() {
        val failed = mutex.withLock { _nearbyDevices.value.filter { it.state == DeviceState.Error }.map { it.id } }
        failed.forEach { restartPeer(it) }
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
                // A fresh session starts its silence window now, not from the last result of the
                // session it replaces.
                existingDevices[idx] = existingDevices[idx].copy(
                    state = DeviceState.Ranging,
                    sessionId = remoteConfig.sessionId,
                    channel = remoteConfig.channel,
                    lastSeen = getCurrentTimeMillis(),
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

        if (deviceIndex != -1) {
            if (recoveringPeers.remove(peerId)) recoveryTracker.onRecovered(peerId)
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

    /** Must be called while holding [mutex]. [touch] also refreshes [NearbyDevice.lastSeen]. */
    private fun updateDeviceStateLocked(id: String, state: DeviceState, error: String? = null, touch: Boolean = false) {
        val devices = _nearbyDevices.value.toMutableList()
        val idx = devices.indexOfFirst { it.id == id }
        if (idx != -1) {
            val lastSeen = if (touch) getCurrentTimeMillis() else devices[idx].lastSeen
            devices[idx] = devices[idx].copy(state = state, errorMessage = error, lastSeen = lastSeen)
            _nearbyDevices.value = devices
        }
    }
}
