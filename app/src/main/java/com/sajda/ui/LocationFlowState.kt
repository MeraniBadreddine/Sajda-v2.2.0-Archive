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
        if (permanent) {
            permissionDeniedPermanently = true
            permState = LocationPermState.DENIED_PERMANENT
        } else {
            // Do not clear a prior permanent sticky flag on soft deny.
            if (!permissionDeniedPermanently) {
                permState = LocationPermState.DENIED
            }
        }
    }

    /**
     * System revoked a temporary grant (e.g. "Only this time") after the app
     * left the foreground. Only applies when the previous state was GRANTED.
     * DENIED_PERMANENT is sticky until onPermissionGranted().
     */
    fun onPermissionLapsed() {
        if (permState != LocationPermState.GRANTED) return
        if (permissionDeniedPermanently) return
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
        if (permissionDeniedPermanently) return
        permState = LocationPermState.UNKNOWN
    }

    fun syncUsingCityFromSource(isManualCity: Boolean) {
        usingCityFallback = isManualCity
    }

    fun markDeniedFromChooser() {
        if (permissionDeniedPermanently) return
        permState = LocationPermState.DENIED
    }
}
