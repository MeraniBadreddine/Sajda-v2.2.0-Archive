package com.sajda.session

import com.sajda.compass.Confidence
import com.sajda.compass.CompassHint
import com.sajda.data.LocationCache
import com.sajda.data.LocationSource
import com.sajda.data.UserLocation
import com.sajda.geo.GeoPoint
import com.sajda.geo.MagneticHeadingDeg
import com.sajda.geo.QiblaEngine
import com.sajda.geo.QiblaSession
import com.sajda.geo.TrueHeadingDeg

/**
 * Owns the current [QiblaSession] and persists location fixes.
 * No View / Activity references.
 */
class SessionRepository(
    private val store: LocationCache
) {
    var session: QiblaSession? = null
        private set

    var provisionalLocation: Boolean = false
        private set

    var lastFreshLocationAt: Long = 0L
        private set

    var lastSource: LocationSource = LocationSource.GPS
        private set

    fun applyPoint(
        point: GeoPoint,
        provisional: Boolean,
        fixTimeMillis: Long = System.currentTimeMillis(),
        source: LocationSource = LocationSource.GPS,
        accuracyMeters: Float? = null
    ): QiblaSession? {
        if (!point.isValid()) return session
        provisionalLocation = provisional
        lastSource = source
        val safeAccuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0f }
        val solution = QiblaEngine.solveDetailed(point)
        store.save(UserLocation(point.latDeg, point.lonDeg, fixTimeMillis, source, safeAccuracy))
        if (!provisional) lastFreshLocationAt = System.currentTimeMillis()
        val prev = session
        session = QiblaSession(
            point = point,
            qibla = solution.bearing,
            trueHeading = prev?.trueHeading,
            magneticHeading = prev?.magneticHeading,
            // LOW until first sensor frame — avoids false calibrate figure-eight flash.
            confidence = prev?.confidence ?: Confidence.LOW,
            interferenceHint = prev?.interferenceHint,
            locationProvisional = provisional,
            locationAccuracyMeters = safeAccuracy,
            atKaaba = solution.atKaaba,
            distanceMeters = solution.distanceMeters
        )
        return session
    }

    fun updateHeading(
        trueHeading: TrueHeadingDeg,
        magnetic: MagneticHeadingDeg,
        confidence: Confidence,
        interferenceHint: CompassHint?
    ): QiblaSession? {
        val s = session ?: return null
        session = s.copy(
            trueHeading = trueHeading,
            magneticHeading = magnetic,
            confidence = confidence,
            interferenceHint = interferenceHint
        )
        return session
    }

    fun loadCachedIfAny(): UserLocation? = store.load()

    fun clearSession() {
        session = null
        provisionalLocation = false
    }


    /** After sensors stop, heading must not remain trusted. */
    fun clearHeadingTrust() {
        val s = session ?: return
        // Clear heading only — do NOT force CALIBRATE (that flashes a false figure-eight on resume).
        session = s.copy(
            trueHeading = null,
            magneticHeading = null,
            interferenceHint = null
        )
    }
}
