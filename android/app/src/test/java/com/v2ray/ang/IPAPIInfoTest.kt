package com.v2ray.ang

import com.v2ray.ang.dto.IPAPIInfo
import org.junit.Assert.*
import org.junit.Test

class IPAPIInfoTest {
    @Test fun traceUsesObservedClientIpNotEndpointAddress() {
        val info = IPAPIInfo.fromTrace("ip=203.0.113.5\nloc=DE\nwarp=off\n")!!
        assertEquals("203.0.113.5", info.resolveIp())
        assertEquals("DE", info.resolveCountryCode())
        assertNull(IPAPIInfo.fromTrace("<html>challenge</html>"))
    }
    @Test fun supportsIpSbCountryCode() {
        val info = IPAPIInfo(ip = "203.0.113.8", country = "Germany", country_code = "DE")
        assertEquals("203.0.113.8", info.resolveIp())
        assertEquals("DE", info.resolveCountryCode())
    }
    @Test fun supportsIpinfoCountry() {
        assertEquals("NL", IPAPIInfo(ip = "203.0.113.9", country = "NL").resolveCountryCode())
    }
    @Test fun doesNotTreatCountryNameAsCode() {
        assertNull(IPAPIInfo(country = "Germany").resolveCountryCode())
    }
}
