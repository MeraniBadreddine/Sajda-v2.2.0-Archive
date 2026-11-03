package com.sajda.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {
    @Test
    fun validCoordinatesAccepted() {
        assertTrue(LocationQuality.isValidCoordinate(36.75, 3.05))
        assertFalse(LocationQuality.isValidCoordinate(91.0, 0.0))
        assertFalse(LocationQuality.isValidCoordinate(0.0, 181.0))
    }

    @Test
    fun ageWindow() {
        val now = 1_000_000L
        assertTrue(LocationQuality.isAcceptableAge(now - 60_000, now, 120_000))
        assertFalse(LocationQuality.isAcceptableAge(now - 200_000, now, 120_000))
    }

    @Test
    fun unknownAccuracyAccepted() {
        assertTrue(LocationQuality.isAcceptableAccuracy(null))
        assertTrue(LocationQuality.isAcceptableAccuracy(0f))
        assertTrue(LocationQuality.isAcceptableAccuracy(-1f))
    }

    @Test
    fun extremeAccuracyRejected() {
        assertTrue(LocationQuality.isAcceptableAccuracy(50f))
        assertFalse(LocationQuality.isAcceptableAccuracy(10_000f))
    }

    @Test
    fun usableRequiresCoordAgeAndAccuracy() {
        val now = System.currentTimeMillis()
        assertTrue(
            LocationQuality.isUsable(36.75, 3.05, now, now, 120_000, accuracyMeters = 25f)
        )
        assertFalse(
            LocationQuality.isUsable(36.75, 3.05, now, now, 120_000, accuracyMeters = 20_000f)
        )
        assertFalse(
            LocationQuality.isUsable(200.0, 3.05, now, now, 120_000, accuracyMeters = 10f)
        )
    }
}
