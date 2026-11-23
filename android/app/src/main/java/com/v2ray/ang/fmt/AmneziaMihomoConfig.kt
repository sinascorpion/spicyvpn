package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil

object AmneziaMihomoConfig {
    fun build(profile: ProfileItem): String {
        val addresses = profile.localAddress.orEmpty().split(',').map { it.trim().substringBefore('/') }
        val proxy = linkedMapOf<String, Any>(
            "name" to "SpicyVPN", "type" to "wireguard", "udp" to true,
            "server" to requireNotNull(profile.server).removeSurrounding("[", "]"),
            "port" to requireNotNull(profile.serverPort?.toIntOrNull()),
            "private-key" to requireNotNull(profile.secretKey),
            "public-key" to requireNotNull(profile.publicKey),
            "allowed-ips" to listOf("0.0.0.0/0", "::/0")
        )
        addresses.firstOrNull { !it.contains(':') && it.isNotBlank() }?.let { proxy["ip"] = it }
        addresses.firstOrNull { it.contains(':') }?.let { proxy["ipv6"] = it }
        profile.preSharedKey?.takeIf { it.isNotBlank() }?.let { proxy["pre-shared-key"] = it }
        profile.mtu?.let { proxy["mtu"] = it }
        val options = profile.amneziaOptions ?: profile.obfsPassword.orEmpty().split(';').mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1] else null
        }.toMap()
        val typed = options.mapValues { (key, value) ->
            when {
                key in setOf("random-trailers", "disable-cookies") -> value.toBooleanStrict()
                key.matches(Regex("h[1-4]|i[1-5]|j[1-3]")) || key in setOf("header-protection-key", "content-padding-addition",
                    "rekey-after-time", "rekey-timeout", "reject-after-time", "keepalive-timeout", "max-handshake-attempts") -> value
                else -> value.toLongOrNull() ?: error("Invalid Amnezia option $key")
            }
        }
        proxy["amnezia-wg-option"] = typed
        return JsonUtil.toJson(mapOf("proxies" to listOf(proxy), "rules" to listOf("MATCH,SpicyVPN")))
    }
}
