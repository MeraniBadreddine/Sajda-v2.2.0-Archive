package com.sajda.data

enum class LocationSource {
    GPS,
    NETWORK,
    FUSED,
    LAST_KNOWN,
    MANUAL_CITY
}

enum class LocationFailure {
    PERMISSION_DENIED,
    LOCATION_DISABLED,
    PROVIDERS_UNAVAILABLE,
    TIMEOUT
}
