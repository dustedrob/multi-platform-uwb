package com.dustedrob.uwb

import kotlin.math.PI
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.kCFNumberNaN
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.NearbyInteraction.NIAlgorithmConvergence
import platform.NearbyInteraction.NIAlgorithmConvergenceStatus
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientHorizontalSweep
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientLighting
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientMovement
import platform.NearbyInteraction.NIAlgorithmConvergenceStatusReasonInsufficientVerticalSweep
import platform.NearbyInteraction.NIDiscoveryToken
import platform.NearbyInteraction.NINearbyAccessoryConfiguration
import platform.NearbyInteraction.NINearbyObject
import platform.NearbyInteraction.NINearbyObjectRemovalReason
import platform.NearbyInteraction.NINearbyObjectVerticalDirectionEstimateAbove
import platform.NearbyInteraction.NINearbyObjectVerticalDirectionEstimateBelow
import platform.NearbyInteraction.NINearbyObjectVerticalDirectionEstimateSame
import platform.NearbyInteraction.NINearbyPeerConfiguration
import platform.NearbyInteraction.NISession
import platform.NearbyInteraction.NISessionDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.asin;
import kotlin.math.PI;

@OptIn(ExperimentalForeignApi::class)
actual class MultiplatformUwbManager {
    private var niSession: NISession? = null
    private var rangingCallback: ((String, Double, Double?, Double?, String?) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    /** Outbound channel to write data back to a peer over BLE (wired to BleManager.sendToPeer). */
    private var sendToPeerCallback: ((String, ByteArray) -> Unit)? = null

    /** The accessory peer currently being ranged (single-accessory session; multi-accessory is future). */
    private var accessoryPeerId: String? = null

    /**
     * NearbyInteraction direction APIs (`horizontalAngle`, `verticalDirectionEstimate`) are iOS 16+.
     * Calling them on iOS 14/15 is an unrecognized selector and crashes, so gate on the OS version.
     */
    private val directionApiAvailable: Boolean =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion >= 16 }

    /** Maps peer ID → the token we received from that peer. */
    private val activePeers = mutableMapOf<String, NIDiscoveryToken>()

    /** Our local discovery token, available after session creation. */
    private var localDiscoveryToken: NIDiscoveryToken? = null

    /** Strong reference to delegate to prevent GC. */
    private var sessionDelegate: SessionDelegate? = null

    /*actual suspend fun initialize(){

    }*/
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

    actual fun getLocalConfig(isAccessory:Boolean): UwbSessionConfig? {
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

        // Accessory ranging: build an accessory configuration from the accessory's data blob. NI then
        // generates shareable configuration data (see didGenerateShareableConfigurationData), which we
        // write back to the accessory over BLE to actually start ranging.
        val accessoryData = remoteConfig.accessoryData
        if (accessoryData != null) {
            val config = try {
                NINearbyAccessoryConfiguration(accessoryData.toNSData(), null)
            } catch (e: Exception) {
                errorCallback?.invoke("Failed to build accessory configuration for $peerId: ${e.message}")
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
            }
            accessoryPeerId = peerId
            NSLog("UwbManager: Starting accessory ranging with $peerId")
            session.runWithConfiguration(config)
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
        if (peerId == accessoryPeerId) accessoryPeerId = null
        if (activePeers.isEmpty() && accessoryPeerId == null) {
            niSession?.pause()
            NSLog("UwbManager: Paused session (no active peers)")
        }
    }

    actual fun setRangingCallback(callback: (peerId: String, distance: Double, azimuth: Double?, elevation: Double?, elevationString:String?) -> Unit) {
        rangingCallback = callback
    }

    actual fun setSendToPeerCallback(callback: (peerId: String, data: ByteArray) -> Unit) {
        sendToPeerCallback = callback
    }

    actual fun setErrorCallback(callback: (error: String) -> Unit) {
        errorCallback = callback
    }

    actual fun cleanup() {
        niSession?.invalidate()
        niSession = null
        activePeers.clear()
        accessoryPeerId = null
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
                        var elevation:Double? =null
                        val distance = obj.distance.toDouble()
                        if (!distance.isNaN()) {
                            // Accessory objects aren't in activePeers (keyed by peer tokens), so fall
                            // back to the tracked accessory peer.
                            val peerId = activePeers.entries
                                .find { it.value == obj.discoveryToken }?.key
                                ?: accessoryPeerId ?: "unknown"

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

                            val elevation: Double? = if (directionApiAvailable) {
                                // index x=0,y=1,z=2
                                obj.direction.getFloatAt(2).let { asin(it).toDouble() * 180.0 / PI }
                            } else null

                            val elevationCategory: String? = if (directionApiAvailable && obj.direction == null) {
                                when (obj.verticalDirectionEstimate) {
                                    NINearbyObjectVerticalDirectionEstimateAbove -> "above"
                                    NINearbyObjectVerticalDirectionEstimateBelow -> "below"
                                    NINearbyObjectVerticalDirectionEstimateSame  -> "same"
                                    else -> null
                                }
                            } else null

                            // on iPhone 11-13, NearbyInteraction exposes a vector (x/y/z) of floats simd_float3
                            // on later iPhones exposes no numeric elevation angle — `verticalDirectionEstimate`
                            // is a direction category (above/below/same), not a measurement — so we
                            // set elevation to the float or the string
                            rangingCallback?.invoke(peerId, distance, azimuth, elevation, elevationCategory)
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
            // Accessory ranging: NI produced the data the accessory needs to start. Send it back over
            // BLE, prefixed with the configure-and-start message id.
            val peerId = accessoryPeerId
            if (peerId == null) {
                NSLog("UwbManager: shareable config generated but no accessory peer tracked")
                return
            }
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
