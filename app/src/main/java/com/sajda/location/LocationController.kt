package com.sajda.location

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
        fun onLocationFixed(location: Location, provisional: Boolean)
        fun onLocationProgress()
        fun onLocationTimeout()
        fun onLocationUnavailable()
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
        } catch (_: Exception) {
            false
        }
    }

    fun stop(invalidate: Boolean = true) {
        if (invalidate) requestGeneration++
        locating.set(false)
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        try { cancelSignal.cancel() } catch (_: Exception) {}
        removeUpdatesQuietly()
    }

    private fun removeUpdatesQuietly() {
        activeListener?.let {
            try { locationManager?.removeUpdates(it) } catch (_: Exception) {}
        }
        activeListener = null
    }

    @SuppressLint("MissingPermission")
    fun startSearch(
        timeoutMs: Long = LOCATION_TIMEOUT_MS,
        currentMaxAgeMs: Long = CURRENT_FIX_MAX_AGE_MS
    ) {
        if (!hasPermission()) {
            listener.onLocationUnavailable()
            return
        }
        if (!isSystemLocationEnabled()) {
            listener.onLocationUnavailable()
            return
        }
        val lm = locationManager ?: run {
            listener.onLocationUnavailable()
            return
        }

        stop(invalidate = true)
        locating.set(true)
        val requestId = requestGeneration
        listener.onLocationProgress()

        bestLastKnown(lm)?.let { last ->
            if (LocationQuality.isUsable(last.latitude, last.longitude, last.time, System.currentTimeMillis(), currentMaxAgeMs)) {
                deliverFix(requestId, last, provisional = false)
                return
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
                            System.currentTimeMillis(), currentMaxAgeMs
                        )
                    ) {
                        deliverFix(requestId, loc, provisional = false)
                    }
                }
            } catch (_: Exception) {
                // continue with updates
            }
        }

        if (!isActive(requestId)) return
        if (!requestUpdates(lm, requestId, currentMaxAgeMs)) {
            // No provider registered — clean and fail
            locating.set(false)
            removeUpdatesQuietly()
            listener.onLocationUnavailable()
            return
        }

        timeoutRunnable = Runnable {
            if (!isActive(requestId)) return@Runnable
            stop(invalidate = false)
            locating.set(false)
            listener.onLocationTimeout()
        }
        mainHandler.postDelayed(timeoutRunnable!!, timeoutMs)
    }

    private fun isActive(requestId: Int): Boolean =
        RequestGate.isActive(requestId, requestGeneration, locating.get())

    private fun deliverFix(requestId: Int, location: Location, provisional: Boolean) {
        if (!isActive(requestId)) return
        stop(invalidate = false)
        locating.set(false)
        listener.onLocationFixed(location, provisional)
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
                        System.currentTimeMillis(), currentMaxAgeMs
                    )
                ) {
                    deliverFix(requestId, location, provisional = false)
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {}
        }
        return candidates.maxByOrNull { it.time }
    }

    companion object {
        const val LOCATION_TIMEOUT_MS = 25_000L
        const val CURRENT_FIX_MAX_AGE_MS = 120_000L
        const val LOCATION_REFRESH_INTERVAL_MS = 15L * 60L * 1000L
    }
}
