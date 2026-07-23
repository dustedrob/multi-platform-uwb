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
import java.security.SecureRandom

actual class MultiplatformUwbManager(private val androidUwbManager: UwbManager? = null) {
    private val TAG = "UwbManager"

    private var rangingCallback: ((String, Double, Double?, Double?) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    /**
     * Stored for `expect` parity; unused on Android. androidx.core.uwb takes all ranging parameters
     * up front and generates nothing post-`prepareSession`, so there is no data to send back to a peer
     * (unlike iOS, where NI produces shareable configuration data after the session runs).
     */
    private var sendToPeerCallback: ((String, ByteArray) -> Unit)? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The session scope obtained during [initialize], which provides our local UWB address. */
    //private var sessionScope: UwbClientSessionScope? = null

    /** Active ranging coroutine jobs, keyed by peer ID. Cancel to stop ranging. */
    private val activeJobs = mutableMapOf<String, Job>()

    private val activeSessions = mutableMapOf<String, UwbSessionConfig>()     // config used for rangingwith this peer
    
    private val connectionConfigs = mutableMapOf<String, UwbSessionConfig>()  // config created for connection aka local

    /**
     * Our 8-byte static-STS session key, generated once and reused for the lifetime
     * of this manager so the value advertised over BLE matches the one used at ranging.
     */
    //private var localSessionKey: ByteArray? = null

    /** Default channel and preamble — used when generating local config. */
    private companion object {
        const val DEFAULT_CHANNEL = 9
        const val DEFAULT_PREAMBLE_INDEX = 11
        const val SESSION_KEY_SIZE = 8
    }

    actual suspend fun initialize() {
        if (androidUwbManager == null) {
            errorCallback?.invoke("UWB not supported on this device")
            return
        }
        try {

            //val scope = androidUwbManager.controllerSessionScope()// androidUwbManager.controleeSessionScope()
            //sessionScope = scope
            // need this for ranging wit accessory, can't create later
            //controllerScope = androidUwbManager.controllerSessionScope()

            val capabilities = androidUwbManager.controllerSessionScope().rangingCapabilities
            if (!capabilities.isDistanceSupported) {
                errorCallback?.invoke("UWB distance ranging not supported")
            }
            //Log.d(TAG, "UWB initialized. Local address: ${scope.localAddress}")
        } catch (e: Exception) {
            errorCallback?.invoke("Failed to initialize UWB: ${e.message}")
        }
    }

    actual suspend fun createLocalConfig(peerId: String, isAccessory: Boolean ): UwbSessionConfig? {
        val localScope = if(isAccessory){
            androidUwbManager?.controllerSessionScope()
        } else {
            androidUwbManager?.controleeSessionScope()
        }

        val localAddress = localScope?.localAddress?.address

        // Generate a session ID from our address for deterministic agreement.
        // During config exchange, the initiator's sessionId is used by convention
        // (the peer with the lexicographically smaller address initiates).

        val sessionId:Int? = localAddress?.fold(0) { acc, b -> acc * 31 + (b.toInt() and 0xFF) }

        // Generate the static-STS key lazily and cache it, so the key we send over
        // BLE is the same one we compare/use when ranging starts.
        val key = localSessionKey ?: ByteArray(SESSION_KEY_SIZE)
            .also { SecureRandom().nextBytes(it) }
            .also { localSessionKey = it }

        Log.d(TAG, "phone address is ${localAddress?.toHexString()}")

        val connectionConfig = if(sessionId !=0 ) {
            localScope?.let {
                sessionId?.let { it1 ->
                    UwbSessionConfig(
                        timeStamp = TimeUtils.getMilliseconds(),
                        scope = it,
                        sessionId = it1,
                        channel = DEFAULT_CHANNEL,
                        preambleIndex = DEFAULT_PREAMBLE_INDEX,
                        uwbAddress = localAddress,
                        discoveryToken = null,
                        sessionKey = key,
                    )
                }
            }
        } else {
            null
        }
        connectionConfigs[peerId]= connectionConfig
        return connectionConfig
    }

    actual fun getConnectionConfig[peerId:String) ->UwbSessionConfig? {
        return if(connectioConfigs[peerId != null){
             connectionConfigs[peerId]
        } else {
             null
        }
	}

    actual suspend fun startRanging(peerId: String, remoteConfig: UwbSessionConfig) {

        // Cancel any existing ranging job for this peer
        activeJobs[peerId]?.cancel()

        val job = coroutineScope.launch {
            try {

                // Use the remote peer's UWB address
                val peerAddress = UwbAddress(remoteConfig.uwbAddress)
                val peerDevice = UwbDevice(peerAddress)
                Log.d(TAG, "ranging peer device address is ${agreed.uwbAddress.toHexString()}")

                val rangingParameters: RangingParameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = remoteConfig.sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = remoteConfig.sessionKey,
                    subSessionKeyInfo = null,
                    complexChannel = UwbComplexChannel(
                        channel = remoteConfg.channel,
                        preambleIndex = remoteConfg.preambleIndex
                    ),
                    peerDevices = listOf(peerDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                )

                Log.d(
                    TAG,
                    "Starting ranging with $peerId — session=${remoteConfig.sessionId.toHexString()} ch=${remoteConfig.channel} pai=${remoteConfig.preambleIndex}  address=${remoteConfig.uwbAddress.toHexString()}"
                )
                // if this is an accessory, send the config it should use now, as we have done all the pre-checking
                if(remoteConfig.isAccessoryDevice) {
                    val message = byteArrayOf(ANDROID_ACCESSORY_CONFIGURE_AND_START)+ activeSessions[peerId].toByteArray()
                    Log.d(TAG, "sending config data message to accessory=${message.toHexString()}")
                    activeSessions[peerId].toByteArray().let { sendToPeerCallback?.invoke(peerId, message) }
                }



                rangingParameters.let { ((activeSessions[peerId] as UwbSessionConfig).scope as UwbClientSessionScope).prepareSession(it) }
                    ?.catch { exception ->
                        errorCallback?.invoke("Ranging failed for $peerId: ${exception.message}")
                    }
                    ?.collect { result ->
                        Log.d(TAG, "in collect")
                        when (result) {
                            is RangingResult.RangingResultPosition -> {
                                Log.d(TAG,"Ranging position report")
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
                                Log.d(TAG,"Ranging init")
                            }

                            is RangingResult.RangingResultPeerDisconnected -> {
                                Log.d(TAG,"peer disconnected")
                                errorCallback?.invoke("Peer $peerId disconnected")
                                activeJobs.remove(peerId)
                            }

                            else ->{
                                Log.d(TAG,"unexpected ranging result ${result}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG,"Ranging startup failed, ${e.message}")
                    errorCallback?.invoke("Failed to start ranging with $peerId: ${e.message}")
                }
                Log.d(TAG,"Ranging active (maybe)")
            }
            Log.d(TAG,"Ranging process starting for peer ${peerId}")
            activeJobs[peerId] = job
            activeSessions[peerId] = remoteConfig
        }

    actual suspend fun stopRanging(peerId: String) {
        (activeSessions.scope as UwbSessionConfig).pause()
        activeSessions.remove((peerId))
        activeJobs.remove(peerId)?.let { job ->
            job.cancel()
            Log.d(TAG, "Stopped ranging with $peerId")
        }
    }

    actual fun setRangingCallback(callback: (peerId: String, distance: Double, azimuth: Double?, elevation: Double?) -> Unit) {
        rangingCallback = callback
    }

    actual fun setSendToPeerCallback(callback: (peerId: String, data: ByteArray) -> Unit) {
        sendToPeerCallback = callback
    }

    actual fun setErrorCallback(callback: (error: String) -> Unit) {
        errorCallback = callback
    }

    /** Stop all sessions and clean up resources. */
    actual suspend fun cleanup() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        activeSessions.clear()
        connectionConfigs.clear()
        coroutineScope.cancel()
        }
    }

