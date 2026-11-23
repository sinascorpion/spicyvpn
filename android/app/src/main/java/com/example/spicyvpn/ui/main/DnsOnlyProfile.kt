package com.example.spicyvpn.ui.main

import java.net.URI

object DnsOnlyProfile {
    fun normalize(value: String): String {
        val text = value.trim()
        require(text.isNotEmpty()) { "DNS address is required" }
        if (com.v2ray.ang.util.Utils.isPureIpAddress(text)) return text
        val normalized = when {
            text.startsWith("doh://", true) -> "https://" + text.substringAfter("://")
            text.startsWith("dot://", true) -> "tls://" + text.substringAfter("://")
            else -> text
        }
        if (com.v2ray.ang.util.Utils.isPureIpAddress(normalized)) return normalized
        val uri = URI(normalized)
        require(uri.scheme in listOf("https", "tcp", "tls", "udp") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Use an IP, HTTPS, TCP, UDP or TLS DNS address" }
        if (uri.scheme != "https") require(uri.path.orEmpty() in listOf("", "/") && uri.query == null) { "Invalid DNS address" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid DNS port" }
        return normalized
    }
}
