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
import android.util.Log
import androidx.core.content.ContextCompat
import com.sajda.data.LocationFailure
import com.sajda.data.LocationSource
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** First usable fix commits and stops. One-shots first; if all return null, start updates immediately. */
class LocationController private constructor(
    private val app: Context?,
    private val listener: Listener,
    private val locationOps: LocationOperations?,
    private val mainExecutor: Executor,
    private val scheduleTimeout: (Runnable, Long) -> Unit,
    private val cancelTimeout: (Runnable) -> Unit,
    private val testBypassPermission: Boolean
) {
    constructor(
        context: Context,
        listener: Listener,
        ops: LocationOperations? = null
    ) : this(
        app = context.applicationContext,
        listener = listener,
        locationOps = ops ?: (context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            ?.let { AndroidLocationOperations(it) },
        mainExecutor = Executor { Handler(Looper.getMainLooper()).post(it) },
        scheduleTimeout = { r, ms -> Handler(Looper.getMainLooper()).postDelayed(r, ms) },
        cancelTimeout = { r -> Handler(Looper.getMainLooper()).removeCallbacks(r) },
        testBypassPermission = false
    )

    internal constructor(
        listener: Listener,
        ops: LocationOperations,
        executor: Executor = Executor { it.run() }
    ) : this(
        app = null,
        listener = listener,
        locationOps = ops,
        mainExecutor = executor,
        scheduleTimeout = { _, _ -> },
        cancelTimeout = { },
        testBypassPermission = true
    )

    interface Listener {
        fun onLocationFixed(location: Location, provisional: Boolean, source: LocationSource)
        fun onLocationProgress()
        fun onLocationFailed(reason: LocationFailure)
    }

    private val locationManager: LocationManager? =
        app?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var activeListener: LocationListener? = null
    private var timeoutRunnable: Runnable? = null
    private val cancelSignals = mutableListOf<CancellationSignal>()
    private var requestGeneration = 0
    private val locating = AtomicBoolean(false)
    private val oneShotsPending = AtomicInteger(0)
    internal val firstFixGate = FirstFixGate()

    @Volatile
    var updatesRequested: Boolean = false
        private set

    val isLocating: Boolean get() = locating.get()

    fun hasPermission(): Boolean {
        if (testBypassPermission) return true
        val ctx = app ?: return false
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun isSystemLocationEnabled(): Boolean {
        if (testBypassPermission) return true
        val lm = locationManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
            else @Suppress("DEPRECATION") (
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
        } catch (e: Exception) { Log.w(TAG, "isSystemLocationEnabled", e); false }
    }

    fun stop(invalidate: Boolean = true) {
        if (invalidate) { requestGeneration++; firstFixGate.reset() }
        locating.set(false)
        oneShotsPending.set(0)
        timeoutRunnable?.let { cancelTimeout(it) }
        timeoutRunnable = null
        cancelSignals.forEach { s -> try { s.cancel() } catch (_: Exception) {} }
        cancelSignals.clear()
        activeListener?.let { l -> try { locationOps?.removeUpdates(l) } catch (e: Exception) { Log.w(TAG, "removeUpdates", e) } }
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
        val ops = locationOps ?: run {
            listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
            return
        }
        startSearchWithOps(ops, timeoutMs, currentMaxAgeMs)
    }

    internal fun startSearchWithOps(
        ops: LocationOperations,
        timeoutMs: Long = LOCATION_TIMEOUT_MS,
        currentMaxAgeMs: Long = CURRENT_FIX_MAX_AGE_MS
    ) {
        stop(invalidate = true)
        updatesRequested = false

        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER
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

        // Primary: one-shot for every enabled provider (ops seam — no SDK gate).
        oneShotsPending.set(0)
        for (provider in enabled) {
            try {
                val signal = CancellationSignal()
                cancelSignals.add(signal)
                oneShotsPending.incrementAndGet()
                ops.getCurrentLocation(provider, signal, mainExecutor) { loc ->
                    onOneShotResult(requestId, loc, currentMaxAgeMs, ops, enabled)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation $provider", e)
                onOneShotResult(requestId, null, currentMaxAgeMs, ops, enabled)
            }
        }

        if (oneShotsPending.get() == 0) {
            // No one-shot registered at all → updates immediately.
            startUpdatesFallback(ops, requestId, currentMaxAgeMs, enabled)
        }

        val timeout = Runnable {
            if (!isActive(requestId)) return@Runnable
            stop(invalidate = false)
            locating.set(false)
            listener.onLocationFailed(LocationFailure.TIMEOUT)
        }
        timeoutRunnable = timeout
        scheduleTimeout(timeout, timeoutMs)
    }

    /** Decrement pending; last null without commit → updates. */
    private fun onOneShotResult(
        requestId: Int,
        loc: Location?,
        currentMaxAgeMs: Long,
        ops: LocationOperations,
        enabled: List<String>
    ) {
        if (loc != null) {
            commitIfUsable(requestId, loc, currentMaxAgeMs)
        }
        val left = oneShotsPending.decrementAndGet()
        if (left == 0 && isActive(requestId) && !firstFixGate.committed) {
            startUpdatesFallback(ops, requestId, currentMaxAgeMs, enabled)
        }
    }

    private fun startUpdatesFallback(
        ops: LocationOperations,
        requestId: Int,
        currentMaxAgeMs: Long,
        enabled: List<String>
    ) {
        if (!isActive(requestId) || firstFixGate.committed) return
        if (updatesRequested) return
        if (!requestUpdates(ops, requestId, currentMaxAgeMs, enabled)) {
            stop(invalidate = false)
            locating.set(false)
            listener.onLocationFailed(LocationFailure.PROVIDERS_UNAVAILABLE)
        }
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
        val locListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                commitIfUsable(requestId, location, currentMaxAgeMs)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeListener = locListener
        var registered = 0
        for (p in providers) {
            try {
                ops.requestLocationUpdates(p, 1_000L, 0f, locListener, Looper.getMainLooper())
                registered++
                Log.i(TAG, "registered updates $p")
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates $p", e)
            }
        }
        if (registered == 0) {
            activeListener = null
            return false
        }
        updatesRequested = true
        return true
    }

    fun bestLastKnown(ops: LocationOperations? = locationOps): Location? {
        val operations = ops ?: return null
        val now = System.currentTimeMillis()
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.FUSED_PROVIDER
        )
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
