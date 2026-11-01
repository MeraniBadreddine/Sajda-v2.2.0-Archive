package com.sajda.compass

/**
 * Sensor confidence — never claim this is geodesic accuracy.
 */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
    CALIBRATE;

    /** UI must not present a precise heading as trustworthy. */
    val allowsPreciseHeading: Boolean
        get() = this == HIGH || this == MEDIUM

    val needsCalibration: Boolean
        get() = this == CALIBRATE
}

data class CompassReading(
    val magneticHeadingDeg: Double,
    val trueHeadingDeg: Double,
    val confidence: Confidence,
    val accuracyStatus: Int,
    val interferenceHint: String? = null
)
