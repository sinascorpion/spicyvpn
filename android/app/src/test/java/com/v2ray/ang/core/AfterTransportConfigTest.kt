package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test

class AfterTransportConfigTest {
    @Test fun preservesUpstreamAndRoutesOnlyBootstrapToIt() {
        val original = V2rayConfig.OutboundBean(protocol = "vless", tag = "proxy")
        val config = V2rayConfig(log = V2rayConfig.LogBean(), inbounds = arrayListOf(),
            outbounds = arrayListOf(original), routing = V2rayConfig.RoutingBean(
                domainStrategy = "AsIs", rules = arrayListOf(V2rayConfig.RoutingBean.RulesBean(outboundTag = "proxy"))))
        AfterTransportConfig.apply(config, 21808)
        assertEquals("proxy", config.outbounds.first().tag)
        assertEquals("socks", config.outbounds.first().protocol)
        assertEquals(21808, config.outbounds.first().settings!!.servers!!.first().port)
        assertEquals(AfterTransportConfig.UPSTREAM_TAG, original.tag)
        assertEquals(AfterTransportConfig.UPSTREAM_TAG, config.routing.rules.first().outboundTag)
        assertEquals("proxy", config.routing.rules.last().outboundTag)
        assertEquals("127.0.0.1", config.inbounds.single().listen)
        assertEquals(false, config.inbounds.single().settings!!.udp)
    }
}
