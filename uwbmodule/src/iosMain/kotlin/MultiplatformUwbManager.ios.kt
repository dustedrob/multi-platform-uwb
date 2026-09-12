package com.dustedrob.uwb

import kotlin.math.PI
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.NearbyInteraction.NIAlgorithmConvergence
import platform.NearbyInteraction.NIAlgorithmConvergenceStatus
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientHorizontalSweep
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientLighting
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientMovement
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientVerticalSweep
import platform.NearbyInteraction.NIConfiguration
import platform.NearbyInteraction.NIDiscoveryToken
import platform.NearbyInteraction.NINearbyAccessoryConfiguration
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

    private var rangingCallback: ((String, Double, Double?, Double?) -> Unit)? = null
    private var errorCallback: ((String?, String) -> Unit)? = null

    /** Outbound channel to write data back to a peer over BLE (wired to BleManager.sendToPeer). */
    private var sendToPeerCallback: ((String, ByteArray) -> Unit)? = null

    /**
     * NearbyInteraction direction APIs (`horizontalAngle`, `verticalDirectionEstimate`) are iOS 16+.
     * Calling them on iOS 14/15 is an unrecognized selector and crashes, so gate on the OS version.
     */
    private val directionApiAvailable: Boolean =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion >= 16 }

    /** Maps peer ID → the token we received from that peer. */
    private val activePeers = mutableMapOf<String, NIDiscoveryToken>()

    /** Live NISession per peer, kept out of the serializable [UwbSessionConfig]. */
    private val peerSessions = mutableMapOf<String, NISession>()

    /** Local config we created per peer. */
    private val connectionConfigs = mutableMapOf<String, UwbSessionConfig>()

    /** Peers ranging as accessories, so suspend/stop handshakes only go to them. */
    private val accessoryPeers = mutableSetOf<String>()

    /** Strong reference to each session's delegate; NISession.delegate is weak. */
    private val activeDelegates = mutableMapOf<NISession, SessionDelegate>()

    /**
     * The configuration each peer's session is running, so a suspended session can be re-run when
     * the suspension ends (NI requires `runWithConfiguration` again; it does not resume by itself).
     */
    private val peerConfigs = mutableMapOf<String, NIConfiguration>()

    private fun peerIdFor(session: NISession): String =
        peerSessions.entries.firstOrNull { it.value == session }?.key ?: "unknown"

     actual suspend fun initialize() {
        if (!NISession.isSupported()) {
            errorCallback?.invoke(null, "NearbyInteraction not supported on this device")
            return
        }
    }

    actual fun createConnectionConfig(peerId:String, isAccessory:Boolean): UwbSessionConfig? {
        // One session per peer: discovery and the incoming connection use the same CoreBluetooth UUID,
        // so a second call must reuse the existing session rather than overlay a fresh NISession (which
        // would strand the first and its already-shared discovery token).
        connectionConfigs[peerId]?.let { return it }

        val session = NISession()
        peerSessions[peerId] = session
        val delegate = SessionDelegate()
        activeDelegates[session] = delegate
        session.delegate = delegate

        // The session's discoveryToken is available immediately after creation
        val localDiscoveryToken = session.discoveryToken
        if (localDiscoveryToken == null) {
            NSLog("UwbManager: Warning — discoveryToken is null after session creation")
        } else {
            NSLog("UwbManager: Initialized with discovery token")
        }

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

        connectionConfigs[peerId]= UwbSessionConfig(
            timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong(),
            sessionId = 0, // Not used on iOS
            channel = 0,
            preambleIndex = 0,
            uwbAddress = ByteArray(0), // Not used on iOS
            discoveryToken = tokenBytes,
        )
        NSLog("Added config for $peerId")
        return connectionConfigs[peerId]
    }

    actual fun getConnectionConfig(peerId:String): UwbSessionConfig? {
        NSLog("looking for config for $peerId")
        return connectionConfigs[peerId]
    }
    
    actual suspend fun startRanging(peerId: String, remoteConfig: UwbSessionConfig) {

        val accessoryData = remoteConfig.accessoryData
        if (accessoryData != null) {
            val config = try {
                NINearbyAccessoryConfiguration(accessoryData.toNSData(), null)
            } catch (e: Exception) {
                errorCallback?.invoke(peerId, "Failed to build accessory configuration for $peerId: ${e.message}")
                return
            }
            // check for camera assistance in later iOS systems
            if(!NISession.deviceCapabilities.supportsDirectionMeasurement) {
                NSLog("MultiPlatformMgr device does not support direction measurement");
                if (NISession.deviceCapabilities.supportsCameraAssistance) {
                    NSLog("MultiPlatformMgr device DOES support camera assistance")
                    config.setCameraAssistanceEnabled(true)
                } else {
                    NSLog("MultiPlatformMgr device DOES NOT support camera assistance")
                }
            } else {
                NSLog("MultiPlatformMgr device DOES support direction measurement")
            }
            NSLog("UwbManager: Starting accessory ranging with $peerId ")
            val session = peerSessions[peerId]
            if (session == null) {
                errorCallback?.invoke(peerId, "No NISession for $peerId; createConnectionConfig must run first")
                return
            }
            accessoryPeers.add(peerId)
            // NISession.delegate is weak, so hold each session's delegate strongly per session.
            val delegate = activeDelegates[session] ?: SessionDelegate().also { activeDelegates[session] = it }
            session.delegate = delegate
            peerConfigs[peerId] = config
            session.runWithConfiguration(config)
            return
        }

        val tokenBytes = remoteConfig.discoveryToken
        if (tokenBytes == null || tokenBytes.isEmpty()) {
            errorCallback?.invoke(peerId, "No discovery token in remote config for $peerId")
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
            errorCallback?.invoke(peerId, "Failed to deserialize peer token for $peerId: ${e.message}")
            return
        }

        if (peerToken == null) {
            errorCallback?.invoke(peerId, "Failed to deserialize discovery token for $peerId")
            return
        }

        activePeers[peerId] = peerToken
        // Create a peer configuration with the exchanged token
        val config = NINearbyPeerConfiguration(peerToken)
        if(!NISession.deviceCapabilities.supportsDirectionMeasurement) {
            NSLog("MultiPlatformMgr device does not support direction measurement");
            if (NISession.deviceCapabilities.supportsCameraAssistance) {
                NSLog("MultiPlatformMgr device DOES support camera assistance")
                config.setCameraAssistanceEnabled(true)
            } else {
                NSLog("MultiPlatformMgr device DOES NOT support camera assistance")
            }
        } else {
            NSLog("MultiPlatformMgr device DOES support direction measurement")
        }
        NSLog("UwbManager: Starting ranging with $peerId")
        val session = peerSessions[peerId]
        if (session == null) {
            errorCallback?.invoke(peerId, "No NISession for $peerId; createConnectionConfig must run first")
            return
        }
        // NISession.delegate is weak, so hold each session's delegate strongly per session.
        val delegate = activeDelegates[session] ?: SessionDelegate().also { activeDelegates[session] = it }
        session.delegate = delegate
        peerConfigs[peerId] = config
        session.runWithConfiguration(config)
    }

    actual suspend fun stopRanging(peerId: String) {
        val session = peerSessions[peerId]
        session?.pause()
        forgetPeer(peerId)
        NSLog("UwbManager: Paused session for $peerId")
    }

    /**
     * Drop every per-peer entry for one peer, leaving the other sessions untouched. The config goes
     * too, so a re-discovery mints a fresh session and token instead of reusing a dead one.
     */
    private fun forgetPeer(peerId: String) {
        activePeers.remove(peerId)
        accessoryPeers.remove(peerId)
        peerConfigs.remove(peerId)
        peerSessions.remove(peerId)?.let { activeDelegates.remove(it) }
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

    actual suspend fun cleanup() {
        peerSessions.values.forEach { it.invalidate() }
        activePeers.clear()
        peerSessions.clear()
        accessoryPeers.clear()
        activeDelegates.clear()
        peerConfigs.clear()
        connectionConfigs.clear()
        NSLog("UwbManager: Cleanup completed")
    }

    // ---- Session Delegate ----

    private inner class SessionDelegate : NSObject(), NISessionDelegateProtocol {

        override fun session(session: NISession, didUpdateNearbyObjects: List<*>) {
            dispatchToMain {
                // Sessions are per peer, so the session identity is the peer identity (accessory
                // objects aren't in activePeers, which is keyed by peer tokens).
                val peerId = peerIdFor(session)
                didUpdateNearbyObjects.forEach { obj ->
                    if (obj is NINearbyObject) {
                        val distance = obj.distance.toDouble()
                        if (!distance.isNaN()) {
                            // Azimuth (`horizontalAngle`) is iOS 16+ and is NaN until camera-assistance
                            // convergence, so only emit it when available and valid. NearbyInteraction
                            // reports it in radians; convert to degrees to match the module contract
                            // (Android reports degrees), so consumers get one consistent unit.
                            val azimuth: Double? =
                                if (directionApiAvailable) {
                                    obj.horizontalAngle.let {
                                        if (it.isNaN()) null else it.toDouble() * 180.0 / PI
                                    }
                                } else {
                                    null
                                }

                            // NearbyInteraction exposes no elevation angle — `verticalDirectionEstimate`
                            // is a direction category (above/below/same), not a measurement — so we
                            // leave elevation null on iOS rather than emit a meaningless value.
                            rangingCallback?.invoke(peerId, distance, azimuth, null)
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
                val peerId = peerIdFor(session)
                didRemoveNearbyObjects.forEach { obj ->
                    if (obj is NINearbyObject) {
                        activePeers.remove(peerId)
                        NSLog("UwbManager: Peer $peerId removed, reason=$withReason")
                        errorCallback?.invoke(peerId, "Peer $peerId out of range (reason=$withReason)")
                    }
                }
            }
        }

        override fun session(session: NISession, didInvalidateWithError: NSError) {
            dispatchToMain {
                val msg = didInvalidateWithError.localizedDescription
                // Only this peer's session died; the others keep ranging.
                val peerId = peerIdFor(session)
                NSLog("UwbManager: Session invalidated for $peerId: $msg")
                errorCallback?.invoke(peerId, "NI Session error: $msg")
                forgetPeer(peerId)
            }
        }

        override fun session(
            session: NISession,
            didGenerateShareableConfigurationData: NSData,
            forObject: NINearbyObject
        ) {
            NSLog("did generate entered")
            // Accessory ranging: NI produced the data the accessory needs to start. Send it back over
            // BLE, prefixed with the configure-and-start message id.
            val peerId = peerIdFor(session)

            val payload = byteArrayOf(NI_ACCESSORY_CONFIGURE_AND_START) + didGenerateShareableConfigurationData.toByteArray()
            NSLog("UwbManager: sending configure-and-start to $peerId (${payload.size} bytes)")
            sendToPeerCallback?.invoke(peerId, payload)
        }

        override fun session(
            session: NISession,
            didUpdateAlgorithmConvergence: NIAlgorithmConvergence,
            forObject: NINearbyObject?
        ) {
            // Algorithm convergence update.
            //
            // NOTE: `NIAlgorithmConvergenceStatusReason` is NOT an enum — it is declared as
            // `typedef NSString * NIAlgorithmConvergenceStatusReason NS_TYPED_ENUM`, so there is no
            // type/enum-class to import. The reasons are NSString constants, and `convergence.reasons`
            // (NSArray<NIAlgorithmConvergenceStatusReason>) comes through to Kotlin/Native as a
            // List<*> of String. (The property is NS_SWIFT_UNAVAILABLE, but is exposed to K/N via the
            // Obj-C surface.) We therefore compare the entries against the imported string constants.
            when (didUpdateAlgorithmConvergence.status) {
                NIAlgorithmConvergenceStatus.NIAlgorithmConvergenceStatusConverged ->
                    NSLog("UwbManager: convergence converged — angles are valid")

                NIAlgorithmConvergenceStatus.NIAlgorithmConvergenceStatusNotConverged -> {
                    didUpdateAlgorithmConvergence.reasons.forEach { reason ->
                        val message = when (reason as? String) {
                            NIAlgorithmConvergenceStatusReasonInsufficientLighting ->
                                "needs more light"
                            NIAlgorithmConvergenceStatusReasonInsufficientHorizontalSweep ->
                                "move device left/right"
                            NIAlgorithmConvergenceStatusReasonInsufficientVerticalSweep ->
                                "move device up/down"
                            NIAlgorithmConvergenceStatusReasonInsufficientMovement ->
                                "move around"
                            else -> "try moving in a different direction"
                        }
                        NSLog("UwbManager: convergence not converged — $message")
                    }
                }

                else -> NSLog("UwbManager: convergence status unknown")
            }
        }

        override fun sessionDidStartRunning(session: NISession) {
            NSLog("UwbManager: Session started running for ${peerIdFor(session)}")
        }

        override fun sessionWasSuspended(session: NISession) {
            val peerId = peerIdFor(session)
            NSLog("UwbManager: Session suspended for ${peerId}")
            // Suspension is transient (backgrounding, or NI juggling several sessions), so do not
            // tell an accessory to stop here: that made the suspension permanent, and the accessory
            // only learns about a real stop from stopRanging. We re-run when the suspension ends.
            dispatchToMain {
                errorCallback?.invoke(peerId, "NI Session was suspended for $peerId")
            }
        }

        override fun sessionSuspensionEnded(session: NISession) {
            val peerId = peerIdFor(session)
            NSLog("UwbManager: Session suspension ended for $peerId")
            // NI does not resume by itself; the session has to be run with its configuration again.
            val config = peerConfigs[peerId]
            if (config != null) {
                session.runWithConfiguration(config)
            } else {
                NSLog("UwbManager: No stored configuration for $peerId; cannot resume")
            }
        }
    }

    // ---- Helpers ----

    private fun dispatchToMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

// NSData <-> ByteArray helpers are in NsDataUtils.kt
