package com.v2ray.ang.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenVpnFmtTest {
    @Test fun keepsFirstRemoteAndItsPortTogether() {
        val profile = OpenVpnFmt.parseOvpnContent("client\ndev tun\nremote first.example 1194 udp\nremote second.example 443 tcp\n")
        assertEquals("first.example", profile.server)
        assertEquals("1194", profile.serverPort)
        assertEquals("udp", profile.network)
    }
    @Test fun acceptsQuotedRemote() {
        val profile = OpenVpnFmt.parseOvpnContent("client\ndev tun\nremote \"vpn.example\" 443 tcp-client\n")
        assertEquals("vpn.example", profile.server)
        assertEquals("443", profile.serverPort)
    }
}
