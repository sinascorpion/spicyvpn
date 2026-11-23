package com.v2ray.ang.tunnel

import android.content.Context
import android.net.VpnService
import android.net.IpPrefix
import android.os.Build
import java.net.InetAddress
import com.google.gson.JsonParser
import com.v2ray.ang.handler.MmkvManager

object CountryVpnRoutes {
    @Volatile private var database: Map<String, List<CountryRoutePlan.Prefix>>? = null
    private val builderExclusions = java.util.WeakHashMap<VpnService.Builder, List<CountryRoutePlan.Prefix>>()
    fun enabledCountries(): Set<String> = MmkvManager.decodeRoutingRulesets().orEmpty()
        .filter { it.enabled && it.outboundTag == "direct" && it.remarks in setOf("Bypass ir", "Bypass ru", "Bypass cn") }
        .map { it.remarks.orEmpty().removePrefix("Bypass ") }.toSet()

    fun exclusions(context: Context): List<CountryRoutePlan.Prefix> {
        val selected = enabledCountries()
        if (selected.isEmpty()) return emptyList()
        val loaded = database ?: synchronized(this) {
            database ?: context.assets.open("country_ip_routes.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject.entrySet().associate { (country, values) ->
                    country to values.asJsonArray.map { value ->
                        val parts = value.asString.split('/')
                        CountryRoutePlan.Prefix(parts[0], parts[1].toInt())
                    }
                }
            }.also { database = it }
        }
        return selected.flatMap { country ->
            requireNotNull(loaded[country]) { "Missing country routing database: $country" }
        }
    }

    fun addRoute(context: Context, builder: VpnService.Builder, address: String, prefix: Int) {
        // Decide before mutating the builder: unsupported country tables must not
        // prevent a native tunnel from connecting or leave partially applied rules.
        val excluded = synchronized(builderExclusions) {
            builderExclusions.getOrPut(builder) {
                try {
                    val requested = exclusions(context)
                    val size = if (Build.VERSION.SDK_INT >= 33) requested.size else
                        CountryRoutePlan.subtract(listOf(CountryRoutePlan.Prefix("0.0.0.0", 0),
                            CountryRoutePlan.Prefix("::", 0)), requested).size
                    if (size <= 6000) requested else {
                        android.util.Log.w("CountryVpnRoutes", "Country bypass ignored: route table exceeds native engine budget")
                        emptyList()
                    }
                } catch (error: Exception) {
                    android.util.Log.w("CountryVpnRoutes", "Country bypass unavailable; preserving native VPN routes", error)
                    emptyList()
                }
            }
        }
        if (excluded.isEmpty()) {
            builder.addRoute(address, prefix)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val included = IpPrefix(InetAddress.getByName(address), prefix)
            val cuts = excluded.mapNotNull { value ->
                val cut = IpPrefix(InetAddress.getByName(value.address), value.length)
                when {
                    included.contains(cut.address) -> cut
                    cut.contains(included.address) -> included
                    else -> null
                }
            }.distinct()
            builder.addRoute(address, prefix)
            cuts.forEach { builder.excludeRoute(it) }
            return
        }
        val routes = CountryRoutePlan.subtract(listOf(CountryRoutePlan.Prefix(address, prefix)), excluded)
        routes.forEach { builder.addRoute(it.address, it.length) }
    }

}
