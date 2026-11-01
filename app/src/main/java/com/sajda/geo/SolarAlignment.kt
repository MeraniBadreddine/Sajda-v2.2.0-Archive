package com.sajda.geo

/**
 * Solar alignment with the Qibla is a valid scientific cross-check only with
 * a verified solar ephemeris (e.g. NREL SPA). Until that is integrated and
 * tested, this API returns null — never invented "two days a year" dates.
 */
object SolarAlignment {
    data class AlignmentEvent(val epochMillisUtc: Long, val sameAzimuth: Boolean)

    fun nextAlignment(user: GeoPoint, fromEpochMs: Long): AlignmentEvent? = null
}
