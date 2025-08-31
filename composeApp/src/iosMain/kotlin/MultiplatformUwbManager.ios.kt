import platform.NearbyInteraction.*
import platform.Foundation.*
import platform.darwin.NSObject
import kotlinx.cinterop.*

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
            val delegate = SessionDelegate()
            session.delegate = delegate
        }
    }
    
    private inner class SessionDelegate : NSObject(), NISessionDelegateProtocol {
        override fun session(session: NISession, didUpdateNearbyObjects: List<*>) {
            didUpdateNearbyObjects.forEach { obj ->
                if (obj is NINearbyObject) {
                    val distance = obj.distance.toDouble()
                    val peerId = activePeers.entries.find { it.value == obj.discoveryToken }?.key ?: "unknown"
                    rangingCallback?.invoke(peerId, distance)
                }
            }
        }
        
        override fun session(session: NISession, didRemoveNearbyObjects: List<*>, withReason: NINearbyObjectRemovalReason) {
            didRemoveNearbyObjects.forEach { obj ->
                if (obj is NINearbyObject) {
                    val peerId = activePeers.entries.find { it.value == obj.discoveryToken }?.key
                    peerId?.let { activePeers.remove(it) }
                }
            }
        }
        
        override fun session(session: NISession, didInvalidateWithError: NSError) {
            val errorMessage = didInvalidateWithError.localizedDescription
            errorCallback?.invoke("NI Session error: $errorMessage")
            
            // Clear active peers and reset session
            activePeers.clear()
            niSession = null
        }
        
        override fun session(session: NISession, didGenerateShareableConfigurationData: NSData, forObject: NINearbyObject) {
            // Handle shareable configuration data if needed
        }
        
        override fun session(session: NISession, didUpdateAlgorithmConvergence: NIAlgorithmConvergence, forObject: NINearbyObject?) {
            // Handle algorithm convergence updates if needed
        }
        
        override fun sessionDidStartRunning(session: NISession) {
            // Session started successfully
        }
        
        override fun sessionWasSuspended(session: NISession) {
            errorCallback?.invoke("NI Session was suspended")
        }
        
        override fun sessionSuspensionEnded(session: NISession) {
            // Session resumed, can continue ranging
        }
    }

    actual fun startRanging(peerId: String) {
        niSession?.let { session ->
            // Store the peer for tracking
            val token = createDiscoveryTokenForPeer(peerId)
            activePeers[peerId] = token
            
            // Start the session with basic configuration
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

    private fun createDiscoveryTokenForPeer(@Suppress("UNUSED_PARAMETER") peerId: String): NIDiscoveryToken {
        // In a real implementation, discovery tokens would be exchanged via BLE
        // NIDiscoveryToken should be obtained from the peer device
        // Note: This needs to be replaced with actual token exchange logic
        return NIDiscoveryToken()
    }
}