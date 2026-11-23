package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test

class DnsTlsConfigTest {
    private fun config() = V2rayConfig(log = V2rayConfig.LogBean(), inbounds = arrayListOf(),
        outbounds = arrayListOf(V2rayConfig.OutboundBean(protocol = "freedom", tag = "proxy")),
        routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()))

    @Test fun dotUsesVerifiedTlsAndChainedTransport() {
        val c = config()
        assertEquals("tcp://127.0.0.1:44000", DnsTlsConfig.adapt("tls://dns.google", c, "proxy"))
        val out = c.outbounds.last()
        assertEquals("dns.google:853", out.settings?.redirect)
        assertEquals("tls", out.streamSettings?.security)
        assertEquals("dns.google", out.streamSettings?.tlsSettings?.serverName)
        assertEquals(false, out.streamSettings?.tlsSettings?.allowInsecure)
        assertNull(out.proxySettings)
        assertEquals("proxy", out.streamSettings?.sockopt?.dialerProxy)
        assertEquals(out.tag, c.routing.rules.first().outboundTag)
        assertEquals("tcp://127.0.0.1:44001", DnsTlsConfig.adapt("tls://dns.quad9.net:8853", c, "proxy"))
        assertEquals("dns.quad9.net:8853", c.outbounds.last().settings?.redirect)
    }

    @Test fun plainTcpDoesNotBecomeTls() {
        val c = config()
        assertEquals("tcp://8.8.8.8", DnsTlsConfig.adapt("tcp://8.8.8.8", c, "proxy"))
        assertEquals(1, c.outbounds.size)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsCredentials() {
        DnsTlsConfig.adapt("tls://user:password@dns.google", config(), "proxy")
    }

    @Test fun ipv6EndpointHasExactlyOnePairOfBrackets() {
        val c = config()
        DnsTlsConfig.adapt("tls://[2001:4860:4860::8888]", c, "proxy")
        assertEquals("[2001:4860:4860::8888]:853", c.outbounds.last().settings?.redirect)
    }
}
