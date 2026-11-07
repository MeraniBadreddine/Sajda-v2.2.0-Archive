package com.sajda.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettlePolicyTest {
    @Test
    fun gpsSettlesIn5s() {
        assertEquals(5_000L, ProviderSettlePolicy.settleDelayMs("gps"))
    }

    @Test
    fun networkSettlesIn15s() {
        assertEquals(15_000L, ProviderSettlePolicy.settleDelayMs("network"))
        assertEquals(15_000L, ProviderSettlePolicy.settleDelayMs("fused"))
        assertEquals(15_000L, ProviderSettlePolicy.settleDelayMs(null))
    }

    @Test
    fun materialAccuracyImprovementCommitsImmediately() {
        assertTrue(ProviderSettlePolicy.shouldCommitImmediately(20f, 90f))
        assertFalse(ProviderSettlePolicy.shouldCommitImmediately(40f, 30f))
        assertTrue(ProviderSettlePolicy.shouldCommitImmediately(15f, null))
    }
}
