package com.dustedrob.uwb

import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

actual class MultiplatformUwbManager(private val androidUwbManager: UwbManager? = null) {
    private val TAG = "UwbManager"

    private var rangingCallback: ((String, Double, Double?, Double?) -> Unit)? = null
    private var errorCallback: ((String?, String) -> Unit)? = null

    /**
     * Stored for `expect` parity; on Android only the accessory path uses it, to push our config to
     * the accessory before the session starts (androidx.core.uwb generates nothing post-`prepareSession`,
     * unlike iOS where NI produces shareable configuration data after the session runs).
     */
    private var sendToPeerCallback: ((String, ByteArray) -> Unit)? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Active ranging coroutine jobs, keyed by peer ID. Cancel to stop ranging. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Local config we created per peer (our addresses, session key). */
    private val connectionConfigs = ConcurrentHashMap<String, UwbSessionConfig>()

    /**
     * One pair of session scopes per peer. androidx.core.uwb scopes are single-use: a scope's
     * `prepareSession` can only be collected once, and its local address is retired when that session
     * ends. Sharing one controller and one controlee scope across peers therefore caps us at one
     * session per role and poisons the scope after any stop. Both roles are allocated up front because
     * the peer needs both of our addresses before either side knows who will be controller; the scope
     * we don't end up using is just dropped.
     */
    private class PeerScopes(
        val controller: UwbControllerSessionScope,
        val controlee: UwbControleeSessionScope?,
    )

    private val peerScopes = ConcurrentHashMap<String, PeerScopes>()

    /** Static-STS key, generated once and reused so it is stable across a peer's BLE identities. */
    private var localSessionKey: ByteArray? = null

    /** Default channel and preamble — used when generating local config. */
    companion object {
        const val DEFAULT_CHANNEL = 9
        const val DEFAULT_PREAMBLE_INDEX = 11
        const val SESSION_KEY_SIZE = 8
    }
    suspend fun init(){
        initialize()
    }
    actual suspend fun initialize() {
        if (androidUwbManager == null) {
            errorCallback?.invoke(null, "UWB not supported on this device")
            return
        }
        try {
            Log.d(TAG,"uwbmanager init")
            // Scopes are now minted per peer in createConnectionConfig; here we only sanity-check the
            // radio. The probe scope is discarded (its address is never advertised).
            val capabilities = androidUwbManager.controllerSessionScope().rangingCapabilities
            if (!capabilities.isDistanceSupported) {
                errorCallback?.invoke(null, "UWB distance ranging not supported")
            }
        } catch (e: Exception) {
            errorCallback?.invoke(null, "Failed to initialize UWB: ${e.message}")
        }
    }

    actual fun createConnectionConfig(peerId: String, isAccessory: Boolean ): UwbSessionConfig? {
        // One config per peer: a repeat discovery must not regenerate the session key mid-exchange,
        // or the copy we already advertised over BLE would no longer match what we range with.
        connectionConfigs[peerId]?.let { return it }
        val manager = androidUwbManager ?: return null

        // Fresh scopes for this peer (see PeerScopes). Scope creation is a short GMS round trip
        // (suspend); this is called from BLE binder-thread callbacks, one of which (the GATT read)
        // has to answer in-line with the config, so block here rather than restructure the BLE
        // layer around a callback. It can still throw when the radio is off; report and bail rather
        // than crash the callback.
        val scopes = try {
            runBlocking {
                PeerScopes(
                    controller = manager.controllerSessionScope(),
                    controlee = if (isAccessory) null else manager.controleeSessionScope(),
                )
            }
        } catch (e: Exception) {
            errorCallback?.invoke(peerId, "Failed to create UWB session scope: ${e.message}")
            return null
        }
        peerScopes[peerId] = scopes

        // The address we advertise as "ours": the controlee address for P2P (the peer's controller
        // ranges against it), the controller address for accessories (the phone is always controller).
        val localAddress = (scopes.controlee ?: scopes.controller).localAddress.address

        // For P2P the real session id is derived per pair in startRanging once roles are known; this
        // one is only used by accessories, which adopt our config verbatim.
        val sessionId: Int = UwbSessionConfig.sessionIdFor(scopes.controller.localAddress.address, localAddress)

        // Generate the static-STS key once per manager and reuse it. A phone seen under several
        // randomized BLE addresses would otherwise hand out a different key per identity, and the one
        // the peer keeps might not match the one we range with. One cached key keeps them consistent.
        // It also doubles as our stable identity for the peer's duplicate-BLE-identity dedup.
        val key = localSessionKey ?: ByteArray(SESSION_KEY_SIZE)
            .also { SecureRandom().nextBytes(it) }
            .also { localSessionKey = it }

        Log.d(TAG, "phone address for $peerId is ${localAddress.toHexString()}")

        // For peer-to-peer, also advertise our controller-scope address so that after role election
        // the controller can range against the peer's controlee address and vice versa. Accessories
        // don't need it (the phone is always controller and the accessory adopts our params).
        val controllerAddr = if (isAccessory) null else scopes.controller.localAddress.address

        val connectionConfig = UwbSessionConfig(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            channel = DEFAULT_CHANNEL,
            preambleIndex = DEFAULT_PREAMBLE_INDEX,
            uwbAddress = localAddress,
            discoveryToken = null,
            sessionKey = key,
            controllerAddress = controllerAddr,
        )
        connectionConfigs[peerId] = connectionConfig
        return connectionConfig
    }

    actual fun getConnectionConfig(peerId:String):UwbSessionConfig? = connectionConfigs[peerId]

    actual suspend fun startRanging(peerId: String, remoteConfig: UwbSessionConfig) {

        // Cancel any existing ranging job for this peer
        activeJobs[peerId]?.cancel()

        val localConfig = getConnectionConfig(peerId)
        val scopes = peerScopes[peerId]

        val job = coroutineScope.launch {
            try {
                // Elect roles. Two controlee scopes set up but never range, so exactly one side must be
                // controller. The session owner (smaller controlee UWB address, stable across BLE
                // identities) is the controller; an accessory always leaves the phone as controller.
                val isAccessory = remoteConfig.isAccessoryDevice
                val amController = isAccessory || localConfig == null ||
                        localConfig.ownsSessionOver(remoteConfig)

                // Use this peer's pre-created scope for our role, so we range with an address the
                // peer already received (never mint a new scope/address after the exchange).
                val scope: UwbClientSessionScope? = if (amController) scopes?.controller else scopes?.controlee
                if (scope == null) {
                    errorCallback?.invoke(peerId, "No UWB session scope for $peerId; createConnectionConfig must run first")
                    releasePeer(peerId)
                    return@launch
                }

                // Range against the peer's opposite-role address: the controller talks to the peer's
                // controlee address ([uwbAddress]); the controlee talks to the peer's controller
                // address ([controllerAddress]).
                val peerAddressBytes = when {
                    isAccessory -> remoteConfig.uwbAddress
                    amController -> remoteConfig.uwbAddress
                    else -> remoteConfig.controllerAddress ?: remoteConfig.uwbAddress
                }

                // Session parameters come from the controller (owner); the controlee adopts them.
                val paramsConfig = if (amController) (localConfig ?: remoteConfig) else remoteConfig

                // One session per peer needs one id per pair, agreed by both ends without another
                // round trip. Accessories adopt the id we already sent them.
                val sessionId = when {
                    isAccessory -> paramsConfig.sessionId
                    amController -> UwbSessionConfig.sessionIdFor(scope.localAddress.address, peerAddressBytes)
                    else -> UwbSessionConfig.sessionIdFor(peerAddressBytes, scope.localAddress.address)
                }

                val peerDevice = UwbDevice(UwbAddress(peerAddressBytes))
                Log.d(TAG, "ranging peer device address is ${peerAddressBytes.toHexString()} (amController=$amController)")

                val rangingParameters: RangingParameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = paramsConfig.sessionKey,
                    subSessionKeyInfo = null,
                    complexChannel = UwbComplexChannel(
                        channel = paramsConfig.channel,
                        preambleIndex = paramsConfig.preambleIndex
                    ),
                    peerDevices = listOf(peerDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                )

                Log.d(
                    TAG,
                    "Starting ranging with $peerId — session=${sessionId.toHexString()} ch=${paramsConfig.channel} pai=${paramsConfig.preambleIndex}  local=${scope.localAddress.address.toHexString()} peer=${peerAddressBytes.toHexString()} amController=$amController"
                )
                // if this is an accessory, send the config it should use now, as we have done all the pre-checking
                if(remoteConfig.isAccessoryDevice) {
                    val message = getConnectionConfig(peerId)?.let { byteArrayOf(ANDROID_ACCESSORY_CONFIGURE_AND_START)+ it.toByteArray() }
                    Log.d(TAG, "sending config data message to accessory=${message?.toHexString()}")
                    message?.let { sendToPeerCallback?.invoke(peerId, it) }
                }

                scope.prepareSession(rangingParameters)
                    .catch { exception ->
                        errorCallback?.invoke(peerId, "Ranging failed for $peerId: ${exception.message}")
                        releasePeer(peerId)
                    }
                    .collect { result ->
                        when (result) {
                            is RangingResult.RangingResultPosition -> {
                                Log.d(TAG,"Ranging position report for $peerId")
                                val distance = result.position.distance?.value
                                if (distance != null) {
                                    rangingCallback?.invoke(
                                        peerId,
                                        distance.toDouble(),
                                        result.position.azimuth?.value?.toDouble(),
                                        result.position.elevation?.value?.toDouble()
                                    )
                                }
                            }

                            is RangingResult.RangingResultInitialized ->{
                                Log.d(TAG,"Ranging init for $peerId")
                            }

                            is RangingResult.RangingResultPeerDisconnected -> {
                                Log.d(TAG,"peer disconnected ${peerId}")
                                errorCallback?.invoke(peerId, "Peer $peerId disconnected")
                                // The scope's address is retired with the session; a re-discovery
                                // must mint a fresh scope and config.
                                releasePeer(peerId)
                            }

                            else ->{
                                Log.d(TAG,"unexpected ranging result ${result}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG,"Ranging startup failed, ${e.message}")
                    errorCallback?.invoke(peerId, "Failed to start ranging with $peerId: ${e.message}")
                    releasePeer(peerId)
                }
            }
            Log.d(TAG,"Ranging process starting for peer ${peerId}")
            activeJobs[peerId] = job
        }

    actual suspend fun stopRanging(peerId: String) {
        activeJobs.remove(peerId)?.let { job ->
            job.cancel()
            Log.d(TAG, "Stopped ranging with $peerId")
        }
        peerScopes.remove(peerId)
        connectionConfigs.remove(peerId)
    }

    /**
     * Forget a peer whose session has ended or failed. Its scope (and address) is spent, so the next
     * discovery of that peer starts over with a fresh scope and config. The job is left to finish
     * on its own (this is called from inside it).
     */
    private fun releasePeer(peerId: String) {
        activeJobs.remove(peerId)
        peerScopes.remove(peerId)
        connectionConfigs.remove(peerId)
    }

    actual fun setRangingCallback(callback: (peerId: String, distance: Double, azimuth: Double?, elevation: Double?) -> Unit) {
        rangingCallback = callback
    }

    actual fun setSendToPeerCallback(callback: (peerId: String, data: ByteArray) -> Unit) {
        sendToPeerCallback = callback
    }

    actual fun setErrorCallback(callback: (peerId: String?, error: String) -> Unit) {
        errorCallback = callback
    }

    /** Stop all sessions and clean up resources. */
    actual suspend fun cleanup() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        peerScopes.clear()
        connectionConfigs.clear()
        localSessionKey = null
        coroutineScope.cancel()
    }
}
