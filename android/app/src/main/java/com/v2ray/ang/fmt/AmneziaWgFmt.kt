package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.idnPort
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.Utils
import java.net.URI

object AmneziaWgFmt : FmtBase() {
    private val optionNames = setOf("version", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime",
        "header-protection-key", "content-padding-addition", "rekey-after-time", "rekey-timeout",
        "reject-after-time", "keepalive-timeout", "max-handshake-attempts", "random-trailers", "disable-cookies")
    private fun normalizeOptions(values: Map<String, String>): Map<String, String> = values.mapNotNull { (key, value) ->
        val name = optionNames.firstOrNull { it.replace("-", "") == key.lowercase().replace("-", "").replace("_", "") }
        name?.let { it to value }
    }.toMap()
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.AMNEZIAWG)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "AmneziaWG" } }
        config.server = uri.idnHost
        config.serverPort = uri.idnPort.toString()

        config.secretKey = uri.userInfo.orEmpty()
        config.localAddress = queryParam["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.publicKey = queryParam["publickey"].orEmpty()
        config.preSharedKey = queryParam["presharedkey"]?.nullIfBlank()
        config.mtu = Utils.parseInt(queryParam["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.reserved = queryParam["reserved"] ?: "0,0,0"

        config.obfsPassword = listOfNotNull(
            queryParam["jc"]?.let { "Jc=$it" },
            queryParam["jmin"]?.let { "Jmin=$it" },
            queryParam["jmax"]?.let { "Jmax=$it" },
            queryParam["s1"]?.let { "S1=$it" },
            queryParam["s2"]?.let { "S2=$it" },
            queryParam["h1"]?.let { "H1=$it" },
            queryParam["h2"]?.let { "H2=$it" },
            queryParam["h3"]?.let { "H3=$it" },
            queryParam["h4"]?.let { "H4=$it" }
        ).joinToString(";")

        config.amneziaOptions = normalizeOptions(queryParam)

        return config
    }

    fun parseConf(str: String): ProfileItem {
        require(Regex("(?im)^\\s*\\[Peer]\\s*$").findAll(str).count() == 1) {
            "Multi-peer Amnezia profiles must be imported as native Mihomo YAML"
        }
        val config = ProfileItem.create(EConfigType.AMNEZIAWG)
        val interfaceParams = mutableMapOf<String, String>()
        val peerParams = mutableMapOf<String, String>()
        var currentSection: String? = null

        str.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

            when {
                trimmed.startsWith("[Interface]", ignoreCase = true) -> currentSection = "Interface"
                trimmed.startsWith("[Peer]", ignoreCase = true) -> currentSection = "Peer"
                else -> {
                    val parts = trimmed.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2 && currentSection != null) {
                        val key = parts[0].lowercase()
                        val value = parts[1]
                        if (currentSection == "Interface") interfaceParams[key] = value
                        else if (currentSection == "Peer") peerParams[key] = value
                    }
                }
            }
        }

        config.secretKey = interfaceParams["privatekey"].orEmpty()
        config.remarks = "AmneziaWG"
        config.localAddress = interfaceParams["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.mtu = Utils.parseInt(interfaceParams["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.publicKey = peerParams["publickey"].orEmpty()
        config.preSharedKey = peerParams["presharedkey"]?.nullIfBlank()

        val endpoint = peerParams["endpoint"].orEmpty()
        val endpointUri = runCatching { URI("udp://$endpoint") }.getOrNull()
        if (!endpointUri?.host.isNullOrBlank()) {
            config.server = endpointUri!!.host.removePrefix("[").removeSuffix("]")
            config.serverPort = if (endpointUri.port > 0) endpointUri.port.toString() else "51820"
        } else {
            config.server = endpoint
            config.serverPort = "51820"
        }
        config.reserved = peerParams["reserved"] ?: "0,0,0"
        config.amneziaOptions = normalizeOptions(peerParams + interfaceParams)

        val jc = interfaceParams["jc"] ?: peerParams["jc"]
        val jmin = interfaceParams["jmin"] ?: peerParams["jmin"]
        val jmax = interfaceParams["jmax"] ?: peerParams["jmax"]
        val s1 = interfaceParams["s1"] ?: peerParams["s1"]
        val s2 = interfaceParams["s2"] ?: peerParams["s2"]
        val h1 = interfaceParams["h1"] ?: peerParams["h1"]
        val h2 = interfaceParams["h2"] ?: peerParams["h2"]
        val h3 = interfaceParams["h3"] ?: peerParams["h3"]
        val h4 = interfaceParams["h4"] ?: peerParams["h4"]

        config.obfsPassword = listOfNotNull(
            jc?.let { "Jc=$it" },
            jmin?.let { "Jmin=$it" },
            jmax?.let { "Jmax=$it" },
            s1?.let { "S1=$it" },
            s2?.let { "S2=$it" },
            h1?.let { "H1=$it" },
            h2?.let { "H2=$it" },
            h3?.let { "H3=$it" },
            h4?.let { "H4=$it" }
        ).joinToString(";")

        return config
    }
}
