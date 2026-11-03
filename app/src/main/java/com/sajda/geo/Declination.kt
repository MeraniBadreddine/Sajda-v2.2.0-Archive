package com.sajda.geo

fun interface DeclinationProvider {
    fun declination(latDeg: Double, lonDeg: Double, altMeters: Double, timeMillis: Long): Double
}

object TrueHeading {
    fun fromMagnetic(magneticDeg: Double, declinationDeg: Double): TrueHeadingDeg =
        TrueHeadingDeg(GeoMath.normalizeDegrees(magneticDeg + declinationDeg))
}
