package com.sajda.data

import com.sajda.geo.GeoPoint

data class OfflineCity(val nameResKey: String, val lat: Double, val lon: Double) {
    fun point() = GeoPoint(lat, lon)
}

/** Short offline fallback after GPS failure — not a world gazetteer. */
object OfflineCities {
    val all: List<OfflineCity> = listOf(
        OfflineCity("mecca", 21.4225, 39.8262),
        OfflineCity("medina", 24.4672, 39.6024),
        OfflineCity("algiers", 36.7538, 3.0588),
        OfflineCity("annaba", 36.9000, 7.7667),
        OfflineCity("oran", 35.6969, -0.6331),
        OfflineCity("casablanca", 33.5731, -7.5898),
        OfflineCity("tunis", 36.8065, 10.1815),
        OfflineCity("cairo", 30.0444, 31.2357),
        OfflineCity("istanbul", 41.0082, 28.9784),
        OfflineCity("riyadh", 24.7136, 46.6753),
        OfflineCity("jeddah", 21.4858, 39.1925),
        OfflineCity("dubai", 25.2048, 55.2708),
        OfflineCity("doha", 25.2854, 51.5310),
        OfflineCity("amman", 31.9454, 35.9284),
        OfflineCity("baghdad", 33.3152, 44.3661),
        OfflineCity("karachi", 24.8607, 67.0011),
        OfflineCity("lahore", 31.5497, 74.3436),
        OfflineCity("dhaka", 23.8103, 90.4125),
        OfflineCity("jakarta", -6.2088, 106.8456),
        OfflineCity("kuala_lumpur", 3.1390, 101.6869),
        OfflineCity("london", 51.5074, -0.1278),
        OfflineCity("paris", 48.8566, 2.3522),
        OfflineCity("berlin", 52.5200, 13.4050),
        OfflineCity("new_york", 40.7128, -74.0060),
        OfflineCity("toronto", 43.6532, -79.3832),
        OfflineCity("los_angeles", 34.0522, -118.2437),
        OfflineCity("sao_paulo", -23.5505, -46.6333),
        OfflineCity("sydney", -33.8688, 151.2093),
        OfflineCity("tokyo", 35.6762, 139.6503),
        OfflineCity("cape_town", -33.9249, 18.4241)
    )
}
