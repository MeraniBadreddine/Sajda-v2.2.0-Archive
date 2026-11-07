package com.sajda.session

import com.sajda.geo.QiblaEngine

/**
 * Shared UI gates — keep display decisions out of Activity.
 */
object DisplayPolicy {
    /**
     * Show the special "at Kaaba" UI only when:
     * - the engine flagged atKaaba (distance < NEAR_KAABA_METERS),
     * - location trust is GOOD,
     * - and distance + accuracy still falls inside the near-Kaaba radius
     *   (user might be outside the real zone within the error margin).
     */
    fun shouldShowAtKaaba(
        atKaaba: Boolean,
        distanceMeters: Double?,
        accuracyMeters: Float?,
        trust: LocationTrust
    ): Boolean {
        if (!atKaaba || trust != LocationTrust.GOOD) return false
        val distance = distanceMeters ?: return false
        val accuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0f } ?: return false
        return distance + accuracy <= QiblaEngine.NEAR_KAABA_METERS
    }
}
