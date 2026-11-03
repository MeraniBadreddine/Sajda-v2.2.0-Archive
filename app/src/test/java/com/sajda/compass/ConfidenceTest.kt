package com.sajda.compass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceTest {
    @Test fun preciseOnlyHighMedium() {
        assertTrue(Confidence.HIGH.allowsPreciseHeading)
        assertTrue(Confidence.MEDIUM.allowsPreciseHeading)
        assertFalse(Confidence.LOW.allowsPreciseHeading)
        assertFalse(Confidence.CALIBRATE.allowsPreciseHeading)
    }

    @Test fun calibrateFlag() {
        assertTrue(Confidence.CALIBRATE.needsCalibration)
        assertFalse(Confidence.HIGH.needsCalibration)
    }
}
