package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig

object AfterTransportConfig {
    const val UPSTREAM_PORT = 21918
    const val UPSTREAM_TAG = "spicy-s-upstream"

    fun applyRaw(raw: String, transportPort: Int): String {
        val json = org.json.JSONObject(raw)
        val outbounds = json.getJSONArray("outbounds")
        require(outbounds.length() > 0) { "After transport requires an outbound" }
        require((0 until outbounds.length()).none { outbounds.getJSONObject(it).optString("tag") == UPSTREAM_TAG }) {
            "After transport tag conflicts with the imported configuration"
        }
        val routing = json.optJSONObject("routing") ?: org.json.JSONObject().also { json.put("routing", it) }
        val rules = routing.optJSONArray("rules") ?: org.json.JSONArray()
        require((0 until rules.length()).none { rules.getJSONObject(it).has("balancerTag") }) {
            "After transport requires a single outbound or proxy chain"
        }
        val upstream = outbounds.getJSONObject(0)
        val tag = upstream.optString("tag").ifBlank { "proxy" }
        upstream.put("tag", UPSTREAM_TAG)
        val relay = org.json.JSONObject().put("tag", tag).put("protocol", "socks")
            .put("settings", org.json.JSONObject().put("servers", org.json.JSONArray().put(
                org.json.JSONObject().put("address", "127.0.0.1").put("port", transportPort))))
        val chained = org.json.JSONArray().put(relay)
        for (i in 0 until outbounds.length()) chained.put(outbounds.get(i))
        json.put("outbounds", chained)
        val inbounds = json.optJSONArray("inbounds") ?: org.json.JSONArray().also { json.put("inbounds", it) }
        inbounds.put(org.json.JSONObject().put("tag", UPSTREAM_TAG).put("listen", "127.0.0.1")
            .put("port", UPSTREAM_PORT).put("protocol", "socks")
            .put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", false)))
        val ordered = org.json.JSONArray().put(org.json.JSONObject().put("type", "field")
            .put("inboundTag", org.json.JSONArray().put(UPSTREAM_TAG)).put("outboundTag", UPSTREAM_TAG))
        for (i in 0 until rules.length()) ordered.put(rules.get(i))
        routing.put("rules", ordered)
        return json.toString()
    }

    fun apply(config: V2rayConfig, transportPort: Int) {
        val upstream = config.outbounds.first()
        val originalTag = upstream.tag
        require(config.routing.rules.none { it.balancerTag != null }) {
            "After transport requires a single outbound or proxy chain"
        }
        upstream.tag = UPSTREAM_TAG
        val relay = V2rayConfig.OutboundBean(
            tag = originalTag, protocol = "socks", mux = null,
            settings = V2rayConfig.OutboundBean.OutSettingsBean(servers = listOf(
                V2rayConfig.OutboundBean.OutSettingsBean.ServersBean(address = "127.0.0.1", port = transportPort))))
        config.outbounds.add(0, relay)
        config.inbounds.add(V2rayConfig.InboundBean(
            tag = UPSTREAM_TAG, listen = "127.0.0.1", port = UPSTREAM_PORT, protocol = "socks",
            settings = V2rayConfig.InboundBean.InSettingsBean(auth = "noauth", udp = false)))
        // This must precede country and DNS rules to prevent the transport feeding itself.
        config.routing.rules.add(0, V2rayConfig.RoutingBean.RulesBean(
            inboundTag = arrayListOf(UPSTREAM_TAG), outboundTag = UPSTREAM_TAG))
    }
}
