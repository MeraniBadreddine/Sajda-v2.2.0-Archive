package com.sajda.session

import com.sajda.compass.Confidence
import com.sajda.data.LocationCache
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

    fun applyPoint(
        point: GeoPoint,
        provisional: Boolean,
        fixTimeMillis: Long = System.currentTimeMillis()
    ): QiblaSession? {
        if (!point.isValid()) return session
        provisionalLocation = provisional
        val qibla = QiblaEngine.solve(point)
        store.save(UserLocation(point.latDeg, point.lonDeg, fixTimeMillis))
        if (!provisional) lastFreshLocationAt = System.currentTimeMillis()
        val prev = session
        session = QiblaSession(
            point = point,
            qibla = qibla,
            trueHeading = prev?.trueHeading,
            magneticHeading = prev?.magneticHeading,
            confidence = prev?.confidence ?: Confidence.CALIBRATE,
            interferenceHint = prev?.interferenceHint,
            locationProvisional = provisional
        )
        return session
    }

    fun updateHeading(
        trueHeading: TrueHeadingDeg,
        magnetic: MagneticHeadingDeg,
        confidence: Confidence,
        interferenceHint: String?
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
        session = s.copy(
            trueHeading = null,
            magneticHeading = null,
            confidence = Confidence.CALIBRATE,
            interferenceHint = null
        )
    }
}

