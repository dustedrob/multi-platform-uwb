
@file:JvmName("TimeoutHandler")package com.dustedrob.uwb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.jvm.JvmName
import kotlin.time.Duration.Companion.milliseconds

/**
 * Platform BLE manager handling device discovery via scanning/advertising
 * and UWB session configuration exchange via GATT.
 */

expect class BleManager {

    /** Start scanning for nearby UWB-capable devices. */
    fun startScanning()

    /** Stop BLE scanning. */
    fun stopScanning()

    /** Start BLE advertising as a UWB-capable device. */
    fun advertise()

    /** Stop BLE advertising. */
    fun stopAdvertising()

    /** Register callback invoked when a new BLE device is discovered. */
    fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit)

    // ---- GATT-based UWB config exchange ----

    /**
     * Start a GATT server.
     * Remote peers can read our config and write theirs.
     */
    fun startGattServer()

    /** Stop the GATT server. */
    fun stopGattServer()

    /**
     * As a GATT client, connect to [peerId] and exchange UWB configs.
     * Reads the peer's config and writes our own.
     * Result delivered via [setConfigExchangedCallback].
     */
    fun connectAndExchangeConfig(peerId: String, connectionConfig: UwbSessionConfig)

    /**
     * Register callback invoked when a config exchange completes (on either side).
     * Called with the peer's ID and their [UwbSessionConfig].
     */
    fun setConfigExchangedCallback(callback: (peerId: String, remoteConfig: UwbSessionConfig) -> Unit)

    /**
     * Write [data] to a still-connected accessory's control characteristic.
     *
     * Used by the accessory protocol after the initial config exchange (e.g. configure-and-start with
     * iOS's shareable data, or stop). No-op if [peerId] isn't a connected accessory.
     */
    fun sendToPeer(peerId: String, data: ByteArray)

    /** Clean up BLE resources. Call when done using the manager. */
    fun cleanup()
}
    val BleManager.ScanTime: Long
        get() = 5000

fun BleManager.setTimeout(delayMillis: Long, block: () -> Unit): Job {
        return CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            delay(delayMillis.milliseconds)
            block()
        }
    }


    fun BleManager.createUUIDFilter(patternMask: String): Regex {

        val cleanedMask = patternMask// .replace("-", "").trim()
        val regexPattern = buildString {
            append("^")
            for (char in cleanedMask) {
                when (char) {
                    '.' -> append("[0-9a-fA-F]")  // Any valid hex character
                    '#' -> append("[0-9]") // Any decimal digit (0-9)
                    else -> append(char.toString()) // Strict character literal match
                }
            }
            append("$")

        }
        return Regex(regexPattern, RegexOption.IGNORE_CASE)
    }
