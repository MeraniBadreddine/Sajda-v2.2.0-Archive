package com.sajda.session

import com.sajda.compass.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingPolicyTest {
    @Test fun alignmentOutside() = assertEquals(0f, HeadingPolicy.alignmentFactor(15.0), 1e-5f)
    @Test fun alignmentInside() {
        assertTrue(HeadingPolicy.alignmentFactor(1.0) > 0.9f)
    }
    @Test fun alignedEnterExit() {
        assertTrue(HeadingPolicy.isAligned(2.5, wasAligned = false))
        assertFalse(HeadingPolicy.isAligned(4.0, wasAligned = false))
        assertTrue(HeadingPolicy.isAligned(4.0, wasAligned = true))
        assertFalse(HeadingPolicy.isAligned(7.0, wasAligned = true))
    }
    @Test fun calibrateNeedsSensor() {
        assertFalse(HeadingPolicy.shouldShowCalibrateBanner(Confidence.CALIBRATE, false))
        assertTrue(HeadingPolicy.shouldShowCalibrateBanner(Confidence.CALIBRATE, true))
    }
    @Test fun preciseGuidance() {
        assertTrue(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, true))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.CALIBRATE, true))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, false))
    }
    @Test fun turnSign() {
        assertEquals(HeadingPolicy.TurnInstruction.RIGHT, HeadingPolicy.turnInstruction(20.0))
        assertEquals(HeadingPolicy.TurnInstruction.LEFT, HeadingPolicy.turnInstruction(-15.0))
        assertEquals(HeadingPolicy.TurnInstruction.ALIGNED, HeadingPolicy.turnInstruction(0.5))
    }
}
