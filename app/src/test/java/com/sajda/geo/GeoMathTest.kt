package com.sajda.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class GeoMathTest {

    private fun refSpherical(lat: Double, lon: Double): Double {
        val φ1 = Math.toRadians(lat)
        val φ2 = Math.toRadians(GeoMath.KAABA_LAT)
        var Δλ = Math.toRadians(GeoMath.KAABA_LON - lon)
        if (Δλ > Math.PI) Δλ -= 2 * Math.PI
        if (Δλ < -Math.PI) Δλ += 2 * Math.PI
        val y = Math.sin(Δλ) * Math.cos(φ2)
        val x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ)
        var d = Math.toDegrees(Math.atan2(y, x)) % 360.0
        if (d < 0) d += 360.0
        return d
    }

    private fun assertCity(name: String, lat: Double, lon: Double) {
        val expected = refSpherical(lat, lon)
        val actual = GeoMath.qiblaBearing(lat, lon)
        assertEquals(name, expected, actual, 1e-9)
    }

    @Test fun algiers() = assertCity("Algiers", 36.7538, 3.0588)
    @Test fun annaba() = assertCity("Annaba", 36.9000, 7.7667)
    @Test fun london() = assertCity("London", 51.5074, -0.1278)
    @Test fun paris() = assertCity("Paris", 48.8566, 2.3522)
    @Test fun newYork() = assertCity("New York", 40.7128, -74.0060)
    @Test fun jakarta() = assertCity("Jakarta", -6.2088, 106.8456)
    @Test fun tokyo() = assertCity("Tokyo", 35.6762, 139.6503)
    @Test fun capeTown() = assertCity("Cape Town", -33.9249, 18.4241)
    @Test fun sydney() = assertCity("Sydney", -33.8688, 151.2093)
    @Test fun saoPaulo() = assertCity("Sao Paulo", -23.5505, -46.6333)

    @Test
    fun longitudeWrapDateline() {
        val b = GeoMath.qiblaBearing(0.0, 179.0)
        assertTrue(b in 0.0..360.0)
        assertEquals(refSpherical(0.0, 179.0), b, 1e-9)
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
