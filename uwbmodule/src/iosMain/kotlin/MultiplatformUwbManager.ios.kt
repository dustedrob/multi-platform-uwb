package com.dustedrob.uwb

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSLog
import platform.NearbyInteraction.NIAlgorithmConvergence
import platform.NearbyInteraction.NIDiscoveryToken
import platform.NearbyInteraction.NINearbyObject
import platform.NearbyInteraction.NINearbyObjectRemovalReason
import platform.NearbyInteraction.NINearbyPeerConfiguration
import platform.NearbyInteraction.NISession
import platform.NearbyInteraction.NISessionDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
actual class MultiplatformUwbManager {
    private var niSession: NISession? = null
    private var rangingCallback: ((String, Double) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    /** Maps peer ID → the token we received from that peer. */
    private val activePeers = mutableMapOf<String, NIDiscoveryToken>()

    /** Our local discovery token, available after session creation. */
    private var localDiscoveryToken: NIDiscoveryToken? = null

    /** Strong reference to delegate to prevent GC. */
    private var sessionDelegate: SessionDelegate? = null

    actual suspend fun initialize() {
        if (!NISession.isSupported()) {
            errorCallback?.invoke("NearbyInteraction not supported on this device")
            return
        }

        val session = NISession()
        niSession = session

        val delegate = SessionDelegate()
        sessionDelegate = delegate
        session.delegate = delegate

        // The session's discoveryToken is available immediately after creation
        localDiscoveryToken = session.discoveryToken
        if (localDiscoveryToken == null) {
            NSLog("UwbManager: Warning — discoveryToken is null after session creation")
        } else {
            NSLog("UwbManager: Initialized with discovery token")
        }
    }

    actual fun getLocalConfig(): UwbSessionConfig? {
        val token = localDiscoveryToken ?: return null

        // Serialize the discovery token via NSKeyedArchiver
        val tokenData = try {
            NSKeyedArchiver.archivedDataWithRootObject(
                `object` = token,
                requiringSecureCoding = true,
                error = null
            )
        } catch (e: Exception) {
            NSLog("UwbManager: Failed to serialize discovery token: ${e.message}")
            return null
        }

        if (tokenData == null) {
            NSLog("UwbManager: archivedData returned null")
            return null
        }

        val tokenBytes = tokenData.toByteArray()

        return UwbSessionConfig(
            sessionId = 0, // Not used on iOS
            channel = 0,
            preambleIndex = 0,
            uwbAddress = ByteArray(0), // Not used on iOS
            discoveryToken = tokenBytes,
        )
    }

    actual fun startRanging(peerId: String, remoteConfig: UwbSessionConfig) {
        val session = niSession
        if (session == null) {
            errorCallback?.invoke("NI session not initialized. Call initialize() first.")
            return
        }

        val tokenBytes = remoteConfig.discoveryToken
        if (tokenBytes == null || tokenBytes.isEmpty()) {
            errorCallback?.invoke("No discovery token in remote config for $peerId")
            return
        }

        // Deserialize the peer's discovery token
        val tokenData = tokenBytes.toNSData()
        val peerToken = try {
            NSKeyedUnarchiver.unarchivedObjectOfClass(
                cls = NIDiscoveryToken,
                fromData = tokenData,
                error = null
            ) as? NIDiscoveryToken
        } catch (e: Exception) {
            errorCallback?.invoke("Failed to deserialize peer token for $peerId: ${e.message}")
            return
        }

        if (peerToken == null) {
            errorCallback?.invoke("Failed to deserialize discovery token for $peerId")
            return
        }

        activePeers[peerId] = peerToken

        // Create a peer configuration with the exchanged token
        val config = NINearbyPeerConfiguration(peerToken)

        NSLog("UwbManager: Starting ranging with $peerId")
        session.runWithConfiguration(config)
    }

    actual fun stopRanging(peerId: String) {
        activePeers.remove(peerId)
        if (activePeers.isEmpty()) {
            niSession?.pause()
            NSLog("UwbManager: Paused session (no active peers)")
        }
    }

    actual fun setRangingCallback(callback: (peerId: String, distance: Double) -> Unit) {
        rangingCallback = callback
    }

    actual fun setErrorCallback(callback: (error: String) -> Unit) {
        errorCallback = callback
    }

    actual fun cleanup() {
        niSession?.invalidate()
        niSession = null
        activePeers.clear()
        localDiscoveryToken = null
        sessionDelegate = null
        NSLog("UwbManager: Cleanup completed")
    }

    // ---- Session Delegate ----

    private inner class SessionDelegate : NSObject(), NISessionDelegateProtocol {

        override fun session(session: NISession, didUpdateNearbyObjects: List<*>) {
            dispatchToMain {
                didUpdateNearbyObjects.forEach { obj ->
                    if (obj is NINearbyObject) {
                        val distance = obj.distance.toDouble()
                        if (!distance.isNaN()) {
                            val peerId = activePeers.entries
                                .find { it.value == obj.discoveryToken }?.key ?: "unknown"
                            rangingCallback?.invoke(peerId, distance)
                        }
                    }
                }
            }
        }

        override fun session(
            session: NISession,
            didRemoveNearbyObjects: List<*>,
            withReason: NINearbyObjectRemovalReason
        ) {
            dispatchToMain {
                didRemoveNearbyObjects.forEach { obj ->
                    if (obj is NINearbyObject) {
                        val peerId = activePeers.entries
                            .find { it.value == obj.discoveryToken }?.key
                        peerId?.let {
                            activePeers.remove(it)
                            NSLog("UwbManager: Peer $it removed, reason=$withReason")
                        }
                    }
                }
            }
        }

        override fun session(session: NISession, didInvalidateWithError: NSError) {
            dispatchToMain {
                val msg = didInvalidateWithError.localizedDescription
                NSLog("UwbManager: Session invalidated: $msg")
                errorCallback?.invoke("NI Session error: $msg")
                activePeers.clear()
                niSession = null
                localDiscoveryToken = null
            }
        }

        override fun session(
            session: NISession,
            didGenerateShareableConfigurationData: NSData,
            forObject: NINearbyObject
        ) {
            // Shareable configuration data generated — not used in our BLE-based exchange
        }

        override fun session(
            session: NISession,
            didUpdateAlgorithmConvergence: NIAlgorithmConvergence,
            forObject: NINearbyObject?
        ) {
            // Algorithm convergence update — informational
        }

        override fun sessionDidStartRunning(session: NISession) {
            NSLog("UwbManager: Session started running")
        }

        override fun sessionWasSuspended(session: NISession) {
            dispatchToMain {
                errorCallback?.invoke("NI Session was suspended")
            }
        }

        override fun sessionSuspensionEnded(session: NISession) {
            NSLog("UwbManager: Session suspension ended")
            // Re-run with existing config if we have active peers
            // The session needs to be re-configured after suspension
        }
    }

    // ---- Helpers ----

    private fun dispatchToMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

// NSData <-> ByteArray helpers are in NsDataUtils.kt
