import androidx.core.uwb.UwbManager
import androidx.core.uwb.RangingCapabilities
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

actual class MultiplatformUwbManager(private val androidUwbManager: UwbManager? = null) {
    private var rangingCallback: ((String, Double) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activeSessions = mutableMapOf<String, UwbClientSessionScope>()

    actual fun initialize() {
        androidUwbManager?.let { uwbManager ->
            coroutineScope.launch {
                try {
                    // Check if UWB is available and get capabilities
                    val clientSessionScope = uwbManager.controleeSessionScope()
                    val capabilities = clientSessionScope.rangingCapabilities
                    
                    if (capabilities.isDistanceSupported) {
                        // UWB manager is ready with distance support
                    } else {
                        errorCallback?.invoke("UWB distance ranging not supported")
                    }
                } catch (e: Exception) {
                    errorCallback?.invoke("Failed to initialize UWB: ${e.message}")
                }
            }
        } ?: run {
            errorCallback?.invoke("UWB not supported on this device")
        }
    }

    actual fun startRanging(peerId: String) {
        androidUwbManager?.let { uwbManager ->
            coroutineScope.launch {
                try {
                    val sessionScope = uwbManager.controleeSessionScope()
                    activeSessions[peerId] = sessionScope
                    
                    // Create UWB device from peer ID
                    val uwbAddress = UwbAddress(peerId.toByteArray())
                    val uwbDevice = UwbDevice(uwbAddress)
                    
                    // Configure ranging parameters
                    val rangingParameters = RangingParameters(
                        uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                        sessionId = peerId.hashCode(),
                        subSessionId = 1,
                        sessionKeyInfo = null,
                        subSessionKeyInfo = null,
                        complexChannel = UwbComplexChannel(channel = 9, preambleIndex = 10),
                        peerDevices = listOf(uwbDevice),
                        updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
                    )
                    
                    // Start ranging and collect results
                    sessionScope.prepareSession(rangingParameters)
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
                                }
                            }
                        }
                } catch (e: Exception) {
                    errorCallback?.invoke("Failed to start ranging with $peerId: ${e.message}")
                }
            }
        }
    }

    actual fun stopRanging(peerId: String) {
        activeSessions[peerId]?.let { _ ->
            coroutineScope.launch {
                try {
                    // Remove the session from active sessions
                    activeSessions.remove(peerId)
                } catch (e: Exception) {
                    errorCallback?.invoke("Failed to stop ranging with $peerId: ${e.message}")
                }
            }
        }
    }

    actual fun setRangingCallback(callback: (peerId: String, distance: Double) -> Unit) {
        rangingCallback = callback
    }

    actual fun setErrorCallback(callback: (error: String) -> Unit) {
        errorCallback = callback
    }
    
    // Cleanup method to stop all sessions and clean up resources
    fun cleanup() {
        activeSessions.clear()
        coroutineScope.cancel()
    }
}