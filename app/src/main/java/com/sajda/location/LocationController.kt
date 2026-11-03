package com.sajda.location

import com.sajda.data.LocationFailure
import com.sajda.data.LocationSource

import android.util.Log

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-provider location search with independent provider registration.
 * A failure on one provider never blocks the others; total failure removes
 * any partial registrations before notifying the UI.
 */
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
    private var cancelSignal = CancellationSignal()
    private var requestGeneration = 0
    private val locating = AtomicBoolean(false)

    val isLocating: Boolean get() = locating.get()

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun isSystemLocationEnabled(): Boolean {
        val lm = locationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
                Log.w(TAG, "provider path", e)
            false
        }
    }

    fun stop(invalidate: Boolean = true) {
        if (invalidate) requestGeneration++
        locating.set(false)
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        try { cancelSignal.cancel() } catch (e: Exception) {
                Log.w(TAG, "provider path", e)
            }
        removeUpdatesQuietly()
    }

    private fun removeUpdatesQuietly() {
        activeListener?.let {
            try { locationManager?.removeUpdates(it) } catch (e: Exception) {
                Log.w(TAG, "provider path", e)
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
        locating.set(true)
        val requestId = requestGeneration
        listener.onLocationProgress()

        // Fast path: show last-known immediately, but KEEP searching for a fresher fix.
        bestLastKnown(lm)?.let { last ->
            if (LocationQuality.isUsable(last.latitude, last.longitude, last.time, System.currentTimeMillis(), currentMaxAgeMs)) {
                deliverFix(requestId, last, provisional = true, source = LocationSource.LAST_KNOWN, stopSearch = false)
            }
        }

        cancelSignal = CancellationSignal()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                lm.getCurrentLocation(
                    LocationManager.FUSED_PROVIDER,
                    cancelSignal,
                    mainExecutor
                ) { loc ->
                    if (!isActive(requestId)) return@getCurrentLocation
                    if (loc != null && LocationQuality.isUsable(
                            loc.latitude, loc.longitude, loc.time,
                            System.currentTimeMillis(), currentMaxAgeMs,
                            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null
                        )
                    ) {
                        deliverFix(
                            requestId, loc, provisional = false,
                            source = sourceFromProvider(loc.provider)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "provider path", e)
                // continue with updates
            }
        }

        if (!isActive(requestId)) return
        if (!requestUpdates(lm, requestId, currentMaxAgeMs)) {
            locating.set(false)
            removeUpdatesQuietly()
            // If we already delivered provisional, keep UI; only fail hard with no session chance
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
        RequestGate.isActive(requestId, requestGeneration, locating.get())

    private fun deliverFix(
        requestId: Int,
        location: Location,
        provisional: Boolean,
        source: LocationSource,
        stopSearch: Boolean = !provisional
    ) {
        if (!isActive(requestId)) return
        if (stopSearch) {
            stop(invalidate = false)
            locating.set(false)
        }
        listener.onLocationFixed(location, provisional, source)
    }

    private fun sourceFromProvider(provider: String?): LocationSource = when (provider) {
        LocationManager.GPS_PROVIDER -> LocationSource.GPS
        LocationManager.NETWORK_PROVIDER -> LocationSource.NETWORK
        LocationManager.FUSED_PROVIDER -> LocationSource.FUSED
        else -> LocationSource.FUSED
    }

    /**
     * Registers each provider independently. Returns true if at least one succeeded.
     * On total failure, removes any partial registration before returning false.
     */
    @SuppressLint("MissingPermission")
    private fun requestUpdates(
        lm: LocationManager,
        requestId: Int,
        currentMaxAgeMs: Long
    ): Boolean {
        val locListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!isActive(requestId)) return
                if (LocationQuality.isUsable(
                        location.latitude, location.longitude, location.time,
                        System.currentTimeMillis(), currentMaxAgeMs,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
                    )
                ) {
                    deliverFix(
                        requestId, location, provisional = false,
                        source = sourceFromProvider(location.provider)
                    )
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeListener = locListener

        val providers = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }

        var registered = 0
        for (provider in providers) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                lm.requestLocationUpdates(provider, 0L, 0f, locListener, Looper.getMainLooper())
                registered++
            } catch (e: Exception) {
                Log.w(TAG, "provider path", e)
                // This provider failed; others may still work
            }
        }

        if (registered == 0) {
            removeUpdatesQuietly()
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        if (!hasPermission()) return null
        val candidates = mutableListOf<Location>()
        val providers = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }
        for (p in providers) {
            try {
                lm.getLastKnownLocation(p)?.let { candidates.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "provider path", e)
            }
        }
        return candidates.maxByOrNull { it.time }
    }

    companion object {
        private const val TAG = "SajdaLocation"
        const val LOCATION_TIMEOUT_MS = 25_000L
        const val CURRENT_FIX_MAX_AGE_MS = 3_600_000L // 1h — Qibla tolerates coarse location
        const val LOCATION_REFRESH_INTERVAL_MS = 10L * 60L * 1000L // 10 min — travel without Clear
    }
}
