package com.sajda.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves first-usable-fix policy without Android LocationManager:
 * FirstFixGate is the commit authority used by LocationController.commitIfUsable.
 */
class LocationControllerFirstFixTest {

    @Test
    fun firstUsableFixCommits() {
        val gate = FirstFixGate()
        assertTrue(gate.tryCommit(usable = true))
        assertTrue(gate.committed)
    }

    @Test
    fun secondFixIgnoredAfterCommit() {
        val gate = FirstFixGate()
        assertTrue(gate.tryCommit(usable = true))
        // GPS-quality second fix must not win.
        assertFalse(gate.tryCommit(usable = true))
        assertTrue(gate.committed)
    }

    @Test
    fun unusableFixDoesNotCommitAndSearchContinues() {
        val gate = FirstFixGate()
        assertFalse(gate.tryCommit(usable = false))
        assertFalse(gate.committed)
        // Later usable fix still allowed.
        assertTrue(gate.tryCommit(usable = true))
    }

    @Test
    fun resetAllowsNewSearchGeneration() {
        val gate = FirstFixGate()
        assertTrue(gate.tryCommit(usable = true))
        gate.reset()
        assertFalse(gate.committed)
        assertTrue(gate.tryCommit(usable = true))
    }
}
