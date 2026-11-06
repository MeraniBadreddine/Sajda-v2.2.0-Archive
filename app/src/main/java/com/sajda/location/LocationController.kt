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
 * Robust multi-provider search. GPS priority with NETWORK fallback.
 * Never relies on FUSED alone (breaks without GMS).
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
    private val cancelSignals = mutableListOf<CancellationSignal>()
    private var bestCurrentFix: Location? = null
    private var bestFixRunnable: Runnable? = null
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
        bestFixRunnable?.let { mainHandler.removeCallbacks(it) }
        bestFixRunnable = null
        bestCurrentFix = null
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
        locating.set(true)
        val requestId = requestGeneration
        listener.onLocationProgress()

        // Fast path: last-known (any provider), keep searching for fresher
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

        // One-shot current fixes — separate signals so one cancel does not kill all
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
                        if (!isActive(requestId)) return@getCurrentLocation
                        if (loc != null) {
                            offerCurrentFix(requestId, loc, currentMaxAgeMs)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation $provider", e)
                }
            }
        }

        if (!isActive(requestId)) return
        if (!requestUpdates(lm, requestId, currentMaxAgeMs)) {
            // Continuous updates failed — wait for one-shot / last-known only
            if (activeListener == null) {
                // still wait for timeout in case getCurrentLocation answers
            }
        }

        timeoutRunnable = Runnable {
            if (!isActive(requestId)) return@Runnable
            val best = bestCurrentFix
            if (best != null) {
                deliverFix(
                    requestId, best, provisional = false,
                    source = LocationSourceMapper.fromProvider(best.provider)
                )
            } else {
                stop(invalidate = false)
                locating.set(false)
                listener.onLocationFailed(LocationFailure.TIMEOUT)
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, timeoutMs)
    }

    private fun isActive(requestId: Int): Boolean =
        RequestGate.isActive(requestId, requestGeneration, locating.get())

    /**
     * Collect near-simultaneous provider fixes briefly instead of accepting
     * whichever callback happens to arrive first. Prefer better accuracy;
     * prefer GPS only when reported accuracies are effectively equivalent.
     */
    private fun offerCurrentFix(
        requestId: Int,
        location: Location,
        currentMaxAgeMs: Long
    ) {
        if (!isActive(requestId)) return
        if (!LocationQuality.isUsable(
                location.latitude, location.longitude, location.time,
                System.currentTimeMillis(), currentMaxAgeMs,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            )
        ) return

        val candidate = Location(location)
        val previous = bestCurrentFix
        val previousAccuracy = previous?.accuracy?.takeIf {
            previous.hasAccuracy() && it.isFinite() && it > 0f
        }
        val candidateAccuracy = candidate.accuracy.takeIf {
            candidate.hasAccuracy() && it.isFinite() && it > 0f
        }

        if (previous == null || isBetterCandidate(candidate, previous)) {
            bestCurrentFix = candidate
        }

        // Materially better accuracy → commit immediately.
        if (ProviderSettlePolicy.shouldCommitImmediately(candidateAccuracy, previousAccuracy) &&
            bestCurrentFix === candidate
        ) {
            bestFixRunnable?.let { mainHandler.removeCallbacks(it) }
            bestFixRunnable = null
            val best = bestCurrentFix
            bestCurrentFix = null
            if (best != null) {
                deliverFix(
                    requestId, best, provisional = false,
                    source = LocationSourceMapper.fromProvider(best.provider)
                )
            }
            return
        }

        val delay = ProviderSettlePolicy.settleDelayMs(bestCurrentFix?.provider)
        // Reschedule if we now have a better provider class (e.g. NETWORK → GPS).
        bestFixRunnable?.let { mainHandler.removeCallbacks(it) }
        val commit = Runnable {
            bestFixRunnable = null
            val best = bestCurrentFix
            bestCurrentFix = null
            if (best != null && isActive(requestId)) {
                deliverFix(
                    requestId, best, provisional = false,
                    source = LocationSourceMapper.fromProvider(best.provider)
                )
            }
        }
        bestFixRunnable = commit
        mainHandler.postDelayed(commit, delay)
    }

    private fun isBetterCandidate(candidate: Location, current: Location): Boolean =
        LocationCandidatePolicy.isBetter(
            candidateAccuracyMeters = candidate.accuracy.takeIf {
                candidate.hasAccuracy() && it.isFinite() && it > 0f
            },
            candidateProvider = candidate.provider,
            currentAccuracyMeters = current.accuracy.takeIf {
                current.hasAccuracy() && it.isFinite() && it > 0f
            },
            currentProvider = current.provider,
            candidateTimeMillis = candidate.time,
            currentTimeMillis = current.time
        )

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

    @SuppressLint("MissingPermission")
    private fun requestUpdates(
        lm: LocationManager,
        requestId: Int,
        currentMaxAgeMs: Long
    ): Boolean {
        val locListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!isActive(requestId)) return
                offerCurrentFix(requestId, location, currentMaxAgeMs)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeListener = locListener

        var registered = 0

        // GPS first when fine permission — real outdoor fix
        val ordered = mutableListOf<String>()
        if (hasFinePermission()) ordered.add(LocationManager.GPS_PROVIDER)
        ordered.add(LocationManager.NETWORK_PROVIDER)
        if (!hasFinePermission()) ordered.add(LocationManager.GPS_PROVIDER) // try anyway
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ordered.add(LocationManager.FUSED_PROVIDER)
        }

        for (provider in ordered.distinct()) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                lm.requestLocationUpdates(
                    provider,
                    /* minTime */ 0L,
                    /* minDistance */ 0f,
                    locListener,
                    Looper.getMainLooper()
                )
                registered++
                Log.i(TAG, "registered $provider")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException $provider (need FINE?)", e)
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates $provider", e)
            }
        }

        // Criteria-based single update as extra path
        if (registered == 0) {
            try {
                val criteria = Criteria().apply {
                    accuracy = Criteria.ACCURACY_COARSE
                    isCostAllowed = true
                }
                val best = lm.getBestProvider(criteria, true)
                if (best != null) {
                    lm.requestLocationUpdates(best, 0L, 0f, locListener, Looper.getMainLooper())
                    registered++
                    Log.i(TAG, "registered best provider $best")
                }
            } catch (e: Exception) {
                Log.w(TAG, "criteria provider", e)
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
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else emptyList()

        for (p in providers) {
            try {
                lm.getLastKnownLocation(p)?.let { candidates.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "lastKnown $p", e)
            }
        }
        val now = System.currentTimeMillis()
        return candidates
            .asSequence()
            .filter { candidate ->
                LocationQuality.isUsable(
                    candidate.latitude,
                    candidate.longitude,
                    candidate.time,
                    now,
                    CURRENT_FIX_MAX_AGE_MS,
                    accuracyMeters = if (candidate.hasAccuracy()) candidate.accuracy else null
                )
            }
            .maxByOrNull { it.time }
    }

    companion object {
        private const val TAG = "SajdaLocation"
        /** Cold GPS fix outdoors often needs 20–40s */
        const val LOCATION_TIMEOUT_MS = 45_000L
        const val CURRENT_FIX_MAX_AGE_MS = 3_600_000L
        const val LOCATION_REFRESH_INTERVAL_MS = 10L * 60L * 1000L
    }
}
