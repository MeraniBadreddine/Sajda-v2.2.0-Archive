package com.sajda.location

/**
 * How long to wait for competing providers before committing a fix.
 * Pure logic — no System clock.
 */
object ProviderSettlePolicy {
    const val SETTLE_WITH_GPS_MS = 5_000L
    const val SETTLE_WITHOUT_GPS_MS = 15_000L
    const val MATERIAL_ACCURACY_GAP_M = LocationCandidatePolicy.ACCURACY_TIE_METERS

    /**
     * @param bestProvider provider string of the current best candidate (e.g. "gps").
     * @return settle delay in milliseconds.
     */
    fun settleDelayMs(bestProvider: String?): Long =
        if (bestProvider == "gps") SETTLE_WITH_GPS_MS else SETTLE_WITHOUT_GPS_MS

    /**
     * A candidate that is materially more accurate than the previous best
     * may be committed immediately (no settle wait).
     */
    fun shouldCommitImmediately(
        candidateAccuracyMeters: Float?,
        previousBestAccuracyMeters: Float?
    ): Boolean {
        val c = candidateAccuracyMeters?.takeIf { it.isFinite() && it > 0f } ?: return false
        val p = previousBestAccuracyMeters?.takeIf { it.isFinite() && it > 0f } ?: return true
        return c + MATERIAL_ACCURACY_GAP_M < p
    }
}
