package com.sajda.session

import com.sajda.compass.Confidence
import kotlin.math.abs

/**
 * Pure UI decisions from confidence + turn angle.
 * Thresholds are coarse — phone magnetometers are not lab instruments.
 */
object HeadingPolicy {
    /** Enter "aligned" when |turn| falls below this. */
    const val ALIGN_ENTER_DEG = 3.0
    /** Leave "aligned" when |turn| rises above this (hysteresis). */
    const val ALIGN_EXIT_DEG = 6.0
    /** Soft band for visual alignment factor (0..1). */
    private const val ALIGN_VISUAL_MAX = 12.0

    fun shouldShowPreciseGuidance(confidence: Confidence, hasHeading: Boolean): Boolean =
        hasHeading && confidence.allowsPreciseHeading

    fun shouldShowCalibrateBanner(confidence: Confidence, sensorHasDelivered: Boolean): Boolean =
        sensorHasDelivered && confidence.needsCalibration

    fun isAligned(turnAbsDeg: Double, wasAligned: Boolean = false): Boolean =
        if (wasAligned) turnAbsDeg < ALIGN_EXIT_DEG else turnAbsDeg < ALIGN_ENTER_DEG

    /** 1 at center → 0 at ALIGN_VISUAL_MAX. */
    fun alignmentFactor(turnAbsDeg: Double): Float {
        val t = turnAbsDeg.coerceIn(0.0, ALIGN_VISUAL_MAX)
        return (1.0 - t / ALIGN_VISUAL_MAX).toFloat().coerceIn(0f, 1f)
    }

    fun turnInstruction(turnDeg: Double, wasAligned: Boolean = false): TurnInstruction {
        val a = abs(turnDeg)
        return when {
            isAligned(a, wasAligned) -> TurnInstruction.ALIGNED
            turnDeg > 0 -> TurnInstruction.RIGHT
            else -> TurnInstruction.LEFT
        }
    }

    enum class TurnInstruction { ALIGNED, LEFT, RIGHT }
}
