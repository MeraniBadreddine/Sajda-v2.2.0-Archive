package com.sajda.ui

import android.content.Context
import com.sajda.R
import com.sajda.compass.CompassHint

object CompassHintMessages {
    fun resolve(context: Context, hint: CompassHint?): String? = when (hint) {
        null, CompassHint.NONE -> null
        CompassHint.DECLINATION_MISSING -> context.getString(R.string.hint_declination_missing)
        CompassHint.MAGNETIC_UNAVAILABLE -> context.getString(R.string.hint_magnetic_unavailable)
        CompassHint.CALIBRATION_NEEDED -> context.getString(R.string.hint_calibration_needed)
        CompassHint.INTERFERENCE -> context.getString(R.string.hint_interference)
        CompassHint.APPROXIMATE -> context.getString(R.string.hint_approximate)
    }
}
