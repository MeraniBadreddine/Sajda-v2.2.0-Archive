package com.sajda.location

/** Pure location acceptance rules — unit-testable without Android Location. */
object LocationQuality {
    fun isValidCoordinate(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    fun isAcceptableAge(fixTimeMillis: Long, nowMillis: Long, maxAgeMs: Long): Boolean {
        val age = nowMillis - fixTimeMillis
        return age in -120_000L..maxAgeMs
    }

    fun isUsable(
        lat: Double,
        lon: Double,
        fixTimeMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMs: Long = LocationController.CURRENT_FIX_MAX_AGE_MS
    ): Boolean = isValidCoordinate(lat, lon) && isAcceptableAge(fixTimeMillis, nowMillis, maxAgeMs)
}
