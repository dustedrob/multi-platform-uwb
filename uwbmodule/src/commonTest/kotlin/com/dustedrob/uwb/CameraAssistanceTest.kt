package com.dustedrob.uwb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full truth table for [shouldUseCameraAssistance]. The iOS manager that consumes it is platform
 * code, so this is where the policy itself is pinned down.
 */
class CameraAssistanceTest {

    @Test
    fun disabledNeverUsesTheCamera() {
        for (direction in listOf(false, true)) {
            for (camera in listOf(false, true)) {
                assertFalse(
                    shouldUseCameraAssistance(CameraAssistanceMode.Disabled, direction, camera),
                    "Disabled should stay off (direction=$direction, camera=$camera)"
                )
            }
        }
    }

    @Test
    fun autoSkipsTheCameraWhenTheRadioMeasuresDirection() {
        assertFalse(shouldUseCameraAssistance(CameraAssistanceMode.Auto, true, true))
        assertFalse(shouldUseCameraAssistance(CameraAssistanceMode.Auto, true, false))
    }

    @Test
    fun autoUsesTheCameraOnlyAsTheFallbackForDirection() {
        assertTrue(shouldUseCameraAssistance(CameraAssistanceMode.Auto, false, true))
    }

    @Test
    fun autoStaysOffWhenTheDeviceCannotAssistEither() {
        assertFalse(shouldUseCameraAssistance(CameraAssistanceMode.Auto, false, false))
    }
}
