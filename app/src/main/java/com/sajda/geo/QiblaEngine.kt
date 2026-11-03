package com.sajda.geo

object QiblaEngine {
    fun solve(point: GeoPoint): TrueBearingDeg {
        require(point.isValid()) { "Invalid coordinates" }
        return TrueBearingDeg(GeoMath.qiblaBearing(point.latDeg, point.lonDeg)).normalized()
    }

    fun solve(lat: Double, lon: Double): TrueBearingDeg =
        solve(GeoPoint(lat, lon))
}
