package com.dustedrob.uwb

/**
 * How [DeviceDiscoveryManager] reacts when a peer's ranging session ends without being stopped
 * (platform session torn down, app backgrounded, radio hiccup) or goes silent while nominally ranging.
 *
 * A restart drops the peer's platform session and pushes it back through discovery → exchange →
 * ranging: phones are forgotten at the BLE layer so their next advertisement starts a fresh exchange;
 * accessories keep their BLE link and get a fresh init over it.
 *
 * @property autoRecover restart automatically. When false the peer goes to [DeviceState.Error] on the
 *   first failure and the app decides via [DeviceDiscoveryManager.restartPeer].
 * @property maxAttempts restarts allowed before giving up; the counter resets once ranging results
 *   arrive again.
 * @property backoffMs delay before the first restart, doubled on each further attempt.
 * @property silentTimeoutMs a [DeviceState.Ranging] peer with no results for this long is treated as
 *   ended (the platform can report a session as running while delivering nothing, e.g. iOS after a
 *   suspension). Must be larger than the normal update interval.
 */
data class RecoveryPolicy(
    val autoRecover: Boolean = true,
    val maxAttempts: Int = 3,
    val backoffMs: Long = 1_000,
    val silentTimeoutMs: Long = 10_000,
)

/** Outcome of [RecoveryTracker.onEnded]. */
sealed class RecoveryDecision {
    /** Restart after [delayMs]; this is attempt number [attempt] (1-based). */
    data class Retry(val delayMs: Long, val attempt: Int) : RecoveryDecision()
    /** Give up: the policy is off or the attempts are spent. */
    data object GiveUp : RecoveryDecision()
}

/**
 * Pure per-peer attempt bookkeeping for [RecoveryPolicy], kept free of platform types so it can be
 * unit-tested in commonTest like [DeviceDiscoveryManager.connectionScore].
 */
internal class RecoveryTracker(private val policy: RecoveryPolicy) {
    private val attempts = mutableMapOf<String, Int>()

    /** A session ended (or went silent); decide whether and when to restart it. */
    fun onEnded(peerId: String): RecoveryDecision {
        val used = attempts[peerId] ?: 0
        if (!policy.autoRecover || used >= policy.maxAttempts) return RecoveryDecision.GiveUp
        attempts[peerId] = used + 1
        return RecoveryDecision.Retry(delayMs = policy.backoffMs shl used, attempt = used + 1)
    }

    /** Results are flowing again; the next failure starts over from the first backoff. */
    fun onRecovered(peerId: String) {
        attempts.remove(peerId)
    }

    /** Forget a peer entirely (purged, or the app forced a restart). */
    fun reset(peerId: String) = onRecovered(peerId)

    fun clear() = attempts.clear()

    companion object {
        /**
         * Peers that claim to be ranging but have delivered nothing within [timeoutMs]. Only
         * [DeviceState.Ranging] counts: suspended and recovering peers are expected to be quiet.
         */
        fun silentPeers(devices: List<NearbyDevice>, now: Long, timeoutMs: Long): List<NearbyDevice> =
            devices.filter { it.state == DeviceState.Ranging && now - it.lastSeen > timeoutMs }
    }
}
