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
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970
import platform.ARKit.ARSession
import platform.ARKit.ARSessionDelegateProtocol
import platform.ARKit.ARWorldTrackingConfiguration
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
import platform.NearbyInteraction.NINearbyObjectVerticalDirectionEstimateAbove
import platform.NearbyInteraction.NINearbyObjectVerticalDirectionEstimateAboveOrBelow
import platform.NearbyInteraction.NINearbyObjectVerticalDirectionEstimateBelow
import platform.NearbyInteraction.NINearbyObjectVerticalDirectionEstimateSame
import platform.NearbyInteraction.NINearbyPeerConfiguration
import platform.NearbyInteraction.NISession
import platform.NearbyInteraction.NISessionDelegateProtocol
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW

@OptIn(ExperimentalForeignApi::class)
actual class MultiplatformUwbManager(private val options: UwbOptions = UwbOptions()) {

    private var rangingCallback: ((String, Double, Double?, Double?, VerticalDirection?) -> Unit)? = null
    private var errorCallback: ((String?, String) -> Unit)? = null
    private var sessionEventCallback: ((String, SessionEvent) -> Unit)? = null

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

    /**
     * One ARSession shared by every NISession that uses camera assistance. Left to itself, NI spins
     * up a private ARSession per NISession, and two of those contend for the camera, which suspends
     * the first session as soon as the second starts. The delegate also opts out of relocalization:
     * camera assistance requires `sessionShouldAttemptRelocalization == false`, and resuming from a
     * stale map stalls ranging after a restart.
     */
    private var sharedArSession: ARSession? = null

    /** Strong reference; ARSession.delegate is weak, like NISession's. */
    private var arSessionDelegate: ArSessionObserver? = null

    /** Peers whose session was given camera assistance, so the ARSession is freed with the last one. */
    private val cameraAssistPeers = mutableSetOf<String>()

    /**
     * Guards every field above. The orchestrator calls [startRanging] / [stopRanging] /
     * [prepareConnectionConfig] on `Dispatchers.Default` while the CoreBluetooth delegates call
     * [createConnectionConfig] / [getConnectionConfig] on the main queue, so these maps are touched
     * from two threads at once. Unsynchronized that is corrupting rather than merely stale: losing an
     * [activeDelegates] entry deallocates a delegate (NISession.delegate is weak) and that session
     * goes silent, and a torn check-then-act in [attachSharedArSession] mints a second ARSession that
     * fights the first for the camera. Both were seen with three concurrent peers (#54).
     *
     * Recursive because delegate callbacks run on the main queue and may re-enter while a platform
     * call made under the lock is in flight. Hold it only around state access, never around a
     * `runWithConfiguration` / `invalidate`, so a blocked main queue can never stall the radio.
     */
    private val stateLock = NSRecursiveLock()

    private inline fun <T> withState(block: () -> T): T {
        stateLock.lock()
        try {
            return block()
        } finally {
            stateLock.unlock()
        }
    }

    private class ArSessionObserver : NSObject(), ARSessionDelegateProtocol {
        override fun sessionShouldAttemptRelocalization(session: ARSession): Boolean = false
    }

    /**
     * Hand [session] the shared ARSession. Call only when camera assistance was enabled (iOS 16+).
     * The whole check-then-create runs under [stateLock]: two peers starting at once would otherwise
     * each create an ARSession, and the loser's would keep running and contend for the camera.
     */
    private fun attachSharedArSession(session: NISession) = withState {
        val ar = sharedArSession ?: ARSession().also {
            arSessionDelegate = ArSessionObserver()
            it.delegate = arSessionDelegate
            it.runWithConfiguration(ARWorldTrackingConfiguration())
            sharedArSession = it
        }
        // NearbyInteraction only forward-declares ARSession, so K/N types the parameter as the
        // objcnames placeholder; the cast is a no-op at runtime.
        @Suppress("UNCHECKED_CAST")
        session.setARSession(ar as objcnames.classes.ARSession)
    }

    /** Stop and drop the shared ARSession once no session is using camera assistance any more. */
    private fun releaseArSessionIfUnused() = withState {
        if (cameraAssistPeers.isNotEmpty() || sharedArSession == null) return@withState
        sharedArSession?.pause()
        sharedArSession = null
        arSessionDelegate = null
        NSLog("UwbManager: released the shared ARSession; no camera-assisted sessions left")
    }

    private fun peerIdFor(session: NISession): String = withState {
        peerSessions.entries.firstOrNull { it.value == session }?.key ?: "unknown"
    }

    /**
     * Whether to turn on camera assistance for a new configuration: only when the app allows it and
     * the device lacks direction-capable UWB hardware but can use the camera instead (iOS 16+).
     */
    private fun wantsCameraAssistance(): Boolean {
        val caps = NISession.deviceCapabilities
        val wanted = shouldUseCameraAssistance(
            mode = options.cameraAssistance,
            supportsDirectionMeasurement = caps.supportsDirectionMeasurement,
            supportsCameraAssistance = caps.supportsCameraAssistance,
        )
        NSLog(
            "UwbManager: camera assistance ${if (wanted) "on" else "off"} " +
                "(mode=${options.cameraAssistance}, direction=${caps.supportsDirectionMeasurement}, " +
                "cameraCapable=${caps.supportsCameraAssistance})"
        )
        return wanted
    }

    /**
     * Re-run of a suspended session. Delayed so the camera pipeline (and whatever caused the
     * suspension, e.g. a screenshot overlay) has settled; an immediate re-run has been seen to leave
     * NI running but silent (#66). Skipped if the peer was released meanwhile.
     */
    private fun resumeLater(peerId: String) {
        val delay = dispatch_time(DISPATCH_TIME_NOW, (RESUME_DELAY_MS * NSEC_PER_MSEC.toLong()))
        dispatch_after(delay, dispatch_get_main_queue()) {
            val session = withState { peerSessions[peerId] }
            val config = withState { peerConfigs[peerId] }
            if (session == null || config == null) {
                NSLog("UwbManager: $peerId released before resume; not re-running")
                return@dispatch_after
            }
            session.runWithConfiguration(config)
            sessionEventCallback?.invoke(peerId, SessionEvent.Resumed)
        }
    }

    private companion object {
        const val RESUME_DELAY_MS = 500L
    }

     actual suspend fun initialize() {
        if (!NISession.isSupported()) {
            errorCallback?.invoke(null, "NearbyInteraction not supported on this device")
            return
        }
    }

    actual fun createConnectionConfig(peerId:String, isAccessory:Boolean): UwbSessionConfig? = withState {
        // One session per peer: discovery and the incoming connection use the same CoreBluetooth UUID,
        // so a second call must reuse the existing session rather than overlay a fresh NISession (which
        // would strand the first and its already-shared discovery token). The check and the insert are
        // under one lock so two concurrent callers cannot each mint a session for the same peer.
        connectionConfigs[peerId]?.let { return@withState it }

        val session = NISession()

        // The session's discoveryToken is available immediately after creation
        val localDiscoveryToken = session.discoveryToken
        if (localDiscoveryToken == null) {
            NSLog("UwbManager: Warning — discoveryToken is null after session creation")
        } else {
            NSLog("UwbManager: Initialized with discovery token")
        }

        // Nothing is recorded until the token is in hand. A session parked in peerSessions with no
        // config used to be orphaned by the next attempt, which overwrote the entry and left the
        // first session running with nobody holding it.
        val token = localDiscoveryToken ?: run {
            session.invalidate()
            return@withState null
        }

        // Serialize the discovery token via NSKeyedArchiver
        val tokenData = try {
            NSKeyedArchiver.archivedDataWithRootObject(
                `object` = token,
                requiringSecureCoding = true,
                error = null
            )
        } catch (e: Exception) {
            NSLog("UwbManager: Failed to serialize discovery token: ${e.message}")
            session.invalidate()
            return@withState null
        }

        if (tokenData == null) {
            NSLog("UwbManager: archivedData returned null")
            session.invalidate()
            return@withState null
        }

        val tokenBytes = tokenData.toByteArray()

        val delegate = SessionDelegate()
        activeDelegates[session] = delegate
        session.delegate = delegate
        peerSessions[peerId] = session

        val config = UwbSessionConfig(
            timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong(),
            sessionId = 0, // Not used on iOS
            channel = 0,
            preambleIndex = 0,
            uwbAddress = ByteArray(0), // Not used on iOS
            discoveryToken = tokenBytes,
        )
        connectionConfigs[peerId] = config
        NSLog("Added config for $peerId")
        config
    }

    actual fun getConnectionConfig(peerId:String): UwbSessionConfig? = withState {
        NSLog("looking for config for $peerId")
        connectionConfigs[peerId]
    }

    actual suspend fun prepareConnectionConfig(peerId: String, isAccessory: Boolean): UwbSessionConfig? =
        createConnectionConfig(peerId, isAccessory)
    
    actual suspend fun startRanging(peerId: String, remoteConfig: UwbSessionConfig) {

        val accessoryData = remoteConfig.accessoryData
        if (accessoryData != null) {
            val config = try {
                NINearbyAccessoryConfiguration(accessoryData.toNSData(), null)
            } catch (e: Exception) {
                errorCallback?.invoke(peerId, "Failed to build accessory configuration for $peerId: ${e.message}")
                return
            }
            val cameraAssist = wantsCameraAssistance()
            if (cameraAssist) config.setCameraAssistanceEnabled(true)
            NSLog("UwbManager: Starting accessory ranging with $peerId ")
            val session = withState { peerSessions[peerId] }
            if (session == null) {
                errorCallback?.invoke(peerId, "No NISession for $peerId; createConnectionConfig must run first")
                return
            }
            // NISession.delegate is weak, so hold each session's delegate strongly per session.
            val delegate = withState {
                accessoryPeers.add(peerId)
                if (cameraAssist) cameraAssistPeers.add(peerId)
                peerConfigs[peerId] = config
                activeDelegates[session] ?: SessionDelegate().also { activeDelegates[session] = it }
            }
            session.delegate = delegate
            if (cameraAssist) attachSharedArSession(session)
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

        withState { activePeers[peerId] = peerToken }
        // Create a peer configuration with the exchanged token
        val config = NINearbyPeerConfiguration(peerToken)
        val cameraAssist = wantsCameraAssistance()
        if (cameraAssist) config.setCameraAssistanceEnabled(true)
        NSLog("UwbManager: Starting ranging with $peerId")
        val session = withState { peerSessions[peerId] }
        if (session == null) {
            errorCallback?.invoke(peerId, "No NISession for $peerId; createConnectionConfig must run first")
            return
        }
        // NISession.delegate is weak, so hold each session's delegate strongly per session.
        val delegate = withState {
            if (cameraAssist) cameraAssistPeers.add(peerId)
            peerConfigs[peerId] = config
            activeDelegates[session] ?: SessionDelegate().also { activeDelegates[session] = it }
        }
        session.delegate = delegate
        if (cameraAssist) attachSharedArSession(session)
        session.runWithConfiguration(config)
    }

    actual suspend fun stopRanging(peerId: String) {
        val session = withState { peerSessions[peerId] }
        session?.pause()
        forgetPeer(peerId)
        NSLog("UwbManager: Paused session for $peerId")
    }

    /**
     * Drop every per-peer entry for one peer, leaving the other sessions untouched. The config goes
     * too, so a re-discovery mints a fresh session and token instead of reusing a dead one.
     */
    private fun forgetPeer(peerId: String) {
        withState {
            activePeers.remove(peerId)
            accessoryPeers.remove(peerId)
            cameraAssistPeers.remove(peerId)
            peerConfigs.remove(peerId)
            peerSessions.remove(peerId)?.let { activeDelegates.remove(it) }
            connectionConfigs.remove(peerId)
        }
        // The camera is an exclusive resource, so hand it back as soon as the last assisted peer goes.
        releaseArSessionIfUnused()
    }

    actual fun setRangingCallback(
        callback: (
            peerId: String,
            distance: Double,
            azimuth: Double?,
            elevation: Double?,
            verticalDirection: VerticalDirection?,
        ) -> Unit
    ) {
        rangingCallback = callback
    }

    actual fun setSendToPeerCallback(callback: (peerId: String, data: ByteArray) -> Unit) {
        sendToPeerCallback = callback
    }

    actual fun setSessionEventCallback(callback: (peerId: String, event: SessionEvent) -> Unit) {
        sessionEventCallback = callback
    }

    actual fun setErrorCallback(callback: (peerId: String?, error: String) -> Unit) {
        errorCallback = callback
    }

    actual suspend fun cleanup() {
        val sessions = withState {
            val snapshot = peerSessions.values.toList()
            activePeers.clear()
            peerSessions.clear()
            accessoryPeers.clear()
            cameraAssistPeers.clear()
            activeDelegates.clear()
            peerConfigs.clear()
            connectionConfigs.clear()
            sharedArSession?.pause()
            sharedArSession = null
            arSessionDelegate = null
            snapshot
        }
        // Outside the lock: invalidate calls back into the delegates.
        sessions.forEach { it.invalidate() }
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
                            // Two mutually exclusive direction sources, and both have to be read
                            // (#63): `direction` is the unit vector that direction-capable hardware
                            // fills, while `horizontalAngle` is iOS 16+ and only filled by a
                            // camera-assisted session, which is exactly the case where there is no
                            // direction hardware (see wantsCameraAssistance). Reading only the angle
                            // meant every direction-capable iPhone reported no angle at all.
                            val angles = anglesFrom(obj)
                            val azimuth = angles?.azimuth ?: cameraAssistedAzimuth(obj)
                            // No vector means no elevation angle either, so pass on NI's coarse
                            // above/below category in its place.
                            val vertical = if (angles == null) verticalDirectionOf(obj) else null
                            rangingCallback?.invoke(peerId, distance, azimuth, angles?.elevation, vertical)
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
                if (didRemoveNearbyObjects.none { it is NINearbyObject }) return@dispatchToMain
                NSLog("UwbManager: Peer $peerId removed, reason=$withReason")
                if (withReason == NINearbyObjectRemovalReason.NINearbyObjectRemovalReasonPeerEnded) {
                    // The peer invalidated its session; ours will never hear from it again. Not an
                    // error: the orchestrator may restart with a fresh session and exchange.
                    session.invalidate()
                    forgetPeer(peerId)
                    sessionEventCallback?.invoke(peerId, SessionEvent.Ended)
                } else {
                    // Timeout: NI stops updating this object but the session is ours to re-run,
                    // which is what Apple recommends. The peer either comes back or ages out.
                    val config = withState {
                        activePeers.remove(peerId)
                        peerConfigs[peerId]
                    }
                    config?.let { session.runWithConfiguration(it) }
                    sessionEventCallback?.invoke(peerId, SessionEvent.PeerLost)
                }
            }
        }

        override fun session(session: NISession, didInvalidateWithError: NSError) {
            dispatchToMain {
                val msg = didInvalidateWithError.localizedDescription
                // Only this peer's session died; the others keep ranging. An invalidated session is
                // gone for good, so this is reported as Ended and the orchestrator decides whether a
                // fresh session is worth trying (bounded by RecoveryPolicy, so a permanent cause such
                // as denied permission still settles into Error).
                val peerId = peerIdFor(session)
                NSLog("UwbManager: Session invalidated for $peerId: $msg")
                forgetPeer(peerId)
                sessionEventCallback?.invoke(peerId, SessionEvent.Ended)
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
            // Not an error: the session is intact and re-run when the suspension ends. The
            // orchestrator handles the accessory side (stop while suspended, link kept open).
            dispatchToMain {
                sessionEventCallback?.invoke(peerId, SessionEvent.Suspended)
            }
        }

        override fun sessionSuspensionEnded(session: NISession) {
            val peerId = peerIdFor(session)
            NSLog("UwbManager: Session suspension ended for $peerId")
            // NI does not resume by itself; the session has to be run with its configuration again.
            // For an accessory that produces fresh shareable configuration data, which restarts it.
            if (withState { peerConfigs[peerId] } != null) {
                resumeLater(peerId)
            } else {
                NSLog("UwbManager: No stored configuration for $peerId; cannot resume")
            }
        }
    }

    // ---- Helpers ----

    /**
     * Angles from `NINearbyObject.direction`. simd_float3 arrives as a non-null `Vector128`
     * (x, y, z, pad), which Kotlin/Native has no `.x/.y/.z` for, so read the 32-bit lanes; the
     * unavailable sentinel is a vector of NaNs and [directionToAngles] rejects it.
     */
    private fun anglesFrom(obj: NINearbyObject): DirectionAngles? {
        val dir = obj.direction
        return directionToAngles(
            x = dir.getFloatAt(0).toDouble(),
            y = dir.getFloatAt(1).toDouble(),
            z = dir.getFloatAt(2).toDouble(),
        )
    }

    /**
     * Azimuth in degrees from the camera-assisted `horizontalAngle` (iOS 16+, radians, NaN until
     * convergence). Converted to degrees because Android reports degrees and the module contract is
     * one unit for both.
     */
    private fun cameraAssistedAzimuth(obj: NINearbyObject): Double? {
        if (!directionApiAvailable) return null
        val radians = obj.horizontalAngle
        return if (radians.isNaN()) null else radians.toDouble() * 180.0 / PI
    }

    /** NI's coarse vertical estimate (iOS 16+): the only vertical output a camera-assisted session has. */
    private fun verticalDirectionOf(obj: NINearbyObject): VerticalDirection? {
        if (!directionApiAvailable) return null
        // NS_ENUM, but cinterop maps this one to a typealias plus top-level constants rather than a
        // Kotlin enum, so these are plain values and the `when` needs an else.
        return when (obj.verticalDirectionEstimate) {
            NINearbyObjectVerticalDirectionEstimateSame -> VerticalDirection.Same
            NINearbyObjectVerticalDirectionEstimateAbove -> VerticalDirection.Above
            NINearbyObjectVerticalDirectionEstimateBelow -> VerticalDirection.Below
            NINearbyObjectVerticalDirectionEstimateAboveOrBelow -> VerticalDirection.AboveOrBelow
            // Unknown, and anything a later SDK adds, is no information.
            else -> null
        }
    }

    private fun dispatchToMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

// NSData <-> ByteArray helpers are in NsDataUtils.kt
