package com.sajda.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCandidatePolicyTest {
    @Test
    fun materiallyMoreAccurateFixWinsEvenFromNetwork() {
        assertTrue(LocationCandidatePolicy.isBetter(20f, "network", 90f, "gps"))
        assertFalse(LocationCandidatePolicy.isBetter(90f, "gps", 20f, "network"))
    }

    @Test
    fun gpsWinsWhenReportedAccuracyIsEffectivelyEquivalent() {
        assertTrue(LocationCandidatePolicy.isBetter(45f, "gps", 30f, "network"))
        assertFalse(LocationCandidatePolicy.isBetter(30f, "network", 45f, "gps"))
    }

    @Test
    fun knownAccuracyWinsAgainstUnknownAccuracy() {
        assertTrue(LocationCandidatePolicy.isBetter(500f, "network", null, "gps"))
        assertFalse(LocationCandidatePolicy.isBetter(null, "gps", 500f, "network"))
    }

    @Test
    fun unknownProvidersDoNotOutrankKnownProviders() {
        assertFalse(LocationCandidatePolicy.isBetter(25f, "passive", 25f, "network"))
    }

    @Test
    fun newerModeratelyWorseAccuracyPreferredWhenMuchFresher() {
        // Old 10m vs newer 40m (>60s): choose newer (not worse than 2×)
        val t0 = 1_000_000L
        assertTrue(
            LocationCandidatePolicy.isBetter(
                candidateAccuracyMeters = 40f,
                candidateProvider = "network",
                currentAccuracyMeters = 10f,
                currentProvider = "gps",
                candidateTimeMillis = t0 + 90_000L,
                currentTimeMillis = t0
            )
        )
    }

    @Test
    fun muchWorseAccuracyRejectedEvenIfVeryNew() {
        // Brand-new 300m vs recent 15m: keep the accurate one
        val t0 = 1_000_000L
        assertFalse(
            LocationCandidatePolicy.isBetter(
                candidateAccuracyMeters = 300f,
                candidateProvider = "network",
                currentAccuracyMeters = 15f,
                currentProvider = "gps",
                candidateTimeMillis = t0 + 120_000L,
                currentTimeMillis = t0
            )
        )
    }

    @Test
    fun withinTieBandPreferNewer() {
        val t0 = 1_000_000L
        assertTrue(
            LocationCandidatePolicy.isBetter(
                candidateAccuracyMeters = 30f,
                candidateProvider = "network",
                currentAccuracyMeters = 28f,
                currentProvider = "network",
                candidateTimeMillis = t0 + 5_000L,
                currentTimeMillis = t0
            )
        )
    }
}
