package com.sajda.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class GeoMathTest {

    private fun assertReference(name: String, lat: Double, lon: Double, expected: Double) {
        assertEquals(name, expected, GeoMath.qiblaBearing(lat, lon), 0.001)
    }

    // Independent reference bearings computed with a geodesic library and
    // checked against the app's spherical great-circle implementation.
    @Test fun algiersReference() = assertReference("Algiers", 36.7538, 3.0588, 105.410265)
    @Test fun annabaReference() = assertReference("Annaba", 36.9000, 7.7667, 110.181280)
    @Test fun londonReference() = assertReference("London", 51.5074, -0.1278, 118.987224)
    @Test fun newYorkReference() = assertReference("New York", 40.7128, -74.0060, 58.481706)

    @Test
    fun longitudeWrapDateline() {
        val b = GeoMath.qiblaBearing(0.0, 179.0)
        assertTrue(b in 0.0..360.0)
        assertEquals(300.969457, b, 0.001)
    }

    @Test
    fun ballparkAlgiersAndNy() {
        val a = GeoMath.qiblaBearing(36.7538, 3.0588)
        assertTrue("Algiers expected ~ESE, was $a", a in 90.0..130.0)
        val ny = GeoMath.qiblaBearing(40.7128, -74.0060)
        assertTrue("NY expected ~NE, was $ny", ny in 40.0..80.0)
    }

    @Test
    fun vincentyCloseToSpherical() {
        val samples = listOf(
            36.7538 to 3.0588,
            51.5074 to -0.1278,
            40.7128 to -74.0060,
            -6.2088 to 106.8456
        )
        for ((lat, lon) in samples) {
            val sph = GeoMath.qiblaBearing(lat, lon)
            val vin = GeoMath.qiblaBearingVincenty(lat, lon)
            assertNotNull(vin)
            val delta = abs(GeoMath.angleDelta(sph, vin!!))
            assertTrue("Vincenty delta $delta at $lat,$lon", delta < 0.5)
        }
    }

    @Test
    fun normalizeDegreesWraps() {
        assertEquals(0.0, GeoMath.normalizeDegrees(360.0), 1e-9)
        assertEquals(1.0, GeoMath.normalizeDegrees(361.0), 1e-9)
        assertEquals(359.0, GeoMath.normalizeDegrees(-1.0), 1e-9)
    }

    @Test
    fun angleDeltaShortestPath() {
        assertEquals(10.0, GeoMath.angleDelta(350.0, 0.0), 1e-9)
        assertEquals(-10.0, GeoMath.angleDelta(0.0, 350.0), 1e-9)
        assertEquals(0.0, GeoMath.angleDelta(10.0, 10.0), 1e-9)
    }

    @Test
    fun nearKaabaBearingDefined() {
        val b = GeoMath.qiblaBearing(21.4225, 39.8262)
        assertTrue(b in 0.0..360.0)
    }

    @Test
    fun qiblaEngineRejectsInvalid() {
        try {
            QiblaEngine.solve(GeoPoint(Double.NaN, 0.0))
            fail("expected exception")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun qiblaEngineMatchesGeoMath() {
        val p = GeoPoint(36.7538, 3.0588)
        val eng = QiblaEngine.solve(p).value
        val math = GeoMath.qiblaBearing(p.latDeg, p.lonDeg)
        assertEquals(math, eng, 1e-9)
    }
}
