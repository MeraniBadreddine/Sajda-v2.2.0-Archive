package com.sajda.ui

import com.sajda.R
import com.sajda.data.LocationFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFailureUiTest {
    @Test
    fun timeoutInCityModeUsesGpsTimeoutNotCityUseGps() {
        val res = LocationFailureUi.timeoutPillRes(cityMode = true, hasSession = true)
        assertEquals(R.string.gps_timeout, res)
        // Explicit: must not fall back to the generic city CTA
        assert(res != R.string.city_use_gps)
    }

    @Test
    fun timeoutWithoutSessionUsesLocationTimeout() {
        assertEquals(
            R.string.location_timeout,
            LocationFailureUi.timeoutPillRes(cityMode = false, hasSession = false)
        )
    }

    @Test
    fun pillResTimeoutCitySession() {
        assertEquals(
            R.string.gps_timeout,
            LocationFailureUi.pillRes(
                LocationFailure.TIMEOUT,
                cityMode = true,
                hasSession = true
            )
        )
    }
}
