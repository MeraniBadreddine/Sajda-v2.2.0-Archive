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
}
