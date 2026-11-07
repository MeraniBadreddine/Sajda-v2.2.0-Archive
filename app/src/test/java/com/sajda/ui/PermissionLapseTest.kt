package com.sajda.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionLapseTest {
    @Test
    fun oneTimeLapseIsNotPermanentDenial() {
        val s = LocationFlowState()
        s.onPermissionGranted()
        assertEquals(LocationPermState.GRANTED, s.permState)

        // System revokes "Only this time"
        s.onPermissionLapsed()
        assertEquals(LocationPermState.UNKNOWN, s.permState)
        assertFalse(s.permissionDeniedPermanently)
        assertFalse(s.gpsUpgradeRequested)
    }

    @Test
    fun explicitPermanentDenyStaysPermanent() {
        val s = LocationFlowState()
        s.onPermissionDenied(permanent = true)
        assertTrue(s.permissionDeniedPermanently)
        assertEquals(LocationPermState.DENIED_PERMANENT, s.permState)
        // Lapse must not apply after permanent deny from our API —
        // but if called, clears permanent so OS dialog path can recover after settings.
        s.onPermissionLapsed()
        assertFalse(s.permissionDeniedPermanently)
    }
}
