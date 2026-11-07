package com.sajda.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionLapseTest {
    @Test
    fun grantedThenLapsedAllowsReRequest() {
        val s = LocationFlowState()
        s.onPermissionGranted()
        assertEquals(LocationPermState.GRANTED, s.permState)
        s.onPermissionLapsed()
        assertEquals(LocationPermState.UNKNOWN, s.permState)
        assertFalse(s.permissionDeniedPermanently)
        s.onPermissionGranted()
        assertEquals(LocationPermState.GRANTED, s.permState)
    }

    @Test
    fun permanentDenySurvivesLapseCall() {
        val s = LocationFlowState()
        s.onPermissionDenied(permanent = true)
        assertEquals(LocationPermState.DENIED_PERMANENT, s.permState)
        assertTrue(s.permissionDeniedPermanently)
        s.onPermissionLapsed() // must no-op
        assertEquals(LocationPermState.DENIED_PERMANENT, s.permState)
        assertTrue(s.permissionDeniedPermanently)
    }

    @Test
    fun lapseIgnoredWhenNeverGranted() {
        val s = LocationFlowState()
        s.onPermissionDenied(permanent = false)
        assertEquals(LocationPermState.DENIED, s.permState)
        s.onPermissionLapsed()
        assertEquals(LocationPermState.DENIED, s.permState)
    }

    @Test
    fun onlyGrantedClearsPermanent() {
        val s = LocationFlowState()
        s.onPermissionDenied(permanent = true)
        s.onPermissionGranted()
        assertFalse(s.permissionDeniedPermanently)
        assertEquals(LocationPermState.GRANTED, s.permState)
    }
}
