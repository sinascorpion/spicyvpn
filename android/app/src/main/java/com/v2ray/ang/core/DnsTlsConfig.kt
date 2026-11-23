package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import java.net.URI

/** Xray supports DNS-over-TCP; a dedicated TLS transport supplies real DoT. */
object DnsTlsConfig {
    fun adapt(address: String, config: V2rayConfig, viaTag: String): String {
        if (!address.startsWith("tls://", true)) return address
        val uri = URI(address)
        val host = requireNotNull(uri.host) { "DoT requires a valid hostname or IP" }.removeSurrounding("[", "]")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")) {
            "Invalid DNS-over-TLS address"
        }
        val port = if (uri.port == -1) 853 else uri.port
        require(port in 1..65535) { "Invalid DoT port" }
        val index = config.outbounds.count { it.tag.startsWith("spicy-dot-") }
        require(index < 64) { "Too many DoT resolvers" }
        val localPort = 44000 + index
        val tag = "spicy-dot-$index"
        val endpoint = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        config.outbounds.add(V2rayConfig.OutboundBean(
            tag = tag, protocol = "freedom",
            settings = V2rayConfig.OutboundBean.OutSettingsBean(redirect = endpoint),
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                network = "tcp", security = "tls",
                tlsSettings = V2rayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean(
                    serverName = host, allowInsecure = false),
                sockopt = V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean(dialerProxy = viaTag))
        ))
        config.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(
            ip = arrayListOf("127.0.0.1"), port = localPort.toString(), network = "tcp", outboundTag = tag))
        return "tcp://127.0.0.1:$localPort"
    }
}
