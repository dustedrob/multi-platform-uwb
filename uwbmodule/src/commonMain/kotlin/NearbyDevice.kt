package com.dustedrob.uwb

/**
 * Connection state of a nearby device through the discovery → ranging pipeline.
 */
enum class DeviceState {
    Discovered,
    ExchangingConfig,
    Ranging,
    /** Session exists but the platform paused it or the peer stopped answering; may resume. */
    Suspended,
    /** Session ended; a restart is scheduled or in progress (see [RecoveryPolicy]). */
    Recovering,
    Disconnected,
    Error
}

/**
 * Coarse vertical relationship to a peer, for platforms that report a category instead of an angle.
 *
 * iOS fills this instead of [NearbyDevice.elevation] on devices without direction hardware, where
 * NearbyInteraction's only vertical output is `verticalDirectionEstimate`.
 */
enum class VerticalDirection {
    /** Roughly the same height. */
    Same,
    Above,
    Below,
    /** Not level, but NearbyInteraction can't tell which way. */
    AboveOrBelow,
}

/**
 * Represents a nearby UWB-capable device discovered via BLE.
 *
 * @property id Unique identifier (BLE address on Android, peripheral UUID on iOS).
 * @property name Human-readable device name from the BLE advertisement.
 * @property distance Current UWB-measured distance in meters, or `null` if not yet ranging.
 * @property azimuth Horizontal angle to the device in degrees (0 = straight ahead, positive = right),
 *   or `null` if direction isn't available yet.
 * @property elevation Vertical angle to the device in degrees (positive = above), or `null` if
 *   unavailable. On iOS this needs direction-capable hardware; a camera-assisted session reports
 *   [verticalDirection] instead.
 * @property verticalDirection Coarse above/below estimate, set only when [elevation] is unavailable.
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
    val azimuth: Double? = null,
    val elevation: Double? = null,
    val verticalDirection: VerticalDirection? = null,
    val lastSeen: Long = getCurrentTimeMillis(),
    val state: DeviceState = DeviceState.Discovered,
    val errorMessage: String? = null,
    val sessionId: Int? = null,
    val channel: Int? = null,
)
