package com.sajda.session

/**
 * Trust in the geographic fix, independent of magnetometer confidence.
 * Derived from reported horizontal accuracy and provisional flag.
 */
enum class LocationTrust {
    /** ≤100 m, non-provisional. */
    GOOD,
    /** ≤2000 m or provisional with some accuracy. */
    WEAK,
    /** Missing / unusable accuracy or pure provisional city-level. */
    UNKNOWN;

    companion object {
        const val GOOD_MAX_M = 100f
        const val WEAK_MAX_M = 2_000f

        fun from(accuracyMeters: Float?, provisional: Boolean): LocationTrust {
            val acc = accuracyMeters?.takeIf { it.isFinite() && it > 0f }
            return when {
                provisional && acc == null -> UNKNOWN
                acc == null -> UNKNOWN
                acc <= GOOD_MAX_M && !provisional -> GOOD
                acc <= WEAK_MAX_M -> WEAK
                else -> UNKNOWN
            }
        }
    }
}
