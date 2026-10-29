package com.dustedrob.uwb

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The direction vector is in the device frame: +x right, +y up, +z out of the screen toward the
 * user, so "ahead" (out the back of the phone) is -z.
 */
class DirectionAnglesTest {

    @Test
    fun straightAheadIsZeroZero() {
        val a = directionToAngles(0.0, 0.0, -1.0)!!
        assertEquals(0.0, a.azimuth, 1e-9)
        assertEquals(0.0, a.elevation, 1e-9)
    }

    @Test
    fun rightIsPositiveAzimuth() {
        val a = directionToAngles(1.0, 0.0, 0.0)!!
        assertEquals(90.0, a.azimuth, 1e-9)
        assertEquals(0.0, a.elevation, 1e-9)
    }

    @Test
    fun leftIsNegativeAzimuth() {
        assertEquals(-90.0, directionToAngles(-1.0, 0.0, 0.0)!!.azimuth, 1e-9)
    }

    @Test
    fun aboveIsPositiveElevation() {
        val a = directionToAngles(0.0, 1.0, 0.0)!!
        assertEquals(90.0, a.elevation, 1e-9)
    }

    @Test
    fun belowIsNegativeElevation() {
        assertEquals(-90.0, directionToAngles(0.0, -1.0, 0.0)!!.elevation, 1e-9)
    }

    @Test
    fun diagonalSplitsTheAngle() {
        // 45 degrees to the right of ahead, level.
        val h = sqrt(0.5)
        val a = directionToAngles(h, 0.0, -h)!!
        assertEquals(45.0, a.azimuth, 1e-9)
        assertEquals(0.0, a.elevation, 1e-9)
    }

    @Test
    fun azimuthIsFullCirclePastNinetyDegrees() {
        // Behind the user: Apple's sample's asin(x) would fold this back to 0.
        assertEquals(180.0, directionToAngles(0.0, 0.0, 1.0)!!.azimuth, 1e-9)
    }

    @Test
    fun elevationIsTheAngleAboveTheHorizontalPlane() {
        // Up and to the right, both at 45 degrees from the plane's diagonal.
        val a = directionToAngles(0.5, sqrt(0.5), -0.5)!!
        assertEquals(45.0, a.azimuth, 1e-9)
        assertEquals(45.0, a.elevation, 1e-9)
    }

    @Test
    fun unavailableSentinelIsNull() {
        // NINearbyObjectDirectionNotAvailable is a vector of NaNs.
        assertNull(directionToAngles(Double.NaN, Double.NaN, Double.NaN))
        assertNull(directionToAngles(0.0, Double.NaN, -1.0))
    }

    @Test
    fun zeroVectorIsNull() {
        assertNull(directionToAngles(0.0, 0.0, 0.0))
    }

    @Test
    fun elevationSurvivesRoundingPastUnitLength() {
        // Float lanes widened to Double can land just outside the unit sphere; asin must not NaN.
        val a = directionToAngles(0.0, 1.0000001, 0.0)!!
        assertTrue(a.elevation.isFinite())
        assertEquals(90.0, a.elevation, 1e-6)
    }
}
