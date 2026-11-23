package com.v2ray.ang.tunnel

import org.junit.Assert.*
import org.junit.Test

class PsiphonPreferencesTest {
    @Test fun countryAndCdnOptionsReachCore() {
        val values = mapOf("pref_psiphon_country" to "de", "pref_psiphon_protocol" to "cdn_fronting",
            "pref_psiphon_cdn_protocols" to "tls,quic", "pref_psiphon_cdn_type" to "akamai",
            "pref_psiphon_akamai_cdn_ip" to "23.1.2.3,23.1.2.3", "pref_psiphon_sni_server" to "www.akamai.com")
        val result = PsiphonPreferences.build(values::get, { _, fallback -> fallback }, true)
        assertEquals("DE", result["EgressRegion"])
        assertEquals(listOf("psiphon-akamai"), result["FrontedMeekCDNScanBuiltInSets"])
        assertFalse((result["LimitTunnelProtocols"] as List<*>).any { it.toString().contains("QUIC") })
        val spec = result["FrontedMeekCDNScanSpec"] as Map<*, *>
        assertEquals(listOf("23.1.2.3"), spec["IPCandidates"])
        assertEquals(listOf("www.akamai.com"), spec["SNIServerNames"])
    }
    @Test fun emptyProtocolChoiceDoesNotSilentlyFallBack() {
        assertTrue(runCatching { PsiphonPreferences.build({ if (it == "pref_psiphon_protocol") "cdn_fronting" else "" }, { _, fallback -> fallback }, false) }.isFailure)
    }
}
