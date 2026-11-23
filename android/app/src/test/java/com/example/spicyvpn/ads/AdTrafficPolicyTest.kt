package com.example.spicyvpn.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdTrafficPolicyTest {
    @Test fun requestsOnlyAfterFullTunnelConnection() {
        assertFalse(AdTrafficPolicy.canRequest(false, false))
        assertFalse(AdTrafficPolicy.canRequest(false, true))
        assertFalse(AdTrafficPolicy.canRequest(true, true))
        assertTrue(AdTrafficPolicy.canRequest(true, false))
    }
}
