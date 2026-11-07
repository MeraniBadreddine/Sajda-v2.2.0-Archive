package com.sajda.ui

import com.sajda.R
import com.sajda.data.LocationFailure

/**
 * Maps location failures to pill string resources.
 * Pure — unit-testable without Activity.
 */
object LocationFailureUi {
    fun timeoutPillRes(cityMode: Boolean, hasSession: Boolean): Int = when {
        hasSession && cityMode -> R.string.gps_timeout
        hasSession -> R.string.location_timeout
        else -> R.string.location_timeout
    }

    fun providersUnavailablePillRes(cityMode: Boolean, hasSession: Boolean): Int = when {
        hasSession && cityMode -> R.string.gps_timeout
        hasSession -> R.string.location_providers_unavailable
        else -> R.string.location_providers_unavailable
    }

    fun pillRes(
        reason: LocationFailure,
        cityMode: Boolean,
        hasSession: Boolean
    ): Int? = when (reason) {
        LocationFailure.TIMEOUT -> timeoutPillRes(cityMode, hasSession)
        LocationFailure.PROVIDERS_UNAVAILABLE -> providersUnavailablePillRes(cityMode, hasSession)
        LocationFailure.PERMISSION_DENIED -> R.string.location_permission_pill
        LocationFailure.LOCATION_DISABLED -> R.string.location_off_pill
    }
}
