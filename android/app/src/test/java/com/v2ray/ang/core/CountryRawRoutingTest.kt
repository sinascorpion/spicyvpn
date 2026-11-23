package com.v2ray.ang.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CountryRawRoutingTest {
    private val raw = """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{}}],"routing":{"rules":[{"type":"field","port":"443","outboundTag":"proxy"}]}}"""
    @Test fun disabledDoesNotChangeAnything() = assertEquals(raw, CountryRawRouting.apply(raw, emptySet(), true))
    @Test fun everySelectableCountryIsIndependent() {
        for (country in listOf("ir", "ru", "cn")) {
            val root = JsonParser.parseString(CountryRawRouting.apply(raw, setOf(country), true)).asJsonObject
            val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
            assertEquals(3, rules.size())
            assertEquals(when (country) { "ir" -> "ext:geoip_c4u.dat:ir"; "cn" -> "ext:geoip_cn.dat:cn"; else -> "geoip:$country" },
                rules[1].asJsonObject.getAsJsonArray("ip")[0].asString)
        }
        val root = JsonParser.parseString(CountryRawRouting.apply(raw, setOf("ir", "ru", "cn"), true)).asJsonObject
        assertEquals(7, root.getAsJsonObject("routing").getAsJsonArray("rules").size())
    }
    @Test fun redirectedFreedomMustNotBeUsedAsCountryDirect() {
        val input = """{"outbounds":[{"protocol":"freedom","tag":"proxy","settings":{"redirect":"127.0.0.1:1234"}}]}"""
        val root = JsonParser.parseString(CountryRawRouting.apply(input, setOf("ir"), true)).asJsonObject
        assertEquals(2, root.getAsJsonArray("outbounds").size())
        assertEquals("spicy-country-direct", root.getAsJsonObject("routing").getAsJsonArray("rules")[0]
            .asJsonObject.get("outboundTag").asString)
    }
    @Test fun domainsAndIpsUseSeparatePriorityRules() {
        val root = JsonParser.parseString(CountryRawRouting.apply(raw, setOf("ir"), true)).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(3, rules.size())
        assertTrue(rules[0].asJsonObject.has("domain"))
        assertFalse(rules[0].asJsonObject.has("ip"))
        assertEquals("ext:geoip_c4u.dat:ir", rules[1].asJsonObject.getAsJsonArray("ip")[0].asString)
        assertEquals("443", rules[2].asJsonObject.get("port").asString)
        assertEquals("IPIfNonMatch", root.getAsJsonObject("routing").get("domainStrategy").asString)
    }
    @Test fun enabledCountriesOnlyAndExistingDirectPreserved() {
        val input = """{"outbounds":[{"protocol":"freedom","tag":"user-direct","settings":{"domainStrategy":"UseIPv4"}}]}"""
        val root = JsonParser.parseString(CountryRawRouting.apply(input, setOf("cn"), false)).asJsonObject
        assertEquals(1, root.getAsJsonArray("outbounds").size())
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals("user-direct", rules[0].asJsonObject.get("outboundTag").asString)
        assertEquals("geosite:cn", rules[0].asJsonObject.getAsJsonArray("domain")[0].asString)
    }
}
