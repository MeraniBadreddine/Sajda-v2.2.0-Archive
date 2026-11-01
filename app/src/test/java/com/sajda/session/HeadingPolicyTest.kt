package com.sajda.session

import com.sajda.compass.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingPolicyTest {
    @Test fun alignmentOutside() = assertEquals(0f, HeadingPolicy.alignmentFactor(15.0), 1e-5f)
    @Test fun alignmentInside() = assertEquals(1f, HeadingPolicy.alignmentFactor(1.0), 1e-5f)
    @Test fun alignmentMid() {
        val a = HeadingPolicy.alignmentFactor(7.0)
        assertTrue(a in 0.4f..0.6f)
    }
    @Test fun alignedThreshold() {
        assertTrue(HeadingPolicy.isAligned(1.5))
        assertFalse(HeadingPolicy.isAligned(3.0))
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
