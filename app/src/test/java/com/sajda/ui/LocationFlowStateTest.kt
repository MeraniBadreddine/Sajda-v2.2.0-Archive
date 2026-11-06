package com.sajda.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFlowStateTest {
    @Test
    fun manualCitySetsCityOnly() {
        val s = LocationFlowState()
        s.onManualCitySelected()
        assertEquals(LocationPermState.CITY_ONLY, s.permState)
        assertTrue(s.usingCityFallback)
        assertTrue(s.isCityMode(lastSourceIsManualCity = false))
    }

    @Test
    fun gpsUpgradeFlags() {
        val s = LocationFlowState()
        s.onManualCitySelected()
        s.beginGpsUpgradeFromCity()
        assertTrue(s.gpsUpgradeRequested)
        s.onLiveGpsAccepted()
        assertFalse(s.usingCityFallback)
        assertFalse(s.gpsUpgradeRequested)
        assertEquals(LocationPermState.GRANTED, s.permState)
    }

    @Test
    fun permissionDeniedPaths() {
        val s = LocationFlowState()
        s.onPermissionDenied(permanent = false)
        assertEquals(LocationPermState.DENIED, s.permState)
        s.onPermissionDenied(permanent = true)
        assertEquals(LocationPermState.DENIED_PERMANENT, s.permState)
        assertTrue(s.permissionDeniedPermanently)
    }
}
