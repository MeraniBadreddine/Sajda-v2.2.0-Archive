package com.sajda.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestGateTest {
    @Test fun acceptsMatchingActive() =
        assertTrue(RequestGate.isActive(3, 3, locating = true))

    @Test fun rejectsAfterStopLocating() =
        assertFalse(RequestGate.isActive(3, 3, locating = false))

    @Test fun rejectsStaleGeneration() =
        assertFalse(RequestGate.isActive(2, 3, locating = true))

    @Test fun rejectsBoth() =
        assertFalse(RequestGate.isActive(1, 9, locating = false))
}
