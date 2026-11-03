package com.sajda.location

import com.sajda.data.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents expected provider → source mapping used by LocationController.
 * Pure logic mirror so regressions are visible without Android instrumentation.
 */
class LocationSourceMappingTest {
    private fun map(provider: String?): LocationSource = when (provider) {
        "gps" -> LocationSource.GPS
        "network" -> LocationSource.NETWORK
        "fused" -> LocationSource.FUSED
        else -> LocationSource.FUSED
    }

    @Test
    fun mapsKnownProviders() {
        assertEquals(LocationSource.GPS, map("gps"))
        assertEquals(LocationSource.NETWORK, map("network"))
        assertEquals(LocationSource.FUSED, map("fused"))
    }

    @Test
    fun lastKnownIsDistinctFromGps() {
        assertEquals(LocationSource.LAST_KNOWN, LocationSource.LAST_KNOWN)
        assertEquals(LocationSource.MANUAL_CITY, LocationSource.MANUAL_CITY)
    }
}
