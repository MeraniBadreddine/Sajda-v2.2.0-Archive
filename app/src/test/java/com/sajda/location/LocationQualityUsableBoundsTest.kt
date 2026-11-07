package com.sajda.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityUsableBoundsTest {
    @Test
    fun usableWithinAgeAndAccuracy() {
        val now = 1_700_000_000_000L
        assertTrue(
            LocationQuality.isUsable(
                36.7, 3.0, now - 1_000L, now,
                LocationController.CURRENT_FIX_MAX_AGE_MS, 25f
            )
        )
    }

    @Test
    fun lastKnownPrefersAccurateOlderOverVagueFresh() {
        val now = 1_700_000_000_000L
        val scoreOld = LocationController.lastKnownScore(50f, now - 30 * 60_000L, now)
        val scoreFresh = LocationController.lastKnownScore(4000f, now - 60_000L, now)
        assertTrue(scoreOld < scoreFresh)
    }
}
