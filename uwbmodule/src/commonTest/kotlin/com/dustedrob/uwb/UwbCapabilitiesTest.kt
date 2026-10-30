package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three device shapes an app actually has to branch between, and what the library promises to
 * report for each. [UwbCapabilities.reportsAzimuth] is the one an app reads to decide whether it
 * must derive a bearing itself (#75), so the mapping is pinned here rather than left to the
 * platform managers.
 */
class UwbCapabilitiesTest {

    /** iPhone 11 through 13, or an Android phone with azimuth: angles straight from the radio. */
    private val directionCapable = UwbCapabilities(
        supportsDistance = true,
        supportsDirection = true,
        supportsElevationAngle = true,
        supportsCameraAssistance = true,
        cameraAssistanceActive = false,
    )

    /** iPhone 14 and later with camera assistance allowed: azimuth after convergence, no elevation. */
    private val cameraAssisted = UwbCapabilities(
        supportsDistance = true,
        supportsDirection = false,
        supportsElevationAngle = false,
        supportsCameraAssistance = true,
        cameraAssistanceActive = true,
    )

    /** The same phone with assistance turned off: distance only. */
    private val distanceOnly = cameraAssisted.copy(cameraAssistanceActive = false)

    @Test
    fun directionCapableHardwareReportsBothAngles() {
        assertTrue(directionCapable.reportsAzimuth)
        assertTrue(directionCapable.reportsElevation)
    }

    @Test
    fun cameraAssistanceGivesAzimuthButNoElevationAngle() {
        assertTrue(cameraAssisted.reportsAzimuth)
        // A camera-assisted session only has the coarse NearbyDevice.verticalDirection category.
        assertFalse(cameraAssisted.reportsElevation)
    }

    @Test
    fun noDirectionHardwareAndNoCameraMeansTheAppDerivesItsOwnBearing() {
        assertFalse(distanceOnly.reportsAzimuth)
        assertFalse(distanceOnly.reportsElevation)
        assertTrue(distanceOnly.supportsDistance)
    }

    @Test
    fun supportingCameraAssistanceIsNotTheSameAsUsingIt() {
        // The 14 can assist and the 11 can too; only the device that needs it ends up with it on.
        assertTrue(directionCapable.supportsCameraAssistance)
        assertFalse(directionCapable.cameraAssistanceActive)
    }

    @Test
    fun aDeviceWithoutUwbPromisesNothing() {
        assertFalse(NO_UWB_CAPABILITIES.supportsDistance)
        assertFalse(NO_UWB_CAPABILITIES.reportsAzimuth)
        assertFalse(NO_UWB_CAPABILITIES.reportsElevation)
    }
}
