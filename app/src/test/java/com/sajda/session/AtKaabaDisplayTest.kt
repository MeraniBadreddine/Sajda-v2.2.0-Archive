package com.sajda.session

import com.sajda.geo.GeoMath
import com.sajda.geo.GeoPoint
import com.sajda.geo.QiblaEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display rule: at-Kaaba UI only when engine says atKaaba AND location trust is GOOD.
 * Manual city (provisional, no accuracy) must not trigger the UI.
 */
class AtKaabaDisplayTest {

    private fun shouldShowAtKaabaUi(atKaaba: Boolean, trust: LocationTrust): Boolean =
        atKaaba && trust == LocationTrust.GOOD

    @Test
    fun meccaCoordinatesEngineFlagsAtKaaba() {
        val s = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT, GeoMath.KAABA_LON))
        assertTrue(s.atKaaba)
    }

    @Test
    fun manualCityMeccaDoesNotShowAtKaabaUi() {
        val engine = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT, GeoMath.KAABA_LON))
        // MANUAL_CITY path stores provisional=true, accuracy=null → UNKNOWN trust
        val trust = LocationTrust.from(accuracyMeters = null, provisional = true)
        assertTrue(engine.atKaaba)
        assertFalse(shouldShowAtKaabaUi(engine.atKaaba, trust))
    }

    @Test
    fun goodGpsNearKaabaShowsAtKaabaUi() {
        val engine = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT, GeoMath.KAABA_LON))
        val trust = LocationTrust.from(accuracyMeters = 25f, provisional = false)
        assertTrue(shouldShowAtKaabaUi(engine.atKaaba, trust))
    }

    @Test
    fun weakGpsNearKaabaDoesNotShowAtKaabaUi() {
        val engine = QiblaEngine.solveDetailed(GeoPoint(GeoMath.KAABA_LAT, GeoMath.KAABA_LON))
        val trust = LocationTrust.from(accuracyMeters = 500f, provisional = false)
        assertFalse(shouldShowAtKaabaUi(engine.atKaaba, trust))
    }
}
