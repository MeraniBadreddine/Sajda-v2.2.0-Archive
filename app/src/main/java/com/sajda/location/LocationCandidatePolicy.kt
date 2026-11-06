package com.sajda.location

/**
 * Deterministic comparison for concurrent location providers.
 * Horizontal accuracy wins; provider preference breaks near-equal ties.
 */
object LocationCandidatePolicy {
    private const val ACCURACY_TIE_METERS = 25f

    fun isBetter(
        candidateAccuracyMeters: Float?,
        candidateProvider: String?,
        currentAccuracyMeters: Float?,
        currentProvider: String?
    ): Boolean {
        val candidateAccuracy = candidateAccuracyMeters
            ?.takeIf { it.isFinite() && it > 0f } ?: Float.MAX_VALUE
        val currentAccuracy = currentAccuracyMeters
            ?.takeIf { it.isFinite() && it > 0f } ?: Float.MAX_VALUE

        if (candidateAccuracy + ACCURACY_TIE_METERS < currentAccuracy) return true
        if (currentAccuracy + ACCURACY_TIE_METERS < candidateAccuracy) return false

        return providerRank(candidateProvider) > providerRank(currentProvider)
    }

    private fun providerRank(provider: String?): Int = when (provider) {
        "gps" -> 3
        "fused" -> 2
        "network" -> 1
        else -> 0
    }
}
