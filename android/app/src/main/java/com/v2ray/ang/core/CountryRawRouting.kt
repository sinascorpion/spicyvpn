package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object CountryRawRouting {
    fun apply(raw: String, countries: Set<String>, iranAssets: Boolean): String {
        if (countries.isEmpty()) return raw
        val root = JsonParser.parseString(raw).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds") ?: error("Missing Xray outbounds")
        val direct = outbounds.firstOrNull {
            val outbound = it.asJsonObject
            outbound.get("protocol")?.asString == "freedom" &&
                !outbound.has("proxySettings") &&
                !outbound.getAsJsonObject("settings").let { settings -> settings?.has("redirect") == true }
        }
            ?.asJsonObject ?: JsonObject().apply {
                addProperty("protocol", "freedom")
                add("settings", JsonObject())
                outbounds.add(this)
            }
        if (direct.get("tag")?.asString.isNullOrBlank()) {
            var tag = "spicy-country-direct"
            while (outbounds.any { it.asJsonObject.get("tag")?.asString == tag }) tag += "-1"
            direct.addProperty("tag", tag)
        }
        val tag = direct.get("tag").asString
        val routing = root.getAsJsonObject("routing") ?: JsonObject().also { root.add("routing", it) }
        val rules = JsonArray()
        for (country in countries.sorted()) {
            require(country in setOf("ir", "ru", "cn"))
            val ip = when {
                country == "ir" && iranAssets -> "ext:geoip_c4u.dat:ir"
                country == "cn" -> "ext:geoip_cn.dat:cn"
                else -> "geoip:$country"
            }
            val domain = if (country == "ir" && iranAssets) "ext:geosite_c4u.dat:ir"
                else "geosite:" + if (country == "cn") "cn" else "category-$country"
            for ((field, value) in listOf("domain" to domain, "ip" to ip)) {
                rules.add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", tag)
                    add(field, JsonArray().apply { add(value) })
                })
            }
        }
        routing.getAsJsonArray("rules")?.forEach { rules.add(it) }
        routing.add("rules", rules)
        if (!routing.has("domainStrategy") || routing.get("domainStrategy").asString == "AsIs") {
            routing.addProperty("domainStrategy", "IPIfNonMatch")
        }
        return root.toString()
    }
}
