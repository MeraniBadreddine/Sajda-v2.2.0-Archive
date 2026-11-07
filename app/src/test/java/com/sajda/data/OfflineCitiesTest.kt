package com.sajda.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCitiesTest {
    @Test
    fun everyOfflineCityHasValidCoordinatesAndUniqueKey() {
        assertEquals(OfflineCities.all.size, OfflineCities.all.map { it.nameResKey }.toSet().size)
        OfflineCities.all.forEach { city ->
            assertTrue("Invalid latitude for ${city.nameResKey}", city.lat in -90.0..90.0)
            assertTrue("Invalid longitude for ${city.nameResKey}", city.lon in -180.0..180.0)
        }
    }

    @Test
    fun annabaIsAvailableForLocalManualFallback() {
        val annaba = OfflineCities.all.firstOrNull { it.nameResKey == "annaba" }
        assertNotNull(annaba)
        assertEquals(36.9000, annaba!!.lat, 1e-6)
        assertEquals(7.7667, annaba.lon, 1e-6)
    }
}
