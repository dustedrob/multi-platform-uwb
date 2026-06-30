package com.dustedrob.uwb

import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
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
    private var sessionScope: UwbClientSessionScope? = null

    /** Active ranging coroutine jobs, keyed by peer ID. Cancel to stop ranging. */
    private val activeJobs = mutableMapOf<String, Job>()

    /**
     * Our 8-byte static-STS session key, generated once and reused for the lifetime
     * of this manager so the value advertised over BLE matches the one used at ranging.
     */
    private var localSessionKey: ByteArray? = null

    /** Default channel and preamble — used when generating local config. */
    private companion object {
        const val DEFAULT_CHANNEL = 9
        const val DEFAULT_PREAMBLE_INDEX = 10
        const val SESSION_KEY_SIZE = 8
    }

    actual suspend fun initialize() {
        if (androidUwbManager == null) {
            errorCallback?.invoke("UWB not supported on this device")
            return
        }
        try {
            val scope = androidUwbManager.controleeSessionScope()
            sessionScope = scope

            val capabilities = scope.rangingCapabilities
            if (!capabilities.isDistanceSupported) {
                errorCallback?.invoke("UWB distance ranging not supported")
            }
            Log.d(TAG, "UWB initialized. Local address: ${scope.localAddress}")
        } catch (e: Exception) {
            errorCallback?.invoke("Failed to initialize UWB: ${e.message}")
        }
    }

    actual fun getLocalConfig(): UwbSessionConfig? {
        val scope = sessionScope ?: return null
        val localAddress = scope.localAddress.address

        // Generate a session ID from our address for deterministic agreement.
        // During config exchange, the initiator's sessionId is used by convention
        // (the peer with the lexicographically smaller address initiates).
        val sessionId = localAddress.fold(0) { acc, b -> acc * 31 + (b.toInt() and 0xFF) }

        // Generate the static-STS key lazily and cache it, so the key we send over
        // BLE is the same one we compare/use when ranging starts.
        val key = localSessionKey ?: ByteArray(SESSION_KEY_SIZE)
            .also { SecureRandom().nextBytes(it) }
            .also { localSessionKey = it }

        return UwbSessionConfig(
            sessionId = sessionId,
            channel = DEFAULT_CHANNEL,
            preambleIndex = DEFAULT_PREAMBLE_INDEX,
            uwbAddress = localAddress,
            discoveryToken = null,
            sessionKey = key,
        )
    }

    actual fun startRanging(peerId: String, remoteConfig: UwbSessionConfig) {
        val scope = sessionScope
        if (scope == null) {
            errorCallback?.invoke("UWB not initialized. Call initialize() first.")
            return
        }

        if (remoteConfig.accessoryData != null) {
            // Host -> accessory ranging. androidx.core.uwb needs explicit FiRa parameters; mapping the
            // accessory's configuration blob into RangingParameters is vendor-specific and hardware-
            // dependent, so it is not implemented here (the iOS NI accessory path is the focus).
            errorCallback?.invoke("Accessory ranging not yet supported on Android for $peerId")
            return
        }

        // Cancel any existing ranging job for this peer
        activeJobs[peerId]?.cancel()

        val job = coroutineScope.launch {
            try {
                // Use the remote peer's UWB address
                val peerAddress = UwbAddress(remoteConfig.uwbAddress)
                val peerDevice = UwbDevice(peerAddress)

                // Deterministic agreement without a handshake: the peer with the
                // lexicographically smaller UWB address is the initiator, and both
                // peers adopt its full parameter set (session ID, channel, preamble,
                // and static-STS key). Both ends hold both configs after the BLE
                // exchange, so they independently compute the same values.
                val localConfig = getLocalConfig()
                val agreed = if (localConfig != null &&
                    compareAddresses(localConfig.uwbAddress, remoteConfig.uwbAddress) <= 0
                ) {
                    localConfig
                } else {
                    remoteConfig
                }

                val sessionKey = agreed.sessionKey
                if (sessionKey == null || sessionKey.size != SESSION_KEY_SIZE) {
                    errorCallback?.invoke(
                        "Cannot range with $peerId: missing or invalid session key " +
                                "(static STS requires $SESSION_KEY_SIZE bytes)"
                    )
                    activeJobs.remove(peerId)
                    return@launch
                }

                val rangingParameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = agreed.sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = sessionKey,
                    subSessionKeyInfo = null,
                    complexChannel = UwbComplexChannel(
                        channel = agreed.channel,
                        preambleIndex = agreed.preambleIndex
                    ),
                    peerDevices = listOf(peerDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                )

                Log.d(
                    TAG,
                    "Starting ranging with $peerId — session=${agreed.sessionId.toHexString()} ch=${agreed.channel}"
                )

                scope.prepareSession(rangingParameters)
                    .catch { exception ->
                        errorCallback?.invoke("Ranging failed for $peerId: ${exception.message}")
                    }
                    .collect { result ->
                        when (result) {
                            is RangingResult.RangingResultPosition -> {
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

                            is RangingResult.RangingResultPeerDisconnected -> {
                                errorCallback?.invoke("Peer $peerId disconnected")
                                activeJobs.remove(peerId)
                            }
                        }
                    }
            } catch (e: Exception) {
                errorCallback?.invoke("Failed to start ranging with $peerId: ${e.message}")
            }
        }

        activeJobs[peerId] = job
    }

    actual fun stopRanging(peerId: String) {
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

    /**
     * Compare two UWB addresses lexicographically (unsigned, byte by byte).
     * Returns a negative value if [a] sorts before [b], positive if after, 0 if equal.
     */
    private fun compareAddresses(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    /** Stop all sessions and clean up resources. */
    actual fun cleanup() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        sessionScope = null
        coroutineScope.cancel()
    }
}
