package com.sajda.data

import android.content.Context

data class UserLocation(
    val lat: Double,
    val lon: Double,
    val fixTimeMillis: Long = System.currentTimeMillis(),
    val source: LocationSource = LocationSource.GPS
) {
    fun isValid(): Boolean = lat.isFinite() && lon.isFinite() &&
        lat in -90.0..90.0 && lon in -180.0..180.0
}

/** Stores only the last useful fix locally. No network or background tracking. */
class LocationStore(context: Context) : LocationCache {
    private val prefs = context.applicationContext.getSharedPreferences("sajda_loc", Context.MODE_PRIVATE)

    override fun save(loc: UserLocation) {
        if (!loc.isValid()) return
        prefs.edit()
            .putBoolean("has", true)
            .putString("lat", loc.lat.toString())
            .putString("lon", loc.lon.toString())
            .putLong("fix_time", loc.fixTimeMillis)
            .putString("source", loc.source.name)
            .apply()
    }

    override fun load(): UserLocation? {
        return try {
            if (!prefs.getBoolean("has", false)) null
            else {
                val lat = prefs.getString("lat", null)?.toDoubleOrNull()
                val lon = prefs.getString("lon", null)?.toDoubleOrNull()
                if (lat == null || lon == null) null
                else {
                    val fixTime = prefs.getLong("fix_time", 0L)
                    val source = try {
                        LocationSource.valueOf(prefs.getString("source", LocationSource.GPS.name)!!)
                    } catch (_: Exception) {
                        LocationSource.GPS
                    }
                    val location = UserLocation(lat = lat, lon = lon, fixTimeMillis = fixTime, source = source)
                    val age = System.currentTimeMillis() - location.fixTimeMillis
                    // Manual city is not subject to the same freshness gate — user chose it
                    val ageOk = if (source == LocationSource.MANUAL_CITY) true
                    else fixTime > 0L && age in -120_000L..MAX_CACHED_AGE_MS
                    location.takeIf { it.isValid() && ageOk }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun clear() = prefs.edit().clear().apply()

    companion object {
        const val MAX_CACHED_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
