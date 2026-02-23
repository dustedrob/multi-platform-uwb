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

actual class MultiplatformUwbManager(private val androidUwbManager: UwbManager? = null) {
    private val TAG = "UwbManager"

    private var rangingCallback: ((String, Double) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The session scope obtained during [initialize], which provides our local UWB address. */
    private var sessionScope: UwbClientSessionScope? = null

    /** Active ranging coroutine jobs, keyed by peer ID. Cancel to stop ranging. */
    private val activeJobs = mutableMapOf<String, Job>()

    /** Default channel and preamble — used when generating local config. */
    private companion object {
        const val DEFAULT_CHANNEL = 9
        const val DEFAULT_PREAMBLE_INDEX = 10
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

        return UwbSessionConfig(
            sessionId = sessionId,
            channel = DEFAULT_CHANNEL,
            preambleIndex = DEFAULT_PREAMBLE_INDEX,
            uwbAddress = localAddress,
            discoveryToken = null,
        )
    }

    actual fun startRanging(peerId: String, remoteConfig: UwbSessionConfig) {
        val scope = sessionScope
        if (scope == null) {
            errorCallback?.invoke("UWB not initialized. Call initialize() first.")
            return
        }

        // Cancel any existing ranging job for this peer
        activeJobs[peerId]?.cancel()

        val job = coroutineScope.launch {
            try {
                // Use the remote peer's UWB address
                val peerAddress = UwbAddress(remoteConfig.uwbAddress)
                val peerDevice = UwbDevice(peerAddress)

                // Deterministic session ID: use the smaller of the two proposed IDs
                // so both peers agree without extra negotiation.
                val localConfig = getLocalConfig()
                val agreedSessionId = if (localConfig != null) {
                    minOf(localConfig.sessionId, remoteConfig.sessionId)
                } else {
                    remoteConfig.sessionId
                }

                val rangingParameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = agreedSessionId,
                    subSessionId = 0,
                    sessionKeyInfo = null,
                    subSessionKeyInfo = null,
                    complexChannel = UwbComplexChannel(
                        channel = remoteConfig.channel,
                        preambleIndex = remoteConfig.preambleIndex
                    ),
                    peerDevices = listOf(peerDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                )

                Log.d(TAG, "Starting ranging with $peerId — session=$agreedSessionId ch=${remoteConfig.channel}")

                scope.prepareSession(rangingParameters)
                    .catch { exception ->
                        errorCallback?.invoke("Ranging failed for $peerId: ${exception.message}")
                    }
                    .collect { result ->
                        when (result) {
                            is RangingResult.RangingResultPosition -> {
                                val distance = result.position.distance?.value
                                if (distance != null) {
                                    rangingCallback?.invoke(peerId, distance.toDouble())
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

    actual fun setRangingCallback(callback: (peerId: String, distance: Double) -> Unit) {
        rangingCallback = callback
    }

    actual fun setErrorCallback(callback: (error: String) -> Unit) {
        errorCallback = callback
    }

    /** Stop all sessions and clean up resources. */
    actual fun cleanup() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        sessionScope = null
        coroutineScope.cancel()
    }
}
