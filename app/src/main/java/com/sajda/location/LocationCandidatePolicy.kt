package com.sajda.location

/**
 * Deterministic comparison for concurrent location providers.
 * Accuracy is primary; recency and provider rank break ties.
 */
object LocationCandidatePolicy {
    const val ACCURACY_TIE_METERS = 25f
    /** Prefer a newer fix when it is at least this much newer. */
    const val RECENCY_ADVANTAGE_MS = 60_000L

    /**
     * @return true if the candidate should replace the current best.
     */
    fun isBetter(
        candidateAccuracyMeters: Float?,
        candidateProvider: String?,
        currentAccuracyMeters: Float?,
        currentProvider: String?,
        candidateTimeMillis: Long = 0L,
        currentTimeMillis: Long = 0L
    ): Boolean {
        val candidateAccuracy = normalizedAccuracy(candidateAccuracyMeters)
        val currentAccuracy = normalizedAccuracy(currentAccuracyMeters)

        // Materially better accuracy always wins.
        if (candidateAccuracy + ACCURACY_TIE_METERS < currentAccuracy) return true
        if (currentAccuracy + ACCURACY_TIE_METERS < candidateAccuracy) return false

        // Near-equal accuracy band: prefer the newer fix when timestamps are known.
        val timesKnown = candidateTimeMillis > 0L && currentTimeMillis > 0L
        if (timesKnown) {
            val ageAdvantage = candidateTimeMillis - currentTimeMillis
            // Newer by >60s and not worse than 2× current accuracy → take newer.
            if (ageAdvantage > RECENCY_ADVANTAGE_MS &&
                candidateAccuracy <= currentAccuracy * 2f
            ) {
                return true
            }
            // Older by >60s and not better than half → keep current (reject candidate).
            if (ageAdvantage < -RECENCY_ADVANTAGE_MS &&
                candidateAccuracy >= currentAccuracy * 0.5f
            ) {
                return false
            }
            // Within accuracy-tie band: prefer newer.
            if (kotlin.math.abs(candidateAccuracy - currentAccuracy) <= ACCURACY_TIE_METERS) {
                if (ageAdvantage > 0L) return true
                if (ageAdvantage < 0L) return false
            }
        }

        return providerRank(candidateProvider) > providerRank(currentProvider)
    }

    private fun normalizedAccuracy(accuracyMeters: Float?): Float =
        accuracyMeters?.takeIf { it.isFinite() && it > 0f } ?: Float.MAX_VALUE

    private fun providerRank(provider: String?): Int = when (provider) {
        "gps" -> 3
        "fused" -> 2
        "network" -> 1
        else -> 0
    }
}
