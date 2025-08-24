import platform.NearbyInteraction.*
import platform.Foundation.*
import kotlinx.cinterop.*

actual class MultiplatformUwbManager {
    private var niSession: NISession? = null
    private var rangingCallback: ((String, Double) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var activePeers = mutableMapOf<String, NIDiscoveryToken>()

    actual fun initialize() {
        if (NISession.isSupported()) {
            niSession = NISession()
            setupSession()
        } else {
            errorCallback?.invoke("NearbyInteraction not supported on this device")
        }
    }

    private fun setupSession() {
        niSession?.let { session ->
            session.delegate = object : NSObject(), NISessionDelegateProtocol {
                override fun session(session: NISession, didUpdateNearbyObjects objects: List<*>) {
                    objects.forEach { obj ->
                        if (obj is NINearbyObject) {
                            val distance = obj.distance?.doubleValue
                            val peerId = obj.discoveryToken.toString()
                            if (distance != null) {
                                rangingCallback?.invoke(peerId, distance)
                            }
                        }
                    }
                }

                override fun session(session: NISession, didRemoveNearbyObjects objects: List<*>) {
                    // Handle removed objects
                }

                override fun session(session: NISession, didFailWithError error: NSError) {
                    errorCallback?.invoke("NI Session failed: ${error.localizedDescription}")
                }
            }
        }
    }

    actual fun startRanging(peerId: String) {
        niSession?.let { session ->
            // In a real implementation, you'd need to get the discovery token from the peer
            // This would typically come from BLE exchange
            // For now, we'll simulate this
            val token = createDiscoveryTokenForPeer(peerId)
            activePeers[peerId] = token
            
            val config = NIConfiguration()
            session.runWithConfiguration(config)
        }
    }

    actual fun stopRanging(peerId: String) {
        activePeers.remove(peerId)
        if (activePeers.isEmpty()) {
            niSession?.pause()
        }
    }

    actual fun setRangingCallback(callback: (peerId: String, distance: Double) -> Unit) {
        rangingCallback = callback
    }

    actual fun setErrorCallback(callback: (error: String) -> Unit) {
        errorCallback = callback
    }

    private fun createDiscoveryTokenForPeer(peerId: String): NIDiscoveryToken {
        // In a real implementation, this would be exchanged via BLE
        // For now, create a mock token
        return NIDiscoveryToken()
    }
}