package com.dustedrob.uwb

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/** Azimuth and elevation in degrees, derived from a unit direction vector. */
internal data class DirectionAngles(val azimuth: Double, val elevation: Double)

/**
 * Converts a unit direction vector to the module's angle contract: degrees, azimuth 0 = straight
 * ahead and positive to the right, elevation positive = above.
 *
 * This is NearbyInteraction's `NINearbyObject.direction` frame, which is the device frame: +x right,
 * +y up, +z out of the screen toward the user, so a peer behind the phone is -z. Hence azimuth is
 * `atan2(x, -z)` (the full-circle bearing in the horizontal plane, not Apple's sample's `asin(x)`,
 * which folds past 90 degrees) and elevation is `asin(y)` (the angle above that plane).
 *
 * Returns `null` when there is no direction to report: the `NINearbyObjectDirectionNotAvailable`
 * sentinel is a vector of NaNs, and an all-zero vector is not a direction either.
 */
internal fun directionToAngles(x: Double, y: Double, z: Double): DirectionAngles? {
    if (x.isNaN() || y.isNaN() || z.isNaN()) return null
    if (x == 0.0 && y == 0.0 && z == 0.0) return null
    return DirectionAngles(
        azimuth = atan2(x, -z) * 180.0 / PI,
        // The vector is documented as a unit vector; clamp anyway so float rounding past 1.0
        // can't turn into a NaN elevation.
        elevation = asin(y.coerceIn(-1.0, 1.0)) * 180.0 / PI,
    )
}
