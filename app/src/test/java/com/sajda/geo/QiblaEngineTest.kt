package com.sajda.geo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QiblaEngineTest {
    @Test
    fun atKaabaWhenCoordinatesMatch() {
        val s = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT, GeoMath.KAABA_LON))
        assertTrue(s.atKaaba)
        assertTrue(s.distanceMeters < QiblaEngine.NEAR_KAABA_METERS)
    }

    @Test
    fun atKaabaWithin100m() {
        // ~50 m north of Kaaba
        val s = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT + 0.00045, GeoMath.KAABA_LON))
        assertTrue(s.distanceMeters < 100.0)
        assertTrue(s.atKaaba)
    }

    @Test
    fun farCityNotAtKaaba() {
        val s = QiblaEngine.solveDetailed(GeoPoint(36.7538, 3.0588)) // Algiers
        assertFalse(s.atKaaba)
        assertTrue(s.distanceMeters > 1_000.0)
        assertTrue(s.bearing.value.isFinite())
    }
}
