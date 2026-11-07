package com.sajda.location

import android.annotation.SuppressLint
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import java.util.concurrent.Executor

/** Production wrapper — no behavior beyond LocationManager. */
class AndroidLocationOperations(
    private val lm: LocationManager
) : LocationOperations {
    override fun isProviderEnabled(provider: String): Boolean =
        try { lm.isProviderEnabled(provider) } catch (_: Exception) { false }

    @SuppressLint("MissingPermission")
    override fun getLastKnownLocation(provider: String): Location? =
        try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    override fun getCurrentLocation(
        provider: String,
        signal: CancellationSignal,
        executor: Executor,
        consumer: (Location?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            consumer(null)
            return
        }
        lm.getCurrentLocation(provider, signal, executor) { loc -> consumer(loc) }
    }

    @SuppressLint("MissingPermission")
    override fun requestLocationUpdates(
        provider: String,
        minTimeMs: Long,
        minDistanceM: Float,
        listener: LocationListener,
        looper: Looper
    ) {
        lm.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, looper)
    }

    override fun removeUpdates(listener: LocationListener) {
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
    }

    override fun getBestProvider(criteria: Criteria, enabledOnly: Boolean): String? =
        try { lm.getBestProvider(criteria, enabledOnly) } catch (_: Exception) { null }
}
