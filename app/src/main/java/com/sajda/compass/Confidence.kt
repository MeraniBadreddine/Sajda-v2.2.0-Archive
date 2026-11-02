package com.sajda.compass

/**
 * Sensor confidence — never claim this is geodesic accuracy.
 */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
    CALIBRATE;

    val allowsPreciseHeading: Boolean
        get() = this == HIGH || this == MEDIUM

    val needsCalibration: Boolean
        get() = this == CALIBRATE
}

/** Localizable sensor/UX hints — never hard-code English in the engine. */
enum class CompassHint {
    NONE,
    DECLINATION_MISSING,
    MAGNETIC_UNAVAILABLE,
    CALIBRATION_NEEDED,
    INTERFERENCE,
    APPROXIMATE
}

data class CompassReading(
    val magneticHeadingDeg: Double,
    val trueHeadingDeg: Double,
    val confidence: Confidence,
    val accuracyStatus: Int,
    val hint: CompassHint = CompassHint.NONE
)
