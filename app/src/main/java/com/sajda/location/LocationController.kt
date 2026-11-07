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

/** First usable fix (LocationQuality.isUsable) commits and stops. Qibla needs no multi-provider settle; near-Kaaba is gated by LocationTrust.GOOD + DisplayPolicy. Last-known may be provisional while live search continues. */
class LocationController(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLocationFixed(location: Location, provisional: Boolean, source: LocationSource)
        fun onLocationProgress()
        fun onLocationFailed(reason: LocationFailure)
    }

    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    private var locationManager: LocationManager? =
        app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var activeListener: LocationListener? = null
    private var timeoutRunnable: Runnable? = null
    private val cancelSignals = mutableListOf<CancellationSignal>()
    private var requestGeneration = 0
    private val locating = AtomicBoolean(false)

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
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
        if (invalidate) requestGeneration++
        locating.set(false)
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        cancelSignals.forEach { s ->
            try { s.cancel() } catch (_: Exception) {}
        }
        cancelSignals.clear()
        removeUpdatesQuietly()
    }

    private fun removeUpdatesQuietly() {
        activeListener?.let {
            try { locationManager?.removeUpdates(it) } catch (e: Exception) {
                Log.w(TAG, "removeUpdates", e)
            }
        }
        activeListener = null
    }

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
        val lm = locationManager ?: run {
            listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
            return
        }

        stop(invalidate = true)

        val allProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else emptyList()
        val enabledProviders = allProviders.filter { p ->
            try { lm.isProviderEnabled(p) } catch (_: Exception) { false }
        }
        Log.i(TAG, "startSearch enabledProviders=$enabledProviders")

        val canOneShot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            enabledProviders.any {
                it == LocationManager.GPS_PROVIDER ||
                    it == LocationManager.NETWORK_PROVIDER ||
                    it == LocationManager.FUSED_PROVIDER
            }
        if (enabledProviders.isEmpty()) {
            locating.set(false)
            listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
            return
        }

        locating.set(true)
        val requestId = requestGeneration
        listener.onLocationProgress()

        // Provisional last-known; keep searching for a live fix.
        bestLastKnown(lm)?.let { last ->
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val oneShot = buildList {
                if (hasFinePermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    add(LocationManager.GPS_PROVIDER)
                }
                add(LocationManager.NETWORK_PROVIDER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
            }
            for (provider in oneShot) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue
                    val signal = CancellationSignal()
                    cancelSignals.add(signal)
                    lm.getCurrentLocation(provider, signal, mainExecutor) { loc ->
                        if (!isActive(requestId) || loc == null) return@getCurrentLocation
                        commitIfUsable(requestId, loc, currentMaxAgeMs)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation $provider", e)
                }
            }
        }

        if (!isActive(requestId)) return
        val updatesRegistered = requestUpdates(lm, requestId, currentMaxAgeMs)
        if (!updatesRegistered && !canOneShot) {
            stop(invalidate = false)
            locating.set(false)
            listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
            return
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

    /**
     * First usable non-provisional fix wins and stops the search.
     */
    private fun commitIfUsable(requestId: Int, location: Location, currentMaxAgeMs: Long) {
        if (!isActive(requestId)) return
        if (!LocationQuality.isUsable(
                location.latitude, location.longitude, location.time,
                System.currentTimeMillis(), currentMaxAgeMs,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            )
        ) return
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

    @SuppressLint("MissingPermission")
    private fun requestUpdates(
        lm: LocationManager,
        requestId: Int,
        currentMaxAgeMs: Long
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
        val providers = buildList {
            if (hasFinePermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }
        for (p in providers) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper())
                registered++
                Log.i(TAG, "registered $p")
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
                val best = lm.getBestProvider(criteria, true)
                if (best != null) {
                    lm.requestLocationUpdates(best, 1_000L, 0f, listener, Looper.getMainLooper())
                    registered = 1
                    Log.i(TAG, "registered best provider $best")
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

    /**
     * Prefer a usable last-known with lower score = better:
     * (accuracy ?: 5000) + max(0, ageMinutes).
     * A sharp old fix beats a vague fresh one for Qibla.
     */
    @SuppressLint("MissingPermission")
    fun bestLastKnown(lm: LocationManager? = locationManager): Location? {
        val manager = lm ?: return null
        val now = System.currentTimeMillis()
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else emptyList()

        return providers.mapNotNull { p ->
            try { manager.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.filter { loc ->
            LocationQuality.isUsable(
                loc.latitude, loc.longitude, loc.time, now,
                CURRENT_FIX_MAX_AGE_MS,
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null
            )
        }.minByOrNull { loc ->
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

        /** Lower is better: (accuracy ?: 5000) + max(0, ageMinutes). */
        fun lastKnownScore(accuracyMeters: Float?, timeMillis: Long, nowMs: Long): Double {
            val accuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 5_000.0
            val ageMinutes = ((nowMs - timeMillis).coerceAtLeast(0L) / 60_000.0)
            return accuracy + ageMinutes.coerceAtLeast(0.0)
        }
    }
}
