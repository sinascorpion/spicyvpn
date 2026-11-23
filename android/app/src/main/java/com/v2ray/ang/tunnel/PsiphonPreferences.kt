package com.v2ray.ang.tunnel

object PsiphonPreferences {
    fun build(read: (String) -> String?, readBool: (String, Boolean) -> Boolean, chained: Boolean): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        val country = read("pref_psiphon_country").orEmpty().uppercase()
        if (country.matches(Regex("[A-Z]{2}"))) result["EgressRegion"] = country
        result["AggressiveEstablishment"] = read("pref_psiphon_aggressive").equals("ON", true)
        val mode = read("pref_psiphon_protocol") ?: "auto"
        val protocols = when (mode) {
            "normal" -> listOf("SSH", "OSSH", "TLS-OSSH", "QUIC-OSSH", "SHADOWSOCKS-OSSH")
            "conduit" -> listOf("INPROXY-WEBRTC-OSSH", "INPROXY-WEBRTC-UNFRONTED-MEEK-HTTPS-OSSH", "INPROXY-WEBRTC-UNFRONTED-MEEK-SESSION-TICKET-OSSH", "INPROXY-WEBRTC-FRONTED-MEEK-OSSH", "INPROXY-WEBRTC-FRONTED-MEEK-HTTP-OSSH", "INPROXY-WEBRTC-QUIC-OSSH")
            "cdn_fronting" -> {
                val selected = (read("pref_psiphon_cdn_protocols") ?: "tls,http,quic").split(',').map { it.trim() }
                buildList {
                    if ("tls" in selected) { add("FRONTED-MEEK-OSSH"); add("FRONTED-MEEK-CDN-OSSH") }
                    if ("http" in selected) { add("FRONTED-MEEK-HTTP-OSSH"); add("FRONTED-MEEK-CDN-HTTP-OSSH") }
                    if ("quic" in selected) { add("FRONTED-MEEK-QUIC-OSSH"); add("FRONTED-MEEK-CDN-QUIC-OSSH") }
                }
            }
            else -> emptyList()
        }.filterNot { chained && (it.contains("QUIC") || it.startsWith("INPROXY-")) }
        if (mode != "auto") {
            require(protocols.isNotEmpty()) { "Select a TCP-compatible Psiphon protocol" }
            result["LimitTunnelProtocols"] = protocols
        }
        if (mode == "auto" || mode == "cdn_fronting") {
            result["FrontedMeekCDNScanUseBuiltInSpec"] = readBool("pref_psiphon_add_builtin", true)
            if (read("pref_psiphon_cdn_type") == "akamai") result["FrontedMeekCDNScanBuiltInSets"] = listOf("psiphon-akamai")
            val candidates = read("pref_psiphon_akamai_cdn_ip").orEmpty().split(Regex("[\\s,]+"))
                .filter { it.isNotBlank() }.distinct().take(500)
            if (candidates.isNotEmpty()) {
                val sni = if (readBool("pref_psiphon_no_sni", false)) emptyList() else
                    read("pref_psiphon_sni_server").orEmpty().split(Regex("[\\s,]+" )).filter { it.isNotBlank() }
                result["FrontedMeekCDNScanSpec"] = mapOf("IPCandidates" to candidates, "SNIServerNames" to sni)
            }
        }
        return result
    }
}
