package com.dustedrob.uwb

import android.Manifest
import android.nfc.tech.TagTechnology
import android.os.Build
import android.ranging.RangingData
import android.util.Log
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.raw.RawRangingDevice.UPDATE_RATE_FREQUENT
import android.ranging.raw.RawRangingDevice.UPDATE_RATE_NORMAL
import android.ranging.raw.RawResponderRangingConfig
import android.ranging.uwb.UwbAddress
import android.ranging.uwb.UwbRangingParams
import androidx.annotation.RequiresApi
import java.util.UUID
/*import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
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
import kotlinx.coroutines.launch*/
import java.security.SecureRandom
import androidx.core.content.ContextCompat
import android.provider.Settings
import java.nio.ByteBuffer
import android.content.Context
import android.ranging.uwb.UwbComplexChannel
import android.ranging.uwb.UwbComplexChannel.UWB_CHANNEL_9
import android.ranging.uwb.UwbComplexChannel.UWB_PREAMBLE_CODE_INDEX_11
import androidx.annotation.RequiresPermission

actual class MultiplatformUwbManager(private val context:Context? = null) {
    private val TAG = "UwbManager"

    var rangingManager : RangingManager? = null
    private var rangingCallback: ((String, Double, Double?, Double?) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    /**
     * Stored for `expect` parity; unused on Android. androidx.core.uwb takes all ranging parameters
     * up front and generates nothing post-`prepareSession`, so there is no data to send back to a peer
     * (unlike iOS, where NI produces shareable configuration data after the session runs).
     */
    private var sendToPeerCallback: ((String, ByteArray) -> Unit)? = null
    //private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Active ranging coroutine jobs, keyed by peer ID. Cancel to stop ranging. */
    //private val activeJobs = mutableMapOf<String, Job>()
    private val activeSessions = mutableMapOf<String, RangingSession?>()

    /** Local config we created per peer (our address, session id, key). */
    private val connectionConfigs = mutableMapOf<String, UwbSessionConfig>()

    //private var controllerScope: UwbControllerSessionScope? = null
    //private var controleeScope: UwbControleeSessionScope? = null

    /** Static-STS key, generated once and reused so it is stable across a peer's BLE identities. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private var localSessionKey: ByteArray? = null
    private var activeSession: RangingSession? = null

    private var savedRangingParameters: UwbRangingParams? = null
    //var restarting: Boolean = false
    var peerCountChanged :Boolean = false;
    /** Default channel and preamble — used when generating local config. */
    companion object {
        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        const val DEFAULT_CHANNEL = UWB_CHANNEL_9
        const val DEFAULT_PREAMBLE_INDEX = UWB_PREAMBLE_CODE_INDEX_11
        const val SESSION_KEY_SIZE = 8
    }
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    suspend fun init(){
        initialize()
    }
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    actual suspend fun initialize() {
        /*if (rangingManager == null) {
            rangingManager = context?.getSystemService(RangingManager::class.java)

            errorCallback?.invoke("UWB not supported on this device")
            return
        }*/

        try {
            Log.d(TAG,"uwbmanager init")
            //controleeScope = androidUwbManager.controleeSessionScope()// androidUwbManager.controleeSessionScope()
            //sessionScope = scope
            // need this for ranging wit accessory, can't create later
            //controllerScope = androidUwbManager.controllerSessionScope()

            /*val capabilities = androidUwbManager.controllerSessionScope().rangingCapabilities
            if (!capabilities.isDistanceSupported) {
                errorCallback?.invoke("UWB distance ranging not supported")
            }*/
            //Log.d(TAG, "UWB initialized. Local address: ${scope.localAddress}")
        } catch (e: Exception) {
            errorCallback?.invoke("Failed to initialize UWB: ${e.message}")
        }
    }
    private fun Int.toByteArray(): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES).putInt(this).array()
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    actual fun createConnectionConfig(peerId: String, isAccessory: Boolean ): UwbSessionConfig? {
        // One config per peer: a repeat discovery must not regenerate the session key mid-exchange,
        // or the copy we already advertised over BLE would no longer match what we range with.
        connectionConfigs[peerId]?.let { return it }
        //val localScope = if(isAccessory){
        //    controllerScope
        //} else {
        //    controleeScope
        //}
        //Log.d(TAG, "localScope = $localScope")
        // get the local device uwb HW address
        val localAddress = Settings.Secure.ANDROID_ID.hashCode().toString().slice(IntRange(0,1))   .toByteArray()

        // Generate a session ID from our address for deterministic agreement.
        // During config exchange, the initiator's sessionId is used by convention
        // (the peer with the lexicographically smaller address initiates).

        val sessionId:Int = localAddress.fold(0) { acc, b -> acc * 31 + (b.toInt() and 0xFF) }

        Log.d(TAG,"session id = ${sessionId}")

        // Generate the static-STS key once per manager and reuse it. A phone seen under several
        // randomized BLE addresses would otherwise hand out a different key per identity, and the one
        // the peer keeps might not match the one we range with. One cached key keeps them consistent.
        val key = localSessionKey ?: ByteArray(SESSION_KEY_SIZE)
            .also { SecureRandom().nextBytes(it) }
            .also { localSessionKey = it }

        Log.d(TAG, "phone address is ${localAddress?.toHexString()}")
        Log.d(TAG,"session key is ${localSessionKey}")

        // For peer-to-peer, also advertise our controller-scope address so that after role election
        // the controller can range against the peer's controlee address and vice versa. Accessories
        // don't need it (the phone is always controller and the accessory adopts our params).
        //val controllerAddr = if (isAccessory) null else controllerScope?.localAddress?.address

        val connectionConfig: UwbSessionConfig? = if(sessionId !=null ) {
            UwbSessionConfig(
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
                channel = DEFAULT_CHANNEL,
                preambleIndex = DEFAULT_PREAMBLE_INDEX,
                uwbAddress = localAddress,
                discoveryToken = null,
                sessionKey = key
            )
        } else {
            null
        }
        connectionConfigs[peerId]= connectionConfig as UwbSessionConfig
        return connectionConfig
    }

    actual fun getConnectionConfig(peerId:String):UwbSessionConfig? {
        return if(connectionConfigs[peerId] != null){
             connectionConfigs[peerId]
        } else {
             null
        }
    }

    @RequiresPermission(Manifest.permission.RANGING)
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    actual suspend fun  startRanging(peerId: String, remoteConfig: UwbSessionConfig){

        val localConfig = getConnectionConfig(peerId)

        val localAddress: UwbAddress? =
            localConfig?.uwbAddress?.let { UwbAddress.fromBytes(it) }  // set from device id hashed in init

        val peerAddress = UwbAddress.fromBytes(remoteConfig.uwbAddress)
        Log.d(TAG,"peer uwb address is ${remoteConfig.uwbAddress.toHexString()}")

        val amController = remoteConfig.isAccessoryDevice || activeSession !=null || localConfig == null ||
                localConfig.ownsSessionOver(remoteConfig)

        val paramsConfig = if (amController) (localConfig ?: remoteConfig) else remoteConfig
        
        // Build params for the first device
        val initialUwbParams = paramsConfig.sessionKey?.let {
            UwbRangingParams.Builder(
                localConfig?.sessionId !!,
                UwbRangingParams.CONFIG_UNICAST_DS_TWR,
                localAddress!!,
                peerAddress
            )
            .setComplexChannel(
                UwbComplexChannel.Builder(
                )
                    .setChannel(DEFAULT_CHANNEL)
                    .setPreambleIndex((DEFAULT_PREAMBLE_INDEX))
                    .build()
            )
            .setRangingUpdateRate(UPDATE_RATE_NORMAL)
            .setSessionKeyInfo(it)
            .build()
        }
        Log.d(TAG, "InitUwpParms is null ${initialUwbParams == null}")

        val standardRangingDevice = RangingDevice.Builder()
            .build()

        val initialRawDevice = RawRangingDevice.Builder()
            .setRangingDevice((standardRangingDevice))
            .setUwbRangingParams(initialUwbParams!!)
            .build()

        var preference: RangingPreference
        if(amController) {        // Package into the Initiator Configuration

           val initiatorConfig = RawInitiatorRangingConfig.Builder()
                .addRawRangingDevice(initialRawDevice)
                .build()

            preference = RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_INITIATOR,

                initiatorConfig
            )
            .build()
        } else {
            val responderConfig = RawResponderRangingConfig.Builder()
                .setRawRangingDevice(initialRawDevice)
                .build()

            preference = RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_RESPONDER,
                responderConfig
            ).build()
        }

        // Handle lifecycle callbacks
        val callback = object : RangingSession.Callback {
            override fun onClosed(p0: Int) {
                TODO("Not yet implemented")
            }

            override fun onOpenFailed(p0: Int) {
                TODO("Not yet implemented")
            }

            override fun onOpened() {
                TODO("Not yet implemented")
            }

            override fun onResults(
                device: RangingDevice,
                data: RangingData
            ) {
                Log.d(TAG, "Ranging position report ${device.uuid.toString()}")
                val distance = data.distance
                if (distance != null) {
                    rangingCallback?.invoke(
                        device.uuid.toString(),
                        distance.measurement,
                        data.azimuth?.measurement,
                        data.elevation?.measurement
                    )
                }
            }

            override fun onStarted(p0: RangingDevice, p1: Int) {
                activeSessions[p0.uuid.toString()]=activeSession
                Log.d(TAG, "Ranging init ${peerId}")
                }

            override fun onStopped(p0: RangingDevice, p1: Int) {
                activeSessions[p0.uuid.toString()]=null
            }
        }

        if (remoteConfig.isAccessoryDevice) {
            val message =
                getConnectionConfig(peerId)?.let { byteArrayOf(ANDROID_ACCESSORY_CONFIGURE_AND_START) + it.toByteArray() }
            Log.d(TAG, "sending config data message to accessory=${message?.toHexString()}")
            message?.let { sendToPeerCallback?.invoke(peerId, it) }
        }
        if(activeSession == null) {
            val executor = ContextCompat.getMainExecutor(context!!)


            val session = rangingManager?.createRangingSession(executor, callback)
            activeSessions[peerId] = session
            activeSession = session
            session?.start(preference)
            Log.d(TAG, "Starting ranging with ${peerId}")
        } else {
           //activeSession!!.addDeviceToRangingSession()
            Log.d(TAG, "Added ranging with ${peerId}")
        }
}

    /*actual suspend fun startRangingold(peerId: String, remoteConfig: UwbSessionConfig) {

        // Cancel any existing ranging job for this peer
        activeJobs[peerId]?.cancel()

        val localConfig = getConnectionConfig(peerId)


        // Elect roles. Two controlee scopes set up but never range, so exactly one side must be
        // controller. The session owner (smaller controlee UWB address, stable across BLE
        // identities) is the controller; an accessory always leaves the phone as controller.
        val isAccessory = remoteConfig.isAccessoryDevice
        val amController = isAccessory || localConfig == null ||
                localConfig.ownsSessionOver(remoteConfig)

        // Use the pre-created scope for our role, so we range with an address the peer already
        // received (never mint a new scope/address after the exchange).
        val scope = if (amController) controllerScope else controleeScope

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

        Log.d(
            TAG,
            "Starting ranging with $peerId — session=${paramsConfig.sessionId.toHexString()} ch=${paramsConfig.channel} pai=${paramsConfig.preambleIndex}  peer=${peerAddressBytes.toHexString()} amController=$amController"
        )
        // if this is an accessory, send the config it should use now, as we have done all the pre-checking
        if (remoteConfig.isAccessoryDevice) {
            val message =
                getConnectionConfig(peerId)?.let { byteArrayOf(ANDROID_ACCESSORY_CONFIGURE_AND_START) + it.toByteArray() }
            Log.d(TAG, "sending config data message to accessory=${message?.toHexString()}")
            message?.let { sendToPeerCallback?.invoke(peerId, it) }
        }
        val peerDevice = UwbDevice(UwbAddress(peerAddressBytes))
        Log.d(
            TAG,
            "ranging peer device address is ${peerAddressBytes.toHexString()} (amController=$amController)"
        )

        if (savedRangingParameters == null) {
            savedRangingParameters =
                RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = paramsConfig.sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = paramsConfig.sessionKey,
                    subSessionKeyInfo = null,
                    complexChannel = UwbComplexChannel(
                        channel = paramsConfig.channel,
                        preambleIndex = paramsConfig.preambleIndex
                    ),
                    peerDevices = mutableListOf(peerDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                )
        } else {
            peerCountChanged = true
            val tempList = activeJobs
            tempList.forEach { (peer, job) -> job.cancel()
                activeJobs.remove(peer)
                if (remoteConfig.isAccessoryDevice) {
                    //sendToPeerCallback?.invoke(peer, byteArrayOf(NI_ACCESSORY_STOP))
                    // sleep for 50ms??
                    val message =
                        getConnectionConfig(peer)?.let { byteArrayOf(ANDROID_ACCESSORY_CONFIGURE_AND_START) + it.toByteArray() }
                    Log.d(TAG, "sending config data message to accessory=${message?.toHexString()}")
                    message?.let { sendToPeerCallback?.invoke(peer, it) }
                }
            }
            val previousDevices: MutableList<UwbDevice> = savedRangingParameters!!.peerDevices.toMutableList()
            savedRangingParameters = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                sessionId = savedRangingParameters!!.sessionId,
                subSessionId = 0,
                sessionKeyInfo = savedRangingParameters!!.sessionKeyInfo,
                subSessionKeyInfo = null,
                complexChannel = savedRangingParameters!!.complexChannel,
                peerDevices = previousDevices.plus(peerDevice),
                updateRateType = savedRangingParameters!!.updateRateType
            )
        }
        var job : Job? = null
        do {
            restarting = false
            job = coroutineScope.launch {

                Log.d(TAG, "job starting")
                if (scope == null) {
                    errorCallback?.invoke("No UWB session scope for $peerId; initialize() must run first")
                    activeJobs.remove(peerId)
                    return@launch
                }

                try {
                    scope.prepareSession(savedRangingParameters!!)
                        .catch { exception ->
                            errorCallback?.invoke("Ranging failed for $peerId: ${exception.message}")
                        }
                        .collect { result ->
                            Log.d(TAG, "in collect")
                            when (result) {
                                is RangingResult.RangingResultPosition -> {
                                    Log.d(TAG, "Ranging position report ${peerId}")
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

                                is RangingResult.RangingResultInitialized -> {
                                    Log.d(TAG, "Ranging init ${peerId}")
                                }

                                is RangingResult.RangingResultPeerDisconnected -> {
                                    Log.d(TAG, "peer disconnected ${peerId}")
                                    errorCallback?.invoke("Peer $peerId disconnected")
                                    activeJobs.remove(peerId)
                                }

                                else -> {
                                    Log.d(TAG, "unexpected ranging result ${result}")
                                }
                            }
                        }
                    Log.d(TAG, "Ranging active (maybe) 2")
                } catch (e: Exception) {

                    if(peerCountChanged && e.message?.contains("cancelled") == true){
                        Log.d(TAG, "Ranging startup restarting, ${e.message} ")
                        peerCountChanged = false
                        restarting = true
                    } else {
                        Log.d(TAG, "Ranging startup failed, ${e.message} ")
                        errorCallback?.invoke("Failed to start ranging with $peerId: ${e.message}")
                    }
                }
                Log.d(TAG, "Ranging active (maybe)")
            }
        } while (restarting)
        Log.d(TAG, "Ranging process starting for peer ${savedRangingParameters?.peerDevices.toString()}")
        activeJobs[peerId] = job
    }

    */
    actual suspend fun stopRanging(peerId: String) {
        //activeSessions[peerId]
        /*activeJobs.remove(peerId)?.let { job ->
            job.cancel()
            Log.d(TAG, "Stopped ranging with $peerId")
        }*/
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
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    actual suspend fun cleanup() {
        //activeJobs.values.forEach { it.cancel() }
        //activeJobs.clear()
        connectionConfigs.clear()
        localSessionKey = null
        //coroutineScope.cancel()
    }
}

