package com.sajda.location

import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.os.CancellationSignal
import android.os.Looper
import java.util.concurrent.Executor

/**
 * Hand-written fake for LocationControllerFlowTest — no Mockito.
 * One-shot consumers are stored and invoked by the test on the same thread.
 */
class FakeLocationOperations(
    private val enabled: Set<String> = setOf("gps", "network")
) : LocationOperations {
    data class OneShotReq(
        val provider: String,
        val signal: CancellationSignal,
        val consumer: (Location?) -> Unit
    )

    val oneShots = mutableListOf<OneShotReq>()
    val updateProviders = mutableListOf<String>()
    val removedListeners = mutableListOf<LocationListener>()
    var updateListener: LocationListener? = null
        private set

    override fun isProviderEnabled(provider: String): Boolean = provider in enabled

    override fun getLastKnownLocation(provider: String): Location? = null

    override fun getCurrentLocation(
        provider: String,
        signal: CancellationSignal,
        executor: Executor,
        consumer: (Location?) -> Unit
    ) {
        oneShots.add(OneShotReq(provider, signal, { loc -> executor.execute { consumer(loc) } }))
    }

    override fun requestLocationUpdates(
        provider: String,
        minTimeMs: Long,
        minDistanceM: Float,
        listener: LocationListener,
        looper: Looper
    ) {
        updateProviders.add(provider)
        updateListener = listener
    }

    override fun removeUpdates(listener: LocationListener) {
        removedListeners.add(listener)
    }

    override fun getBestProvider(criteria: Criteria, enabledOnly: Boolean): String? = null

    fun completeOneShot(provider: String, location: Location?) {
        oneShots.filter { it.provider == provider }.forEach { it.consumer(location) }
    }

    fun completeAllOneShots(location: Location?) {
        oneShots.toList().forEach { it.consumer(location) }
    }

    fun deliverUpdate(location: Location) {
        updateListener?.onLocationChanged(location)
    }
}
