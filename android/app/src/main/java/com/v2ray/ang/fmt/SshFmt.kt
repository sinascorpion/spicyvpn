package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.idnPort
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.util.Utils
import java.net.URI

object SshFmt : FmtBase() {
    /**
     * Parses an SSH URI string into a ProfileItem object.
     * Format: ssh://username:password@host:port#remarks
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.SSH)

        val isTlsScheme = str.startsWith("ssh+tls://", ignoreCase = true)
        val normalized = if (isTlsScheme) "ssh://${str.substringAfter("://")}" else str
        val uri = URI(Utils.fixIllegalUrl(normalized))
        if (uri.idnHost.isEmpty()) return null
        val port = if (uri.idnPort > 0) uri.idnPort else if (isTlsScheme) 443 else 22

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let {
            it.ifEmpty { "${uri.idnHost}:$port" }
        }
        config.server = uri.idnHost
        config.serverPort = port.toString()
        config.sshPrivateKey = queryParamFor(uri, "private_key")
        config.sshKeyPassphrase = queryParamFor(uri, "key_passphrase")
        config.sshUpstreamProxy = queryParamFor(uri, "proxy")
        config.sshUpstreamProxy?.let { proxy ->
            val upstream = URI(proxy)
            if (upstream.scheme !in setOf("socks5", "http") || upstream.host.isNullOrBlank() || upstream.port !in 1..65535) return null
        }
        config.sshUdpGatewayHost = queryParamFor(uri, "udpgw_host")
        config.sshUdpGatewayPort = queryParamFor(uri, "udpgw_port")?.toIntOrNull()
        if (config.sshUdpGatewayHost != null && config.sshUdpGatewayPort !in 1..65535) return null

        if (uri.userInfo?.isNotEmpty() == true) {
            val userPart = if (uri.userInfo.contains(":")) {
                uri.userInfo.split(":", limit = 2)
            } else {
                try {
                    val decoded = Utils.decode(uri.userInfo)
                    if (decoded.contains(":")) decoded.split(":", limit = 2) else listOf(uri.userInfo)
                } catch (e: Exception) {
                    listOf(uri.userInfo)
                }
            }
            config.username = Utils.decodeURIComponent(userPart.firstOrNull().orEmpty())
            config.password = userPart.getOrNull(1)?.let(Utils::decodeURIComponent)
        }

        val queryParam = getQueryParam(uri)
        if (queryParam.isNotEmpty()) {
            config.sni = queryParam["sni"]
            config.security = queryParam["security"] ?: if (isTlsScheme || queryParam["tls"] == "true") "tls" else null
            config.publicKey = queryParam["pubkey"]
        } else if (isTlsScheme) {
            config.security = "tls"
        }

        return config
    }

    /**
     * Converts an SSH ProfileItem to a URI string.
     */
    fun toUri(config: ProfileItem): String {
        val userAuth = if (config.username.isNotNullEmpty()) {
            val username = Utils.encodeURIComponent(config.username.orEmpty())
            if (config.password.isNotNullEmpty()) {
                "$username:${Utils.encodeURIComponent(config.password.orEmpty())}"
            } else {
                username
            }
        } else {
            ""
        }

        val userPart = if (userAuth.isNotEmpty()) "$userAuth@" else ""
        val portPart = ":${config.serverPort ?: "22"}"
        val hostPart = config.server.orEmpty()
        val remarkPart = if (config.remarks.isNotEmpty()) "#${Utils.encodeURIComponent(config.remarks)}" else ""

        val queryParams = mutableListOf<String>()
        if (config.sshUpstreamProxy.isNotNullEmpty()) queryParams.add("proxy=${Utils.encodeURIComponent(config.sshUpstreamProxy.orEmpty())}")
        if (config.sshUdpGatewayHost.isNotNullEmpty()) {
            queryParams.add("udpgw_host=${Utils.encodeURIComponent(config.sshUdpGatewayHost.orEmpty())}")
            queryParams.add("udpgw_port=${config.sshUdpGatewayPort ?: 7300}")
        }
        if (config.sni.isNotNullEmpty()) queryParams.add("sni=${Utils.encodeURIComponent(config.sni.orEmpty())}")
        if (config.security == "tls") queryParams.add("tls=true")
        if (config.sshPrivateKey.isNotNullEmpty()) queryParams.add("private_key=${Utils.encodeURIComponent(config.sshPrivateKey.orEmpty())}")
        if (config.sshKeyPassphrase.isNotNullEmpty()) queryParams.add("key_passphrase=${Utils.encodeURIComponent(config.sshKeyPassphrase.orEmpty())}")

        val queryString = if (queryParams.isNotEmpty()) "?${queryParams.joinToString("&")}" else ""

        return "ssh://$userPart$hostPart$portPart$queryString$remarkPart"
    }

    private fun queryParamFor(uri: URI, key: String): String? =
        getQueryParam(uri)[key]?.takeIf { it.isNotBlank() }
}

