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
    @Test fun preciseGuidanceRequiresGoodLocation() {
        assertTrue(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, true, LocationTrust.GOOD))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, true, LocationTrust.WEAK))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, true, LocationTrust.UNKNOWN))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.CALIBRATE, true, LocationTrust.GOOD))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, false, LocationTrust.GOOD))
    }
    @Test fun sensorLocationMatrix() {
        // HIGH sensor × each location trust
        assertTrue(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, true, LocationTrust.GOOD))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, true, LocationTrust.WEAK))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.HIGH, true, LocationTrust.UNKNOWN))
        // MEDIUM sensor
        assertTrue(HeadingPolicy.shouldShowPreciseGuidance(Confidence.MEDIUM, true, LocationTrust.GOOD))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.MEDIUM, true, LocationTrust.WEAK))
        // LOW / CALIBRATE never precise
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.LOW, true, LocationTrust.GOOD))
        assertFalse(HeadingPolicy.shouldShowPreciseGuidance(Confidence.CALIBRATE, true, LocationTrust.GOOD))
    }
    @Test fun turnSign() {
        assertEquals(HeadingPolicy.TurnInstruction.RIGHT, HeadingPolicy.turnInstruction(20.0))
        assertEquals(HeadingPolicy.TurnInstruction.LEFT, HeadingPolicy.turnInstruction(-15.0))
        assertEquals(HeadingPolicy.TurnInstruction.ALIGNED, HeadingPolicy.turnInstruction(0.5))
    }
}

class LocationTrustTest {
    @Test fun goodFromTightAccuracy() {
        assertEquals(LocationTrust.GOOD, LocationTrust.from(50f, provisional = false))
        assertEquals(LocationTrust.GOOD, LocationTrust.from(100f, provisional = false))
    }
    @Test fun weakBand() {
        assertEquals(LocationTrust.WEAK, LocationTrust.from(250f, provisional = false))
        assertEquals(LocationTrust.WEAK, LocationTrust.from(50f, provisional = true))
        assertEquals(LocationTrust.WEAK, LocationTrust.from(2000f, provisional = false))
    }
    @Test fun unknownWhenMissingOrHuge() {
        assertEquals(LocationTrust.UNKNOWN, LocationTrust.from(null, provisional = false))
        assertEquals(LocationTrust.UNKNOWN, LocationTrust.from(null, provisional = true))
        assertEquals(LocationTrust.UNKNOWN, LocationTrust.from(5000f, provisional = false))
    }
}
