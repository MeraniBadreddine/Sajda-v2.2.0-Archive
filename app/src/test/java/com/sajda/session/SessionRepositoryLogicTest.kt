package com.sajda.session

import com.sajda.compass.Confidence
import com.sajda.geo.GeoMath
import com.sajda.geo.GeoPoint
import com.sajda.geo.QiblaEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session math without Android Context: Qibla from point must match engine.
 */
class SessionRepositoryLogicTest {
    @Test
    fun qiblaFromAlgiersMatchesEngine() {
        val p = GeoPoint(36.7538, 3.0588)
        val q = QiblaEngine.solveDetailed(p).bearing.value
        assertEquals(GeoMath.qiblaBearing(p.latDeg, p.lonDeg), q, 1e-9)
        assertTrue(q in 90.0..130.0)
    }

    @Test
    fun invalidPointRejectedByEngine() {
        try {
            QiblaEngine.solveDetailed(GeoPoint(Double.POSITIVE_INFINITY, 0.0))
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun confidenceCalibrateBlocksPrecise() {
        assertTrue(!Confidence.CALIBRATE.allowsPreciseHeading)
        assertTrue(Confidence.HIGH.allowsPreciseHeading)
    }
}
