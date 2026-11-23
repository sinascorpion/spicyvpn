package com.v2ray.ang.fmt

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NativeDnsFmtTest {
    private fun link(scheme: String, json: String) = scheme + "://" + Base64.getEncoder().encodeToString(json.toByteArray())
    @Test fun acceptsBothNativeClients() {
        val content = """{"config":{"DOMAINS":["t.example.com"],"ENCRYPTION_KEY":"test"},"resolvers":["127.0.0.1:5353"]}"""
        assertNotNull(NativeDnsFmt.parse(link("masterdns", content)))
        assertNotNull(NativeDnsFmt.parse(link("stormdns", content)))
    }
    @Test fun rejectsMissingResolvers() {
        assertNull(NativeDnsFmt.parse(link("masterdns", """{"config":{"DOMAINS":["test"],"ENCRYPTION_KEY":"test"}}""")))
    }
    @Test fun acceptsReferenceProfileLinks() {
        val json = """{"schema":"whitedns.profile","version":1,"profile":{"name":"My DNS","server":{"domain":"t.example.com","encryption_key":"test-key","encryption_method":2}}}"""
        val profile = NativeDnsFmt.parse(link("stormdns", json))!!
        assertEquals("My DNS", profile.remarks)
        val native = com.google.gson.JsonParser.parseString(profile.nativeConfig).asJsonObject
        assertEquals(2, native.getAsJsonObject("config").get("DATA_ENCRYPTION_METHOD").asInt)
        assertEquals("t.example.com", native.getAsJsonObject("config").getAsJsonArray("DOMAINS")[0].asString)
        assertEquals(2, native.getAsJsonArray("resolvers").size())
    }
    @Test fun rejectsResolverLineInjection() {
        val content = """{"config":{"DOMAINS":["test"],"ENCRYPTION_KEY":"key"},"resolvers":["127.0.0.1\nmalicious"]}"""
        assertNull(NativeDnsFmt.parse(link("stormdns", content)))
    }
}
