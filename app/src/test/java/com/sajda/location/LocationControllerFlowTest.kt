package com.sajda.location

import android.location.Location
import com.sajda.data.LocationFailure
import com.sajda.data.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Full location cycle through the LocationOperations seam.
 */
class LocationControllerFlowTest {

    private lateinit var fake: FakeLocationOperations
    private lateinit var fixes: MutableList<Triple<Location, Boolean, LocationSource>>
    private lateinit var failures: MutableList<LocationFailure>
    private lateinit var controller: LocationController

    private val sync = Executor { it.run() }

    @Before
    fun setUp() {
        fixes = mutableListOf()
        failures = mutableListOf()
        fake = FakeLocationOperations(enabled = setOf("gps", "network"))
        val listener = object : LocationController.Listener {
            override fun onLocationFixed(location: Location, provisional: Boolean, source: LocationSource) {
                fixes += Triple(location, provisional, source)
            }
            override fun onLocationProgress() {}
            override fun onLocationFailed(reason: LocationFailure) {
                failures += reason
            }
        }
        controller = LocationController(listener, fake, sync)
    }

    private fun usable(provider: String, accuracy: Float, ageMs: Long = 1_000L): Location =
        Location(provider).apply {
            latitude = 36.75
            longitude = 3.05
            time = System.currentTimeMillis() - ageMs
            this.accuracy = accuracy
        }

    @Test
    fun firstNetworkFixCommitsCancelsSignalsNoUpdates() {
        controller.startSearchWithOps(fake)
        assertEquals(2, fake.oneShots.size) // gps + network
        fake.completeOneShot("network", usable("network", 2000f))
        assertEquals(1, fixes.size)
        assertFalse(controller.updatesRequested)
        assertFalse(controller.isLocating)
    }

    @Test
    fun allOneShotsNullStartsUpdatesImmediately() {
        controller.startSearchWithOps(fake)
        fake.completeAllOneShots(null)
        assertTrue("updates must start before timeout", controller.updatesRequested)
        assertTrue(fake.updateProviders.isNotEmpty())
        assertTrue(failures.isEmpty())
        assertTrue(controller.isLocating)
    }

    @Test
    fun nullThenUpdateFixCommitsAndRemovesListener() {
        controller.startSearchWithOps(fake)
        fake.completeAllOneShots(null)
        assertTrue(controller.updatesRequested)
        val listener = fake.updateListener
        assertTrue(listener != null)
        fake.deliverUpdate(usable("gps", 30f))
        assertEquals(1, fixes.count { !it.second }) // non-provisional
        assertTrue(fake.removedListeners.contains(listener) || !controller.isLocating)
    }

    @Test
    fun secondFixAfterCommitIgnored() {
        controller.startSearchWithOps(fake)
        fake.completeOneShot("network", usable("network", 2000f))
        assertEquals(1, fixes.size)
        assertTrue(controller.firstFixGate.committed)
        // Late GPS must not produce another listener call.
        fake.completeOneShot("gps", usable("gps", 10f))
        assertEquals(1, fixes.size)
        assertTrue(controller.firstFixGate.committed)
    }

    @Test
    fun noEnabledProvidersFailsImmediately() {
        fake = FakeLocationOperations(enabled = emptySet())
        val listener = object : LocationController.Listener {
            override fun onLocationFixed(location: Location, provisional: Boolean, source: LocationSource) {
                fixes += Triple(location, provisional, source)
            }
            override fun onLocationProgress() {}
            override fun onLocationFailed(reason: LocationFailure) {
                failures += reason
            }
        }
        controller = LocationController(listener, fake, sync)
        controller.startSearchWithOps(fake)
        assertEquals(listOf(LocationFailure.PROVIDERS_UNAVAILABLE), failures)
        assertTrue(fake.oneShots.isEmpty())
        assertFalse(controller.updatesRequested)
    }
}
