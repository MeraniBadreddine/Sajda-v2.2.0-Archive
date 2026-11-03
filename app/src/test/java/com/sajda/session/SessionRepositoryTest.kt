package com.sajda.session

import com.sajda.compass.Confidence
import com.sajda.data.LocationCache
import com.sajda.data.UserLocation
import com.sajda.geo.GeoMath
import com.sajda.geo.GeoPoint
import com.sajda.geo.MagneticHeadingDeg
import com.sajda.geo.TrueHeadingDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemoryLocationCache : LocationCache {
    var saved: UserLocation? = null
    override fun save(loc: UserLocation) { saved = loc }
    override fun load(): UserLocation? = saved
    override fun clear() { saved = null }
}

class SessionRepositoryTest {
    private lateinit var cache: InMemoryLocationCache
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        cache = InMemoryLocationCache()
        repo = SessionRepository(cache)
    }

    @Test
    fun applyPointComputesQiblaAndPersists() {
        val p = GeoPoint(36.7538, 3.0588)
        val s = repo.applyPoint(p, provisional = false, fixTimeMillis = 1_000L)
        assertNotNull(s)
        assertEquals(GeoMath.qiblaBearing(p.latDeg, p.lonDeg), s!!.qibla.value, 1e-9)
        assertFalse(s.locationProvisional)
        assertNotNull(cache.saved)
        assertEquals(36.7538, cache.saved!!.lat, 1e-9)
        assertEquals(1_000L, cache.saved!!.fixTimeMillis)
        assertTrue(repo.lastFreshLocationAt > 0L)
    }

    @Test
    fun provisionalDoesNotClaimFreshTimestampPath() {
        val before = repo.lastFreshLocationAt
        repo.applyPoint(GeoPoint(36.75, 3.05), provisional = true, fixTimeMillis = 50L)
        assertTrue(repo.provisionalLocation)
        // provisional must not advance "fresh" clock from a real GPS success path
        // (implementation only updates lastFresh on non-provisional)
        assertEquals(before, repo.lastFreshLocationAt)
    }

    @Test
    fun clearHeadingTrustResetsConfidenceAndHeadings() {
        repo.applyPoint(GeoPoint(36.75, 3.05), provisional = false)
        repo.updateHeading(
            TrueHeadingDeg(100.0),
            MagneticHeadingDeg(98.0),
            Confidence.HIGH,
            interferenceHint = null
        )
        assertEquals(Confidence.HIGH, repo.session!!.confidence)
        assertNotNull(repo.session!!.trueHeading)

        repo.clearHeadingTrust()
        val s = repo.session!!
        assertNull(s.trueHeading)
        assertNull(s.magneticHeading)
        assertEquals(Confidence.CALIBRATE, s.confidence)
        assertNull(s.interferenceHint)
        // location + qibla preserved
        assertNotNull(s.point)
        assertTrue(s.qibla.value in 0.0..360.0)
    }

    @Test
    fun applyPointRecordsSource() {
        repo.applyPoint(GeoPoint(36.75, 3.05), provisional = true, source = com.sajda.data.LocationSource.MANUAL_CITY)
        assertEquals(com.sajda.data.LocationSource.MANUAL_CITY, repo.lastSource)
        assertEquals(com.sajda.data.LocationSource.MANUAL_CITY, cache.saved?.source)
    }

    @Test
    fun invalidPointLeavesSessionUnchanged() {
        assertNull(repo.applyPoint(GeoPoint(Double.NaN, 0.0), provisional = false))
        assertNull(repo.session)
        assertNull(cache.saved)
    }

    @Test
    fun loadCachedIfAnyReadsStore() {
        cache.saved = UserLocation(40.0, -74.0, System.currentTimeMillis())
        val loaded = repo.loadCachedIfAny()
        assertNotNull(loaded)
        assertEquals(40.0, loaded!!.lat, 1e-9)
    }
}
