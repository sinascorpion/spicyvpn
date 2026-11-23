package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object MihomoFmt {
    fun readConfig(raw: String): MutableMap<String, Any?> {
        require(raw.length <= 2_000_000) { "Mihomo configuration is too large" }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 20
            codePointLimit = 2_000_000
        }
        val value = Yaml(SafeConstructor(options)).load<Any>(raw)
        require(value is Map<*, *>) { "Mihomo requires a YAML mapping" }
        require(value["proxies"] is List<*> || value["proxy-providers"] is Map<*, *>) {
            "Mihomo requires proxies or proxy-providers"
        }
        return value.entries.associate { (key, item) ->
            require(key is String) { "Invalid YAML configuration key" }
            key to item
        }.toMutableMap()
    }

    fun parse(raw: String): ProfileItem? = runCatching {
        val content = if (raw.startsWith("mihomo://", true)) {
            val encoded = raw.substringAfter("://").substringBefore('#')
            String(runCatching { java.util.Base64.getDecoder().decode(encoded) }
                .getOrElse { java.util.Base64.getUrlDecoder().decode(encoded) }, Charsets.UTF_8)
        } else raw
        readConfig(content)
        ProfileItem.create(EConfigType.MIHOMO).apply {
            remarks = if (raw.startsWith("mihomo://", true)) {
                Utils.decodeURIComponent(raw.substringAfter('#', "Mihomo"))
            } else "Mihomo"
            nativeConfig = content
        }
    }.getOrNull()
}
