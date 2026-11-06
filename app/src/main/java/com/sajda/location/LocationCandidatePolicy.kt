package com.sajda.location

/**
 * Deterministic comparison for concurrent location providers.
 *
 * Order (must stay in this order):
 *  A. Candidate materially more accurate (> ACCURACY_TIE_METERS) → always wins.
 *  B. Candidate newer by > RECENCY_ADVANTAGE_MS and accuracy ≤ 2× current → wins.
 *  C. Accuracies within ACCURACY_TIE_METERS → newer timestamp wins.
 *  D. Full tie → provider rank: gps > fused > network.
 */
object LocationCandidatePolicy {
    const val ACCURACY_TIE_METERS = 25f
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

        // A. Material accuracy advantage always wins (regardless of age).
        if (candidateAccuracy + ACCURACY_TIE_METERS < currentAccuracy) return true
        if (currentAccuracy + ACCURACY_TIE_METERS < candidateAccuracy) return false

        val timesKnown = candidateTimeMillis > 0L && currentTimeMillis > 0L
        if (timesKnown) {
            val ageAdvantage = candidateTimeMillis - currentTimeMillis

            // B. Much fresher and not worse than 2× current accuracy.
            if (ageAdvantage > RECENCY_ADVANTAGE_MS &&
                candidateAccuracy <= currentAccuracy * 2f
            ) {
                return true
            }
            if (ageAdvantage < -RECENCY_ADVANTAGE_MS &&
                currentAccuracy <= candidateAccuracy * 2f
            ) {
                return false
            }

            // C. Within accuracy-tie band → prefer newer.
            if (kotlin.math.abs(candidateAccuracy - currentAccuracy) <= ACCURACY_TIE_METERS) {
                if (ageAdvantage > 0L) return true
                if (ageAdvantage < 0L) return false
            }
        }

        // D. Provider rank.
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
