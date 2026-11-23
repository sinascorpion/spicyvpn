package com.example.spicyvpn.ads

import org.junit.Assert.*
import org.junit.Test

class AdFrequencyPolicyTest {
    private val now = 100 * AdFrequencyPolicy.WINDOW_MS
    @Test fun firstImpressionIsEligible() { assertTrue(AdFrequencyPolicy.canShow(now, emptyList())) }
    @Test fun enforcesCooldown() {
        assertFalse(AdFrequencyPolicy.canShow(now, listOf(now - AdFrequencyPolicy.COOLDOWN_MS + 1)))
        assertTrue(AdFrequencyPolicy.canShow(now, listOf(now - AdFrequencyPolicy.COOLDOWN_MS)))
    }
    @Test fun threeImpressionsBlockEvenAcrossMidnight() {
        assertFalse(AdFrequencyPolicy.canShow(now, listOf(now - 20 * 3600000L, now - 12 * 3600000L, now - 4 * 3600000L)))
    }
    @Test fun expiredImpressionsDoNotBlock() {
        assertTrue(AdFrequencyPolicy.canShow(now, List(3) { now - AdFrequencyPolicy.WINDOW_MS }))
    }
    @Test fun changingClockBackDoesNotPermitAnotherImpression() {
        assertFalse(AdFrequencyPolicy.canShow(now, listOf(now + 1)))
    }
}
