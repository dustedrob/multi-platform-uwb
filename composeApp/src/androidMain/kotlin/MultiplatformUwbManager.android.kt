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
                    val capabilities = uwbManager.clientSessionScope()
                    // UWB manager is ready
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
                    val sessionScope = uwbManager.clientSessionScope()
                    activeSessions[peerId] = sessionScope
                    
                    // Create UWB device from peer ID
                    val uwbAddress = UwbAddress(peerId.toByteArray())
                    val uwbDevice = UwbDevice(uwbAddress)
                    
                    // Configure ranging parameters
                    val rangingParameters = RangingParameters(
                        uwbConfigType = RangingParameters.UWB_CONFIG_ID_1,
                        sessionId = peerId.hashCode(),
                        sessionKeyInfo = null,
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
                                        rangingCallback?.invoke(peerId, distance)
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
        activeSessions[peerId]?.let { session ->
            coroutineScope.launch {
                try {
                    session.cancel()
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
}