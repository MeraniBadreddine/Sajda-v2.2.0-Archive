package com.sajda.location

import com.sajda.data.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSourceMappingTest {
    @Test
    fun mapsKnownProvidersUsingProductionMapper() {
        assertEquals(LocationSource.GPS, LocationSourceMapper.fromProvider("gps"))
        assertEquals(LocationSource.NETWORK, LocationSourceMapper.fromProvider("network"))
        assertEquals(LocationSource.FUSED, LocationSourceMapper.fromProvider("fused"))
    }

    @Test
    fun unknownProviderUsesSafeFusedFallback() {
        assertEquals(LocationSource.FUSED, LocationSourceMapper.fromProvider(null))
        assertEquals(LocationSource.FUSED, LocationSourceMapper.fromProvider("unknown"))
    }
}
