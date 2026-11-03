package com.sajda.location

/** Pure request acceptance: generation match + locating flag. */
object RequestGate {
    fun isActive(requestId: Int, generation: Int, locating: Boolean): Boolean =
        requestId == generation && locating
}
