package com.sajda.geo

/**
 * Qibla solution. [atKaaba] is true when the user is within [NEAR_KAABA_METERS]
 * of the Kaaba — bearing is geometrically undefined in that zone.
 */
data class QiblaSolution(
    val bearing: TrueBearingDeg,
    val atKaaba: Boolean,
    val distanceMeters: Double
)

object QiblaEngine {
    /** Within this radius, show "at Kaaba" instead of a bearing. */
    const val NEAR_KAABA_METERS = 100.0

    fun solveDetailed(point: GeoPoint): QiblaSolution {
        require(point.isValid()) { "Invalid coordinates" }
        val distance = GeoMath.distanceToKaabaMeters(point.latDeg, point.lonDeg)
        if (distance < NEAR_KAABA_METERS) {
            // Bearing is undefined at/near the target; keep 0° as a neutral placeholder.
            return QiblaSolution(
                bearing = TrueBearingDeg(0.0),
                atKaaba = true,
                distanceMeters = distance
            )
        }
        val bearing = TrueBearingDeg(GeoMath.qiblaBearing(point.latDeg, point.lonDeg)).normalized()
        return QiblaSolution(bearing = bearing, atKaaba = false, distanceMeters = distance)
    }

    fun solve(point: GeoPoint): TrueBearingDeg = solveDetailed(point).bearing

    fun solve(lat: Double, lon: Double): TrueBearingDeg =
        solve(GeoPoint(lat, lon))
}
