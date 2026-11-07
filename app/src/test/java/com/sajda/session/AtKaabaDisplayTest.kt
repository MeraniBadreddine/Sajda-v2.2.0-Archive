package com.sajda.session

import com.sajda.geo.GeoMath
import com.sajda.geo.GeoPoint
import com.sajda.geo.QiblaEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtKaabaDisplayTest {

    @Test
    fun meccaCoordinatesEngineFlagsAtKaaba() {
        val s = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT, GeoMath.KAABA_LON))
        assertTrue(s.atKaaba)
    }

    @Test
    fun goodTightFixShowsAtKaaba() {
        assertTrue(
            DisplayPolicy.shouldShowAtKaaba(
                atKaaba = true,
                distanceMeters = 20.0,
                accuracyMeters = 25f,
                trust = LocationTrust.GOOD
            )
        )
    }

    @Test
    fun accuracyMarginBlocksAtKaaba() {
        // distance 80 + accuracy 40 = 120 > 100
        assertFalse(
            DisplayPolicy.shouldShowAtKaaba(
                atKaaba = true,
                distanceMeters = 80.0,
                accuracyMeters = 40f,
                trust = LocationTrust.GOOD
            )
        )
    }

    @Test
    fun weakTrustNeverShowsAtKaaba() {
        assertFalse(
            DisplayPolicy.shouldShowAtKaaba(
                atKaaba = true,
                distanceMeters = 10.0,
                accuracyMeters = 20f,
                trust = LocationTrust.WEAK
            )
        )
    }

    @Test
    fun manualCityProvisionalUnknownTrust() {
        val engine = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT, GeoMath.KAABA_LON))
        val trust = LocationTrust.from(accuracyMeters = null, provisional = true)
        assertTrue(engine.atKaaba)
        assertFalse(
            DisplayPolicy.shouldShowAtKaaba(
                engine.atKaaba,
                engine.distanceMeters,
                null,
                trust
            )
        )
    }
}
