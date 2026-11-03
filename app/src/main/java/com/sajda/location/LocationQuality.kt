package com.sajda.location

/**
 * Pure location acceptance rules — unit-testable without Android Location.
 * Accuracy is soft: missing accuracy does not reject; very poor accuracy does.
 */
object LocationQuality {
    /** Reject only when the provider reports a very large horizontal error. */
    const val MAX_ACCEPTABLE_ACCURACY_M = 5_000f

    fun isValidCoordinate(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    fun isAcceptableAge(fixTimeMillis: Long, nowMillis: Long, maxAgeMs: Long): Boolean {
        val age = nowMillis - fixTimeMillis
        return age in -120_000L..maxAgeMs
    }

    /**
     * @param accuracyMeters null or ≤0 means "unknown" — do not reject.
     */
    fun isAcceptableAccuracy(accuracyMeters: Float?): Boolean {
        if (accuracyMeters == null || accuracyMeters <= 0f) return true
        return accuracyMeters.isFinite() && accuracyMeters <= MAX_ACCEPTABLE_ACCURACY_M
    }

    fun isUsable(
        lat: Double,
        lon: Double,
        fixTimeMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMs: Long = LocationController.CURRENT_FIX_MAX_AGE_MS,
        accuracyMeters: Float? = null
    ): Boolean = isValidCoordinate(lat, lon) &&
        isAcceptableAge(fixTimeMillis, nowMillis, maxAgeMs) &&
        isAcceptableAccuracy(accuracyMeters)
}
