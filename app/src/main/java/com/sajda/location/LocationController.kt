package com.sajda.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.sajda.data.LocationFailure
import com.sajda.data.LocationSource
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First usable fix (LocationQuality.isUsable) commits and stops.
 * Primary path: one-shot getCurrentLocation. Continuous updates only if no one-shot registered.
 */
class LocationController(
    context: Context,
    private val listener: Listener,
    private val ops: LocationOperations? = null
) {
    interface Listener {
        fun onLocationFixed(location: Location, provisional: Boolean, source: LocationSource)
        fun onLocationProgress()
        fun onLocationFailed(reason: LocationFailure)
    }

    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    private val locationManager: LocationManager? =
        app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val locationOps: LocationOperations? =
        ops ?: locationManager?.let { AndroidLocationOperations(it) }

    private var activeListener: LocationListener? = null
    private var timeoutRunnable: Runnable? = null
    private val cancelSignals = mutableListOf<CancellationSignal>()
    private var requestGeneration = 0
    private val locating = AtomicBoolean(false)
    internal val firstFixGate = FirstFixGate()

    val isLocating: Boolean get() = locating.get()

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isSystemLocationEnabled(): Boolean {
        val lm = locationManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
            else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            Log.w(TAG, "isSystemLocationEnabled", e)
            try {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) {
                false
            }
        }
    }

    fun stop(invalidate: Boolean = true) {
        if (invalidate) {
            requestGeneration++
            firstFixGate.reset()
        }
        locating.set(false)
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        cancelSignals.forEach { s -> try { s.cancel() } catch (_: Exception) {} }
        cancelSignals.clear()
        removeUpdatesQuietly()
    }

    private fun removeUpdatesQuietly() {
        val ops = locationOps
        activeListener?.let { l ->
            try { ops?.removeUpdates(l) } catch (e: Exception) {
                Log.w(TAG, "removeUpdates", e)
            }
        }
        activeListener = null
    }

    /**
     * Test / instrumentation entry: start search assuming permission + system location OK.
     * Production uses [startSearch] which checks those first.
     */
    @SuppressLint("MissingPermission")
    fun startSearch(
        timeoutMs: Long = LOCATION_TIMEOUT_MS,
        currentMaxAgeMs: Long = CURRENT_FIX_MAX_AGE_MS
    ) {
        if (!hasPermission()) {
            listener.onLocationFailed(LocationFailure.PERMISSION_DENIED)
            return
        }
        if (!isSystemLocationEnabled()) {
            listener.onLocationFailed(LocationFailure.LOCATION_DISABLED)
            return
        }
        val ops = locationOps ?: run {
            listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
            return
        }
        startSearchWithOps(ops, timeoutMs, currentMaxAgeMs)
    }

    /** Package-visible for tests that inject [LocationOperations] without real permission. */
    internal fun startSearchWithOps(
        ops: LocationOperations,
        timeoutMs: Long = LOCATION_TIMEOUT_MS,
        currentMaxAgeMs: Long = CURRENT_FIX_MAX_AGE_MS
    ) {
        stop(invalidate = true)

        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val enabled = candidates.filter { ops.isProviderEnabled(it) }
        Log.i(TAG, "startSearch enabledProviders=$enabled")
        if (enabled.isEmpty()) {
            locating.set(false)
            listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
            return
        }

        locating.set(true)
        val requestId = requestGeneration
        listener.onLocationProgress()

        bestLastKnown(ops)?.let { last ->
            if (LocationQuality.isUsable(
                    last.latitude, last.longitude, last.time,
                    System.currentTimeMillis(), currentMaxAgeMs,
                    accuracyMeters = if (last.hasAccuracy()) last.accuracy else null
                )
            ) {
                deliverFix(
                    requestId, last, provisional = true,
                    source = LocationSource.LAST_KNOWN, stopSearch = false
                )
            }
        }

        // Primary: one-shot only.
        var oneShotRegistered = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in enabled) {
                try {
                    val signal = CancellationSignal()
                    cancelSignals.add(signal)
                    ops.getCurrentLocation(provider, signal, mainExecutor) { loc ->
                        if (!isActive(requestId) || loc == null) return@getCurrentLocation
                        commitIfUsable(requestId, loc, currentMaxAgeMs)
                    }
                    oneShotRegistered++
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation $provider", e)
                }
            }
        }

        // Fallback continuous updates only if no one-shot was registered.
        if (oneShotRegistered == 0) {
            if (!requestUpdates(ops, requestId, currentMaxAgeMs, enabled)) {
                stop(invalidate = false)
                locating.set(false)
                listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
                return
            }
        }

        timeoutRunnable = Runnable {
            if (!isActive(requestId)) return@Runnable
            stop(invalidate = false)
            locating.set(false)
            listener.onLocationFailed(LocationFailure.TIMEOUT)
        }
        mainHandler.postDelayed(timeoutRunnable!!, timeoutMs)
    }

    private fun isActive(requestId: Int): Boolean =
        requestId == requestGeneration && locating.get()

    private fun commitIfUsable(requestId: Int, location: Location, currentMaxAgeMs: Long) {
        if (!isActive(requestId)) return
        val usable = LocationQuality.isUsable(
            location.latitude, location.longitude, location.time,
            System.currentTimeMillis(), currentMaxAgeMs,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        )
        if (!firstFixGate.tryCommit(usable)) return
        deliverFix(
            requestId, location, provisional = false,
            source = LocationSourceMapper.fromProvider(location.provider),
            stopSearch = true
        )
    }

    private fun deliverFix(
        requestId: Int,
        location: Location,
        provisional: Boolean,
        source: LocationSource,
        stopSearch: Boolean = true
    ) {
        if (requestId != requestGeneration) return
        if (stopSearch) {
            stop(invalidate = false)
            locating.set(false)
        }
        listener.onLocationFixed(Location(location), provisional, source)
    }

    private fun requestUpdates(
        ops: LocationOperations,
        requestId: Int,
        currentMaxAgeMs: Long,
        providers: List<String>
    ): Boolean {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                commitIfUsable(requestId, location, currentMaxAgeMs)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeListener = listener
        var registered = 0
        for (p in providers) {
            try {
                ops.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper())
                registered++
                Log.i(TAG, "registered updates $p")
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates $p", e)
            }
        }
        if (registered == 0) {
            try {
                val criteria = Criteria().apply {
                    accuracy = Criteria.ACCURACY_COARSE
                    isCostAllowed = true
                }
                val best = ops.getBestProvider(criteria, true)
                if (best != null) {
                    ops.requestLocationUpdates(best, 1_000L, 0f, listener, Looper.getMainLooper())
                    registered = 1
                    Log.i(TAG, "registered best $best")
                }
            } catch (e: Exception) {
                Log.w(TAG, "bestProvider", e)
            }
        }
        if (registered == 0) {
            activeListener = null
            return false
        }
        return true
    }

    fun bestLastKnown(ops: LocationOperations? = locationOps): Location? {
        val operations = ops ?: return null
        val now = System.currentTimeMillis()
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else emptyList()
        return providers.mapNotNull { p -> operations.getLastKnownLocation(p) }
            .filter { loc ->
                LocationQuality.isUsable(
                    loc.latitude, loc.longitude, loc.time, now,
                    CURRENT_FIX_MAX_AGE_MS,
                    accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null
                )
            }
            .minByOrNull { loc ->
                lastKnownScore(
                    accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
                    timeMillis = loc.time,
                    nowMs = now
                )
            }
    }

    companion object {
        private const val TAG = "SajdaLoc"
        const val LOCATION_TIMEOUT_MS = 45_000L
        const val CURRENT_FIX_MAX_AGE_MS = 5 * 60_000L
        const val LOCATION_REFRESH_INTERVAL_MS = 10 * 60_000L

        fun lastKnownScore(accuracyMeters: Float?, timeMillis: Long, nowMs: Long): Double {
            val accuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 5_000.0
            val ageMinutes = ((nowMs - timeMillis).coerceAtLeast(0L) / 60_000.0)
            return accuracy + ageMinutes.coerceAtLeast(0.0)
        }
    }
}
