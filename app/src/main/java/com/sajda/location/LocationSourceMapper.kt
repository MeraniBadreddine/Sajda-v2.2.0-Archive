package com.sajda.location

import com.sajda.data.LocationSource

object LocationSourceMapper {
    fun fromProvider(provider: String?): LocationSource = when (provider) {
        "gps" -> LocationSource.GPS
        "network" -> LocationSource.NETWORK
        "fused" -> LocationSource.FUSED
        else -> LocationSource.FUSED
    }
}
