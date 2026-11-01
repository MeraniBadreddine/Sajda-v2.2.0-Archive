package com.sajda.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {
    @Test fun validCoords() {
        assertTrue(LocationQuality.isValidCoordinate(36.75, 3.05))
        assertTrue(LocationQuality.isValidCoordinate(-90.0, 180.0))
        assertTrue(LocationQuality.isValidCoordinate(90.0, -180.0))
        assertFalse(LocationQuality.isValidCoordinate(Double.NaN, 0.0))
        assertFalse(LocationQuality.isValidCoordinate(0.0, Double.POSITIVE_INFINITY))
        assertFalse(LocationQuality.isValidCoordinate(91.0, 0.0))
        assertFalse(LocationQuality.isValidCoordinate(-91.0, 0.0))
        assertFalse(LocationQuality.isValidCoordinate(0.0, 181.0))
        assertFalse(LocationQuality.isValidCoordinate(0.0, -181.0))
    }

    @Test fun ageWindow() {
        val now = 1_000_000L
        assertTrue(LocationQuality.isAcceptableAge(now, now, 120_000L))
        assertTrue(LocationQuality.isAcceptableAge(now - 120_000L, now, 120_000L))
        assertFalse(LocationQuality.isAcceptableAge(now - 120_001L, now, 120_000L))
        assertTrue(LocationQuality.isAcceptableAge(now + 119_000L, now, 120_000L))
        assertFalse(LocationQuality.isAcceptableAge(now + 120_001L, now, 120_000L))
    }

    @Test fun usableComposite() {
        val now = System.currentTimeMillis()
        assertTrue(LocationQuality.isUsable(36.75, 3.05, now - 10_000L, now, 120_000L))
        assertFalse(LocationQuality.isUsable(36.75, 3.05, now - 999_999L, now, 120_000L))
        assertFalse(LocationQuality.isUsable(91.0, 3.05, now, now, 120_000L))
    }
}
