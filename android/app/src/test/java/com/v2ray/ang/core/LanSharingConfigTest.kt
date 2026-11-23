package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test

class LanSharingConfigTest {
    private fun config() = V2rayConfig(log = V2rayConfig.LogBean(),
        inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 12000, protocol = "socks", listen = "127.0.0.1")),
        outbounds = arrayListOf(), routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()))
    @Test fun retainsLoopbackAndUsesPrivateInterfaceAndActualPort() {
        val c = config()
        LanSharingConfig.addListeners(c, listOf("192.168.43.1", "192.168.43.1"))
        assertEquals(2, c.inbounds.size)
        assertEquals("127.0.0.1", c.inbounds.first().listen)
        assertEquals("192.168.43.1", c.inbounds.last().listen)
        assertEquals(12000, c.inbounds.last().port)
        assertNotEquals(c.inbounds.first().tag, c.inbounds.last().tag)
    }
    @Test fun identifiesOnlyPrivateIpv4() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1").forEach { assertTrue(LanSharingConfig.isPrivateIpv4(it)) }
        listOf("0.0.0.0", "127.0.0.1", "8.8.8.8", "172.32.0.1", "10.1.2.999", "10.1.2", "::1").forEach { assertFalse(LanSharingConfig.isPrivateIpv4(it)) }
    }
    @Test(expected = IllegalArgumentException::class) fun refusesPublicListener() {
        LanSharingConfig.addListeners(config(), listOf("8.8.8.8"))
    }
}
