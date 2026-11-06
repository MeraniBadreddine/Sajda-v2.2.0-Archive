package com.sajda.compass

import com.sajda.geo.GeoMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular low-pass with adaptive alpha:
 * — still device → heavy smoothing (less jitter)
 * — clear turn → higher alpha (keeps up)
 * Confidence can further tighten the range.
 */
class HeadingFilter(
    private var baseAlpha: Float = 0.12f
) {
    private var sinAcc = 0.0
    private var cosAcc = 1.0
    private var initialized = false
    private var lastHeading = 0.0

    fun reset() {
        initialized = false
        sinAcc = 0.0
        cosAcc = 1.0
    }

    fun setConfidence(confidence: Confidence) {
        baseAlpha = when (confidence) {
            Confidence.HIGH -> 0.16f
            Confidence.MEDIUM -> 0.12f
            Confidence.LOW -> 0.08f
            Confidence.CALIBRATE -> 0.05f
        }
    }

    fun filter(headingDeg: Double): Double {
        if (!headingDeg.isFinite()) return if (initialized) lastHeading else 0.0
        val r = Math.toRadians(headingDeg)
        val s = sin(r)
        val c = cos(r)
        if (!initialized) {
            sinAcc = s
            cosAcc = c
            lastHeading = headingDeg
            initialized = true
            return GeoMath.normalizeDegrees(headingDeg)
        }

        val delta = abs(GeoMath.angleDelta(lastHeading, headingDeg))
        // Motion: 0° ≈ still, 15°+ ≈ deliberate turn this sample
        val motion = (delta / 15.0).coerceIn(0.0, 1.0)
        val stillAlpha = (baseAlpha * 0.45f).coerceAtLeast(0.03f)
        val moveAlpha = (baseAlpha * 1.35f).coerceAtMost(0.35f)
        val alpha = (stillAlpha + (moveAlpha - stillAlpha) * motion.toFloat())

        sinAcc = alpha * s + (1 - alpha) * sinAcc
        cosAcc = alpha * c + (1 - alpha) * cosAcc
        val out = GeoMath.normalizeDegrees(Math.toDegrees(atan2(sinAcc, cosAcc)))
        lastHeading = out
        return out
    }
}
