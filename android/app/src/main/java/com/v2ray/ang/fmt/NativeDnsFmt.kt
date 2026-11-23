package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

/** Link payload is base64 JSON: {config: {...native options...}, resolvers: ["IP:port"]}. */
object NativeDnsFmt {
    fun parse(raw: String): ProfileItem? = runCatching {
        val type = if (raw.startsWith("masterdns://", true)) EConfigType.MASTERDNS else EConfigType.STORMDNS
        require(raw.startsWith(type.protocolScheme, true))
        val encoded = raw.substringAfter("://").substringBefore('#')
        val payload = String(runCatching { java.util.Base64.getDecoder().decode(encoded) }
            .getOrElse { java.util.Base64.getUrlDecoder().decode(encoded) }, Charsets.UTF_8)
        require(payload.length <= 1_000_000)
        val original = JsonParser.parseString(payload).asJsonObject
        val root = if (original.has("schema")) {
            require(original.get("schema").asString == "whitedns.profile" && original.get("version").asInt == 1)
            val server = original.getAsJsonObject("profile").getAsJsonObject("server")
            manualPayload(server.get("domain").asString, server.get("encryption_key").asString,
                server.get("encryption_method").asInt, listOf("1.1.1.1:53", "8.8.8.8:53"))
        } else original
        val config = root.getAsJsonObject("config")
        require(config.getAsJsonArray("DOMAINS").size() > 0)
        require(config.get("ENCRYPTION_KEY").asString.isNotBlank())
        val resolvers = root.getAsJsonArray("resolvers")
        require(resolvers.size() in 1..1000)
        resolvers.forEach { require(!it.asString.contains('\n') && !it.asString.contains('\r') && it.asString.isNotBlank()) }
        ProfileItem.create(type).apply {
            remarks = Utils.decodeURIComponent(raw.substringAfter('#', if (original.has("profile")) original.getAsJsonObject("profile").get("name").asString else type.name))
            nativeConfig = root.toString()
        }
    }.getOrNull()

    fun manualPayload(domain: String, key: String, method: Int, resolvers: List<String>): com.google.gson.JsonObject {
        require(method in 0..5 && domain.isNotBlank() && key.isNotBlank() && resolvers.isNotEmpty())
        val config = com.google.gson.JsonObject().apply {
            add("DOMAINS", com.google.gson.JsonArray().apply { domain.split(',').map(String::trim).filter(String::isNotBlank).forEach { add(it) } })
            addProperty("ENCRYPTION_KEY", key)
            addProperty("DATA_ENCRYPTION_METHOD", method)
        }
        return com.google.gson.JsonObject().apply {
            add("config", config)
            add("resolvers", com.google.gson.JsonArray().apply { resolvers.forEach { add(it) } })
        }
    }
}
