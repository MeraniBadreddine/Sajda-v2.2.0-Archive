package com.sajda.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.abs

/**
 * Geographical mathematics — pure Kotlin, double precision.
 *
 * Default Qibla: spherical forward azimuth (great-circle).
 * Optional: Vincenty inverse on WGS-84 ellipsoid for scrutiny builds/tests.
 *
 * Documented limit: at city–continental scales, sphere vs WGS-84 azimuth
 * typically differs by well under 0.1°, often below magnetometer noise.
 */
object GeoMath {

    const val KAABA_LAT = 21.422487
    const val KAABA_LON = 39.826206

    /** WGS-84 */
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257223563
    private const val B = A * (1 - F)

    fun rad(deg: Double): Double = deg * PI / 180.0
    fun deg(rad: Double): Double = rad * 180.0 / PI

    fun normalizeDegrees(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    fun angleDelta(fromDeg: Double, toDeg: Double): Double {
        var d = (toDeg - fromDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /**
     * Spherical initial bearing (forward azimuth), [0, 360).
     * Primary production method.
     */
    fun initialBearingSpherical(
        lat1Deg: Double, lon1Deg: Double,
        lat2Deg: Double, lon2Deg: Double
    ): Double {
        val φ1 = rad(lat1Deg)
        val φ2 = rad(lat2Deg)
        var Δλ = rad(lon2Deg - lon1Deg)
        if (Δλ > PI) Δλ -= 2 * PI
        if (Δλ < -PI) Δλ += 2 * PI
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return normalizeDegrees(deg(atan2(y, x)))
    }

    /**
     * Vincenty inverse — forward azimuth on WGS-84 ellipsoid.
     * Returns null if the algorithm fails to converge (antipodal edge cases).
     */
    fun initialBearingVincenty(
        lat1Deg: Double, lon1Deg: Double,
        lat2Deg: Double, lon2Deg: Double
    ): Double? {
        val φ1 = rad(lat1Deg)
        val φ2 = rad(lat2Deg)
        var L = rad(lon2Deg - lon1Deg)
        if (L > PI) L -= 2 * PI
        if (L < -PI) L += 2 * PI

        val tanU1 = (1 - F) * tan(φ1)
        val tanU2 = (1 - F) * tan(φ2)
        val cosU1 = 1.0 / sqrt(1 + tanU1 * tanU1)
        val sinU1 = tanU1 * cosU1
        val cosU2 = 1.0 / sqrt(1 + tanU2 * tanU2)
        val sinU2 = tanU2 * cosU2

        var λ = L
        var λPrev: Double
        var sinλ: Double
        var cosλ: Double
        var sinσ: Double
        var cosσ: Double
        var σ: Double
        var sinα: Double
        var cos2α: Double
        var cos2σm: Double
        var C: Double
        var iter = 0
        do {
            sinλ = sin(λ)
            cosλ = cos(λ)
            val sinσ2 = (cosU2 * sinλ) * (cosU2 * sinλ) +
                    (cosU1 * sinU2 - sinU1 * cosU2 * cosλ) *
                    (cosU1 * sinU2 - sinU1 * cosU2 * cosλ)
            sinσ = sqrt(sinσ2)
            if (sinσ == 0.0) return 0.0 // coincident
            cosσ = sinU1 * sinU2 + cosU1 * cosU2 * cosλ
            σ = atan2(sinσ, cosσ)
            sinα = cosU1 * cosU2 * sinλ / sinσ
            cos2α = 1 - sinα * sinα
            cos2σm = if (cos2α != 0.0) cosσ - 2 * sinU1 * sinU2 / cos2α else 0.0
            C = F / 16 * cos2α * (4 + F * (4 - 3 * cos2α))
            λPrev = λ
            λ = L + (1 - C) * F * sinα * (
                    σ + C * sinσ * (cos2σm + C * cosσ * (-1 + 2 * cos2σm * cos2σm))
                    )
            if (++iter > 100) return null
        } while (abs(λ - λPrev) > 1e-12)

        val fwd = atan2(cosU2 * sinλ, cosU1 * sinU2 - sinU1 * cosU2 * cosλ)
        return normalizeDegrees(deg(fwd))
    }

    /** Production Qibla: spherical great-circle to Kaaba. */
    fun qiblaBearing(userLat: Double, userLon: Double): Double =
        initialBearingSpherical(userLat, userLon, KAABA_LAT, KAABA_LON)

    fun qiblaBearingVincenty(userLat: Double, userLon: Double): Double? =
        initialBearingVincenty(userLat, userLon, KAABA_LAT, KAABA_LON)

    /** Approximate great-circle distance in metres (spherical Earth R=6371000). */
    fun distanceMetersApprox(
        lat1Deg: Double, lon1Deg: Double,
        lat2Deg: Double, lon2Deg: Double
    ): Double {
        val r = 6_371_000.0
        val φ1 = rad(lat1Deg)
        val φ2 = rad(lat2Deg)
        val Δφ = rad(lat2Deg - lat1Deg)
        val Δλ = rad(lon2Deg - lon1Deg)
        val a = sin(Δφ / 2) * sin(Δφ / 2) +
            cos(φ1) * cos(φ2) * sin(Δλ / 2) * sin(Δλ / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun distanceToKaabaMeters(userLat: Double, userLon: Double): Double =
        distanceMetersApprox(userLat, userLon, KAABA_LAT, KAABA_LON)
}
