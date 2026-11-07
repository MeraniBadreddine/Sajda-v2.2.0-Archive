package com.sajda.location

/**
 * Documents "first usable fix wins". Used by LocationController commit path
 * and unit-tested without Android framework.
 */
class FirstFixGate {
    @Volatile
    var committed: Boolean = false
        private set

    /** @return true if this call is the winning commit. */
    fun tryCommit(usable: Boolean): Boolean {
        if (!usable || committed) return false
        committed = true
        return true
    }

    fun reset() {
        committed = false
    }
}
