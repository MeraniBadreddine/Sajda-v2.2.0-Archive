package com.sajda.ui

/**
 * Permission / city / GPS-upgrade state machine for the location pill.
 * All transitions go through methods — callers must not assign fields directly.
 */
class LocationFlowState {
    var permState: LocationPermState = LocationPermState.UNKNOWN
        private set
    var usingCityFallback: Boolean = false
        private set
    var gpsUpgradeRequested: Boolean = false
        private set
    var readyDismissed: Boolean = false
        private set
    var permissionDeniedPermanently: Boolean = false
        private set

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

    /**
     * System revoked a one-time / temporary grant (e.g. "Only this time") after
     * the app left the foreground. This is NOT a user denial — keep re-requestable.
     */
    fun onPermissionLapsed() {
        permissionDeniedPermanently = false
        permState = LocationPermState.UNKNOWN
        gpsUpgradeRequested = false
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

    fun markReadyDismissed() {
        readyDismissed = true
    }

    fun resetReadyDismissed() {
        readyDismissed = false
    }

    fun markPreSheetShown() {
        permState = LocationPermState.PRE_SHOWN
    }

    fun resetToUnknown() {
        permState = LocationPermState.UNKNOWN
    }

    fun syncUsingCityFromSource(isManualCity: Boolean) {
        usingCityFallback = isManualCity
    }

    fun markDeniedFromChooser() {
        permState = LocationPermState.DENIED
    }
}
