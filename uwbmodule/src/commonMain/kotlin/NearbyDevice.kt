package com.dustedrob.uwb

/**
 * Connection state of a nearby device through the discovery → ranging pipeline.
 */
enum class DeviceState {
    Discovered,
    ExchangingConfig,
    Ranging,
    Disconnected,
    Error
}

/**
 * Represents a nearby UWB-capable device discovered via BLE.
 *
 * @property id Unique identifier (BLE address on Android, peripheral UUID on iOS).
 * @property name Human-readable device name from the BLE advertisement.
 * @property distance Current UWB-measured distance in meters, or `null` if not yet ranging.
 * @property lastSeen Epoch millis when the device was last observed or ranged.
 * @property state Current connection state in the discovery pipeline.
 * @property errorMessage Error description if [state] is [DeviceState.Error].
 * @property sessionId Agreed UWB session ID (set after config exchange).
 * @property channel Agreed UWB channel (set after config exchange).
 */
data class NearbyDevice(
    val id: String,
    val name: String,
    val distance: Double? = null,
    val lastSeen: Long = getCurrentTimeMillis(),
    val state: DeviceState = DeviceState.Discovered,
    val errorMessage: String? = null,
    val sessionId: Int? = null,
    val channel: Int? = null
)
