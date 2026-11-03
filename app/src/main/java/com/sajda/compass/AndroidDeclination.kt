package com.sajda.compass

/**
 * Wraps platform GeomagneticField (WMM). Known limitation: accuracy degrades
 * at high latitudes (|lat| > ~60°) and near magnetic poles — consumers should
 * treat confidence as LOW when declination is extreme or unavailable.
 */

import android.hardware.GeomagneticField
import com.sajda.geo.DeclinationProvider

/**
 * WMM via Android GeomagneticField (offline, onboard model).
 * Model epoch is managed by the platform; validity ~5 years per WMM release.
 */
class AndroidDeclination : DeclinationProvider {
    override fun declination(latDeg: Double, lonDeg: Double, altMeters: Double, timeMillis: Long): Double {
        val field = GeomagneticField(
            latDeg.toFloat(),
            lonDeg.toFloat(),
            altMeters.toFloat(),
            timeMillis
        )
        return field.declination.toDouble()
    }
}
