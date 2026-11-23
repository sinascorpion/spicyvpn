package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

object AetherFmt {
    fun normalize(raw: String): String {
        if (!raw.trimStart().startsWith("{")) return raw
        val root = com.google.gson.JsonParser.parseString(raw).asJsonObject
        require(root.has("protocol"))
        val element = root.get("protocol")
        val protocols = listOf("masque", "wg", "gool", "smart", "mim")
        val protocol = if (element.asJsonPrimitive.isNumber) protocols.getOrNull(element.asInt) ?: error("Unsupported Aether protocol") else element.asString
        require(protocol in setOf("masque", "wg", "gool", "mim", "h2", "h3", "wiw"))
        val options = linkedMapOf("protocol" to if (protocol == "gool" && root.get("goolMode")?.asString == "classic") "wiw" else protocol)
        fun option(source: String, target: String) { root.get(source)?.takeIf { !it.isJsonNull && it.asString.isNotBlank() }?.let { options[target] = it.asString } }
        option("scanMode", "scan"); option("ipScan", "ip")
        root.get("scan")?.let { options["scan"] = if (it.asJsonPrimitive.isNumber) listOf("balanced", "turbo", "thorough", "verified", "ironclad").getOrNull(it.asInt) ?: error("Invalid scan mode") else it.asString }
        root.get("ip")?.let { options["ip"] = if (it.asJsonPrimitive.isNumber) listOf("v4", "v6", "both").getOrNull(it.asInt) ?: error("Invalid IP mode") else it.asString }
        root.get("transport")?.let { options["h2"] = if (it.asString in setOf("h2", "http2", "1")) "1" else "0" }
        option("wiwOuterPeer", "outer"); option("wiwInnerPeer", "inner")
        option("peer", "peer"); option("h2Fragment", "fragment"); option("ech", "ech")
        option("upstreamProxy", "proxy"); option("dns", "dns"); option("wgKeepalive", "keepalive")
        option("tlsGroups", "groups"); option("reprovision", "reprovision")
        return "aether://?" + options.entries.joinToString("&") { (key, value) -> "$key=${Utils.encodeURIComponent(value)}" }
    }

    fun parse(raw: String): ProfileItem? {
        val normalized = runCatching { normalize(raw) }.getOrNull() ?: return null
        if (!normalized.startsWith(EConfigType.AETHER.protocolScheme, ignoreCase = true)) return null
        val config = ProfileItem.create(EConfigType.AETHER)
        config.nativeConfig = normalized
        config.remarks = normalized.substringAfter('#', "Aether").let(Utils::decodeURIComponent).ifBlank { "Aether" }
        return config
    }
}
