package com.sajda.location

import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.os.CancellationSignal
import android.os.Looper
import java.util.concurrent.Executor

/**
 * Thin seam over LocationManager so unit tests can drive first-fix policy
 * without a real system service.
 */
interface LocationOperations {
    fun isProviderEnabled(provider: String): Boolean
    fun getLastKnownLocation(provider: String): Location?
    fun getCurrentLocation(
        provider: String,
        signal: CancellationSignal,
        executor: Executor,
        consumer: (Location?) -> Unit
    )
    fun requestLocationUpdates(
        provider: String,
        minTimeMs: Long,
        minDistanceM: Float,
        listener: LocationListener,
        looper: Looper
    )
    fun removeUpdates(listener: LocationListener)
    fun getBestProvider(criteria: Criteria, enabledOnly: Boolean): String?
}
