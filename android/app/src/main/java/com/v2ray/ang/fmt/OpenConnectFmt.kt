package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.net.URI

object OpenConnectFmt : FmtBase() {
    fun toUri(profile: ProfileItem): String {
        val host = profile.server.orEmpty().removeSurrounding("[", "]").let { if (it.contains(':')) "[$it]" else it }
        val credentials = profile.username?.let {
            Utils.encodeURIComponent(it) + ":" + Utils.encodeURIComponent(profile.password.orEmpty()) + "@"
        }.orEmpty()
        val query = linkedMapOf("protocol" to profile.method, "group" to profile.serviceName,
            "sni" to profile.sni, "servercert" to profile.pinSHA256, "ca" to profile.nativeConfig)
            .filterValues { !it.isNullOrBlank() }.entries.joinToString("&") {
                it.key + "=" + Utils.encodeURIComponent(it.value.orEmpty())
            }
        return credentials + host + ":" + (profile.serverPort ?: "443") + (profile.path ?: "/") +
            (if (query.isBlank()) "" else "?$query") + "#" + Utils.encodeURIComponent(profile.remarks)
    }

    fun parse(text: String): ProfileItem {
        val uri = URI(Utils.fixIllegalUrl(text))
        require(uri.scheme == "openconnect" && !uri.host.isNullOrBlank()) { "Invalid OpenConnect URL" }
        val query = getQueryParam(uri)
        val credentials = uri.rawUserInfo?.split(":", limit = 2).orEmpty()
        val port = if (uri.port == -1) 443 else uri.port
        require(port in 1..65535) { "Invalid OpenConnect port" }
        return ProfileItem.create(EConfigType.OPENCONNECT).apply {
            server = uri.host
            serverPort = port.toString()
            username = credentials.getOrNull(0)?.let(Utils::decodeURIComponent)
            password = credentials.getOrNull(1)?.let(Utils::decodeURIComponent)
            path = uri.rawPath.ifBlank { "/" }
            method = query["protocol"] ?: "anyconnect"
            require(method in setOf("anyconnect", "nc", "gp", "pulse", "f5", "fortinet", "array")) {
                "Unsupported OpenConnect protocol"
            }
            serviceName = query["group"]
            sni = query["sni"]
            pinSHA256 = query["servercert"]
            nativeConfig = query["ca"]
            remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifBlank { "OpenConnect" }
        }
    }
}
