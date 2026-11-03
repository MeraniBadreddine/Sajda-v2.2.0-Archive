package com.sajda.data

/** Abstraction over last-fix persistence for testability. */
interface LocationCache {
    fun save(loc: UserLocation)
    fun load(): UserLocation?
    fun clear()
}
