package com.sajda.compass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidencePolicyTest {
    @Test
    fun onlyHighAndMediumAllowPreciseHeading() {
        assertTrue(Confidence.HIGH.allowsPreciseHeading)
        assertTrue(Confidence.MEDIUM.allowsPreciseHeading)
        assertFalse(Confidence.LOW.allowsPreciseHeading)
        assertFalse(Confidence.CALIBRATE.allowsPreciseHeading)
    }

    @Test
    fun onlyCalibrateRequestsCalibration() {
        assertFalse(Confidence.HIGH.needsCalibration)
        assertFalse(Confidence.MEDIUM.needsCalibration)
        assertFalse(Confidence.LOW.needsCalibration)
        assertTrue(Confidence.CALIBRATE.needsCalibration)
    }
}
