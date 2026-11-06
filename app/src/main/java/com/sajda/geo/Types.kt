package com.sajda.geo

/**
 * Strong types — geographical truth vs device measurement stay distinct.
 * Degrees are always normalized [0, 360) unless noted.
 */
@JvmInline value class TrueBearingDeg(val value: Double) {
    init { require(value.isFinite()) }
    fun normalized(): TrueBearingDeg = TrueBearingDeg(GeoMath.normalizeDegrees(value))
}

@JvmInline value class MagneticHeadingDeg(val value: Double) {
    fun normalized(): MagneticHeadingDeg = MagneticHeadingDeg(GeoMath.normalizeDegrees(value))
}

@JvmInline value class TrueHeadingDeg(val value: Double) {
    fun normalized(): TrueHeadingDeg = TrueHeadingDeg(GeoMath.normalizeDegrees(value))
}

/** Signed turn from device true heading toward Qibla, (-180, 180]. Positive = turn right. */
@JvmInline value class TurnDeg(val value: Double)

data class GeoPoint(val latDeg: Double, val lonDeg: Double) {
    fun isValid(): Boolean =
        latDeg.isFinite() && lonDeg.isFinite() &&
            latDeg in -90.0..90.0 && lonDeg in -180.0..180.0
}

/**
 * One moment of product truth: math + sensors, never mixed into UI doubles.
 */
data class QiblaSession(
    val point: GeoPoint,
    val qibla: TrueBearingDeg,
    val trueHeading: TrueHeadingDeg?,
    val magneticHeading: MagneticHeadingDeg?,
    val confidence: com.sajda.compass.Confidence,
    val interferenceHint: com.sajda.compass.CompassHint?,
    val locationProvisional: Boolean,
    /** Horizontal location uncertainty in metres; null means provider did not report it. */
    val locationAccuracyMeters: Float? = null
) {
    fun turnToQibla(): TurnDeg? {
        val h = trueHeading ?: return null
        return TurnDeg(GeoMath.angleDelta(h.value, qibla.value))
    }
}
