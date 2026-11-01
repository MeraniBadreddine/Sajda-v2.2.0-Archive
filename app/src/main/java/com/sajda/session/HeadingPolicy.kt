package com.sajda.session

import com.sajda.compass.Confidence
import kotlin.math.abs

/**
 * Pure mapping from sensor/session facts → UI decisions.
 * No Android framework types — unit-testable.
 */
object HeadingPolicy {

    /** 0 outside 12°, 1 inside 2°, linear between. */
    fun alignmentFactor(turnAbsDeg: Double): Float = when {
        turnAbsDeg >= 12.0 -> 0f
        turnAbsDeg <= 2.0 -> 1f
        else -> (1f - ((turnAbsDeg - 2.0) / 10.0).toFloat()).coerceIn(0f, 1f)
    }

    fun isAligned(turnAbsDeg: Double, thresholdDeg: Double = 2.0): Boolean =
        turnAbsDeg < thresholdDeg

    fun shouldShowPreciseGuidance(confidence: Confidence, hasHeading: Boolean): Boolean =
        hasHeading && confidence.allowsPreciseHeading

    fun shouldShowCalibrateBanner(confidence: Confidence): Boolean =
        confidence.needsCalibration

    fun turnInstruction(turnDeg: Double): TurnInstruction {
        val a = abs(turnDeg)
        return when {
            a < 2.0 -> TurnInstruction.ALIGNED
            turnDeg > 0 -> TurnInstruction.RIGHT
            else -> TurnInstruction.LEFT
        }
    }

    enum class TurnInstruction { ALIGNED, LEFT, RIGHT }
}
