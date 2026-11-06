package com.sajda.ui

/**
 * Permission / city / GPS-upgrade state machine for the location pill.
 * Pure state — no Android framework types besides enums.
 */
class LocationFlowState {
    var permState: LocationPermState = LocationPermState.UNKNOWN
    var usingCityFallback: Boolean = false
    var gpsUpgradeRequested: Boolean = false
    var readyDismissed: Boolean = false
    var permissionDeniedPermanently: Boolean = false

    fun isCityMode(lastSourceIsManualCity: Boolean): Boolean =
        usingCityFallback ||
            permState == LocationPermState.CITY_ONLY ||
            lastSourceIsManualCity

    fun onManualCitySelected() {
        usingCityFallback = true
        permState = LocationPermState.CITY_ONLY
        gpsUpgradeRequested = false
    }

    fun onCachedManualCityRestored() {
        usingCityFallback = true
        permState = LocationPermState.CITY_ONLY
    }

    fun onPermissionGranted() {
        permState = LocationPermState.GRANTED
        permissionDeniedPermanently = false
    }

    fun onPermissionDenied(permanent: Boolean) {
        permissionDeniedPermanently = permanent
        permState = if (permanent) LocationPermState.DENIED_PERMANENT else LocationPermState.DENIED
    }

    fun beginGpsUpgradeFromCity() {
        gpsUpgradeRequested = true
    }

    fun clearGpsUpgrade() {
        gpsUpgradeRequested = false
    }

    fun onLiveGpsAccepted() {
        usingCityFallback = false
        gpsUpgradeRequested = false
        permState = LocationPermState.GRANTED
    }
}
