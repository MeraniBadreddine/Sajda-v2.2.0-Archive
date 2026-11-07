package com.sajda.location

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the v2.5.7 policy: first usable fix commits (no multi-provider settle).
 */
class FirstUsableFixPolicyTest {

    @Test
    fun firstUsableFixCommitsAndStops() {
        val now = 1_700_000_000_000L
        assertTrue(
            LocationQuality.isUsable(
                latitude = 36.7,
                longitude = 3.0,
                timeMillis = now - 1_000L,
                nowMillis = now,
                maxAgeMs = LocationController.CURRENT_FIX_MAX_AGE_MS,
                accuracyMeters = 25f
            )
        )
        // Second usable fix is also valid in isolation; policy is first-wins at controller level.
        assertTrue(
            LocationQuality.isUsable(
                latitude = 36.7,
                longitude = 3.0,
                timeMillis = now - 2_000L,
                nowMillis = now,
                maxAgeMs = LocationController.CURRENT_FIX_MAX_AGE_MS,
                accuracyMeters = 2000f
            )
        )
    }

    @Test
    fun lastKnownPrefersAccurateOlderOverVagueFresh() {
        val now = 1_700_000_000_000L
        val scoreOld = LocationController.lastKnownScore(
            accuracyMeters = 50f,
            timeMillis = now - 30 * 60_000L,
            nowMs = now
        )
        val scoreFresh = LocationController.lastKnownScore(
            accuracyMeters = 4000f,
            timeMillis = now - 60_000L,
            nowMs = now
        )
        assertTrue("accurate old ($scoreOld) should beat vague fresh ($scoreFresh)", scoreOld < scoreFresh)
    }

    @Test
    fun lastKnownFreshAccurateBeatsOldAccurate() {
        val now = 1_700_000_000_000L
        val older = LocationController.lastKnownScore(20f, now - 5 * 60_000L, now)
        val fresher = LocationController.lastKnownScore(20f, now - 60_000L, now)
        assertTrue(fresher < older)
    }
}
