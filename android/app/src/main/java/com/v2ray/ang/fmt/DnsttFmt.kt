package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.idnPort
import com.v2ray.ang.util.Utils
import java.net.URI

object DnsttFmt : FmtBase() {
    /**
     * Parses dnstt:// link
     * Format: dnstt://pubkey@nameserver:port?domain=tunnel.example.com#remarks
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.DNSTT)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "dnstt" } }
        config.server = uri.idnHost
        config.serverPort = if (uri.idnPort > 0) uri.idnPort.toString() else "53"
        config.publicKey = queryParam["publickey"] ?: queryParam["pubkey"] ?: uri.userInfo.orEmpty()
        config.host = queryParam["domain"] ?: queryParam["host"]
        config.username = queryParam["ssh_user"] ?: queryParam["user"]
        config.password = queryParam["ssh_pass"] ?: queryParam["pass"]
        config.seed = queryParam["ssh_port"] ?: "22" // store ssh port in seed or keep 22

        val sshHost = queryParam["ssh_host"] ?: queryParam["sshHost"] ?: queryParam["remote_host"]
        if (!sshHost.isNullOrBlank()) {
            config.sni = sshHost // Store SSH server IP/host in sni
        }

        return config
    }

    fun toUri(config: ProfileItem): String {
        val serverPart = config.server.orEmpty()
        val portPart = if (!config.serverPort.isNullOrBlank()) ":${config.serverPort}" else ":53"
        val queryParams = mutableListOf<String>()
        if (!config.publicKey.isNullOrBlank()) queryParams.add("publickey=${Utils.encodeURIComponent(config.publicKey.orEmpty())}")
        if (!config.host.isNullOrBlank()) queryParams.add("domain=${Utils.encodeURIComponent(config.host.orEmpty())}")
        if (!config.sni.isNullOrBlank()) queryParams.add("ssh_host=${Utils.encodeURIComponent(config.sni.orEmpty())}")
        if (!config.username.isNullOrBlank()) queryParams.add("ssh_user=${Utils.encodeURIComponent(config.username.orEmpty())}")
        if (!config.password.isNullOrBlank()) queryParams.add("ssh_pass=${Utils.encodeURIComponent(config.password.orEmpty())}")
        val sshPort = config.seed?.takeIf { it.isNotBlank() } ?: "22"
        if (sshPort != "22") queryParams.add("ssh_port=$sshPort")

        val query = if (queryParams.isNotEmpty()) "?" + queryParams.joinToString("&") else ""
        val remarks = if (config.remarks.isNotBlank()) "#" + Utils.encodeURIComponent(config.remarks) else ""
        return "dnstt://$serverPart$portPart$query$remarks"
    }
}
