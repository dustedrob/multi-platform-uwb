package com.dustedrob.uwb

/**
 * Represents a nearby UWB-capable device discovered via BLE.
 *
 * @property id Unique identifier (BLE address on Android, peripheral UUID on iOS).
 * @property name Human-readable device name from the BLE advertisement.
 * @property distance Current UWB-measured distance in meters, or `null` if not yet ranging.
 * @property lastSeen Epoch millis when the device was last observed or ranged.
 */
data class NearbyDevice(
    val id: String,
    val name: String,
    val distance: Double? = null,
    val lastSeen: Long = getCurrentTimeMillis()
)
