package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.idnPort
import com.v2ray.ang.util.Utils
import java.net.URI

object OpenVpnFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.OPENVPN)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = getQueryParam(uri)

        val port = if (uri.idnPort > 0) uri.idnPort else 1194
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let {
            it.ifEmpty { "${uri.idnHost}:$port" }
        }
        config.server = uri.idnHost
        config.serverPort = port.toString()

        if (uri.userInfo?.isNotEmpty() == true) {
            val userPart = if (uri.userInfo.contains(":")) uri.userInfo.split(":", limit = 2) else listOf(uri.userInfo)
            config.username = userPart.firstOrNull().orEmpty()
            config.password = if (userPart.size > 1) userPart[1] else null
        }

        config.network = queryParam["proto"] ?: "udp"
        config.sni = queryParam["sni"]
        return config
    }

    fun parseOvpnContent(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.OPENVPN)
        config.remarks = "OpenVPN"

        var remoteHost = ""
        var remotePort = "1194"
        var proto = "udp"

        str.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) return@forEach
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.isNotEmpty()) {
                when (parts[0].lowercase()) {
                    "remote" -> {
                        if (parts.size > 1 && remoteHost.isEmpty()) {
                            remoteHost = parts[1].trim('"', '\'')
                            if (parts.size > 2) remotePort = parts[2]
                            if (parts.size > 3) proto = parts[3].lowercase()
                        }
                    }
                    "proto" -> {
                        if (parts.size > 1) proto = parts[1].lowercase()
                    }
                    "port" -> {
                        if (parts.size > 1) remotePort = parts[1]
                    }
                }
            }
        }

        config.server = remoteHost
        config.serverPort = remotePort
        config.network = proto
        return config
    }
}
