package com.sajda.compass

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import com.sajda.geo.GeoMath

class HeadingFilterTest {
    @Test fun smoothsAcrossNorthWithoutJumpingThroughSouth() {
        val filter = HeadingFilter()
        filter.filter(359.0)
        val result = filter.filter(1.0)
        assertTrue("Expected result close to north, got $result", abs(GeoMath.angleDelta(0.0, result)) < 3.0)
    }

    @Test fun ignoresNonFiniteSensorFrame() {
        val filter = HeadingFilter()
        val first = filter.filter(42.0)
        val afterInvalid = filter.filter(Double.NaN)
        assertTrue(abs(GeoMath.angleDelta(first, afterInvalid)) < 1e-9)
    }

    @Test fun resetAllowsFreshInitialReading() {
        val filter = HeadingFilter()
        filter.filter(90.0)
        filter.reset()
        val result = filter.filter(270.0)
        assertTrue(abs(GeoMath.angleDelta(270.0, result)) < 1e-9)
    }
}
