package com.sajda.compass

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingSourceTest {
    @Test fun sourcesDistinct() {
        assertEquals(3, HeadingSource.values().size)
        assertEquals(HeadingSource.NONE, HeadingSource.NONE)
    }
}
