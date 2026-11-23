package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.google.gson.JsonArray
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils
import java.io.File

object CoreConfigManager {
    private var initConfigCache: String? = null
    private var initConfigCacheWithTun: String? = null

    //region get config function

    /**
     * Build the runtime configuration for normal startup.
     */
    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            return toConfigResult(configContext, buildUnifiedConfig(configContext))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build a lightweight configuration for latency testing.
     *
     * The core flow is reused, then non-essential sections are removed.
     */
    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext, applyAfterTransport = false)
            }
            val v2rayConfig = buildUnifiedConfig(configContext, applyAfterTransport = false)
            postProcessForSpeedtest(v2rayConfig)

            return toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config for speedtest: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build configuration for custom profiles.
     */
    private fun buildV2rayCustomConfig(configContext: CoreConfigContext, applyAfterTransport: Boolean = true): ConfigResult {
        val context = configContext.context
        val raw = MmkvManager.decodeServerRaw(configContext.guid)
            ?: return ConfigResult(status = false, guid = configContext.guid, errorMessage = "Custom config is empty")
        var adjustedRaw = applySSettingsToRaw(raw)
        val geoDir = Utils.userAssetPath(context)
        adjustedRaw = CountryRawRouting.apply(adjustedRaw,
            com.v2ray.ang.tunnel.CountryVpnRoutes.enabledCountries(),
            File(geoDir, "geoip_c4u.dat").exists() && File(geoDir, "geosite_c4u.dat").exists())
        val afterFlags = listOf("pref_tor_after", "pref_psiphon_after", "pref_aether_after")
            .map { MmkvManager.decodeSettingsBool(it, false) }
        require(afterFlags.count { it } <= 1) { "Select only one After transport" }
        if (applyAfterTransport && afterFlags.any { it }) {
            require(listOf("pref_tor_before", "pref_warp_before", "pref_warp_after")
                .none { MmkvManager.decodeSettingsBool(it, false) }) { "Conflicting tunnel chain settings" }
            adjustedRaw = AfterTransportConfig.applyRaw(adjustedRaw,
                when { afterFlags[0] -> 21908; afterFlags[2] -> 21708; else -> 21808 })
        }
        val result = ConfigResult(true, configContext.guid, adjustedRaw)
        if (!needTun()) {
            return result
        }

        val json = JsonUtil.parseString(adjustedRaw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return result

        // Check whether package names need to be replaced with UIDs
        if (SettingsManager.canUseProcessRouting()) {
            val rulesJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: JsonArray()

            for (elem in rulesJson) {
                val rule = elem.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val process = rule.get("process")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val packages = process.mapNotNull {
                    it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }.takeIf { it.isNotEmpty() } ?: continue
                val uids = PackageUidResolver.packageNamesToUids(context, packages).takeIf { it.isNotEmpty() } ?: continue

                rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
            }
        }

        // check if tun inbound exists
        val inboundsJson = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { json.add("inbounds", it) }
        val tunNotExists = inboundsJson.none { elem ->
            elem.isJsonObject && elem.asJsonObject.get("protocol")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString == "tun"
        }

        if (tunNotExists) {
            // add tun inbound from template
            val templateConfig = initV2rayConfig(configContext)
            templateConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { inboundTun ->
                inboundTun.settings?.mtu = SettingsManager.getVpnMtu()
                inboundsJson.add(JsonUtil.parseString(JsonUtil.toJson(inboundTun)))
            }
        }

        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
    }

    /**
     * Build one unified configuration for every non-custom profile type.
     *
     * The analyzed outbound plan is consumed in order and converted to concrete
     * outbounds before routing, DNS, and runtime extras are assembled.
     */
    private fun buildUnifiedConfig(configContext: CoreConfigContext, applyAfterTransport: Boolean = true): V2rayConfig {
        require(configContext.resolvedOutbounds.isNotEmpty()) { "resolvedOutbounds must not be empty for a non-CUSTOM context" }
        val primaryResolvedOutbound = configContext.resolvedOutbounds.first()

        val v2rayConfig = initV2rayConfig(configContext)
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.remarks = primaryResolvedOutbound.profile.remarks

        configureInbounds(v2rayConfig)

        if (v2rayConfig.outbounds.isNotEmpty()) {
            v2rayConfig.outbounds.removeAt(0)
        }
        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        val policyGroupBalancerTags = mutableMapOf<String, String>()
        val balancerStrategies = mutableListOf<BalancerStrategy>()

        // resolvedOutbounds is a single ordered plan: index 0 is primary and must be prepended,
        // the rest are routing outbounds and can be appended.
        configContext.resolvedOutbounds.forEachIndexed { index, spec ->
            buildOutbounds(
                resolvedOutbound = spec,
                prepend = index == 0,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }

        // User routing rules (policyGroupBalancerTags rewrites TAG_PROXY→balancer when main is POLICYGROUP).
        configureRouting(configContext, v2rayConfig, policyGroupBalancerTags)
        configureFakeDns(v2rayConfig)
        val tcpOnlyTunnel = primaryResolvedOutbound.profile.configType in listOf(EConfigType.SSH, EConfigType.DNSTT) ||
            primaryResolvedOutbound.profile.subscriptionId == "spicyvpn-s-internal" ||
            MmkvManager.decodeSettingsBool("pref_tor_after", false) ||
            MmkvManager.decodeSettingsBool("pref_psiphon_after", false)
        configureDns(v2rayConfig, policyGroupBalancerTags, tcpOnlyTunnel)
        configureLocalDns(v2rayConfig, tcpOnlyTunnel)
        configureRootModeDns(v2rayConfig)

        // (added by getDns / getCustomLocalDns) to use the balancer, then add
        // the catch-all balancer rule.
        if (primaryResolvedOutbound.resolvedType == CoreResolvedType.POLICYGROUP) {
            if (v2rayConfig.routing.domainStrategy == "IPIfNonMatch") {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        ip = arrayListOf("0.0.0.0/0", "::/0"),
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp,udp",
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            }
        }

        applyObservability(v2rayConfig, balancerStrategies)
        applySpeedDisabled(v2rayConfig)
        resolveOutboundDomainsToHosts(v2rayConfig)

        val autoFallback = primaryResolvedOutbound.profile.subscriptionId == com.v2ray.ang.tunnel.AutoFallback.SUBSCRIPTION
        if (!autoFallback) applySButtonSettings(v2rayConfig)
        if (applyAfterTransport && !autoFallback && primaryResolvedOutbound.profile.subscriptionId != "spicyvpn-s-internal") {
            val torAfter = MmkvManager.decodeSettingsBool("pref_tor_after", false)
            val psiphonAfter = MmkvManager.decodeSettingsBool("pref_psiphon_after", false)
            val aetherAfter = MmkvManager.decodeSettingsBool("pref_aether_after", false)
            require(listOf(torAfter, psiphonAfter, aetherAfter).count { it } <= 1) { "Select only one After transport" }
            if (torAfter || psiphonAfter || aetherAfter) {
                require(!aetherAfter || primaryResolvedOutbound.profile.configType != EConfigType.AETHER) {
                    "An Aether exit cannot share the same native engine with an Aether upstream"
                }
                require(!MmkvManager.decodeSettingsBool("pref_tor_before", false) &&
                    !MmkvManager.decodeSettingsBool("pref_warp_before", false) &&
                    !MmkvManager.decodeSettingsBool("pref_warp_after", false)) { "Conflicting tunnel chain settings" }
                AfterTransportConfig.apply(v2rayConfig, when { torAfter -> 21908; aetherAfter -> 21708; else -> 21808 })
            }
        }

        return v2rayConfig
    }

    private fun applySSettingsToRaw(raw: String): String {
        if (!listOf("pref_warp_before", "pref_warp_after", "pref_tor_before", "pref_mux_enabled")
                .any { MmkvManager.decodeSettingsBool(it, false) }) return raw
        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return raw
        val original = json.getAsJsonArray("outbounds") ?: return raw
        if (!json.has("routing")) json.add("routing", com.google.gson.JsonObject().apply { add("rules", JsonArray()) })
        val routingJson = json.getAsJsonObject("routing")
        if (!routingJson.has("rules")) routingJson.add("rules", JsonArray())
        original.forEachIndexed { index, value ->
            if (!value.asJsonObject.has("tag")) value.asJsonObject.addProperty("tag", if (index == 0) AppConfig.TAG_PROXY else "custom_$index")
        }
        val parsed = com.google.gson.Gson().fromJson(json, V2rayConfig::class.java)
        val originalRules = parsed.routing.rules.size
        applySButtonSettings(parsed)
        parsed.outbounds.forEachIndexed { index, outbound ->
            val updated = com.google.gson.Gson().toJsonTree(outbound).asJsonObject
            if (index >= original.size()) original.add(updated) else {
                val target = original[index].asJsonObject
                for (key in listOf("proxySettings", "mux")) updated.get(key)?.let { target.add(key, it) }
                updated.getAsJsonObject("streamSettings")?.get("sockopt")?.let { sockopt ->
                    val stream = target.getAsJsonObject("streamSettings") ?: com.google.gson.JsonObject().also { target.add("streamSettings", it) }
                    val options = stream.getAsJsonObject("sockopt") ?: com.google.gson.JsonObject().also { stream.add("sockopt", it) }
                    sockopt.asJsonObject.entrySet().forEach { (key, value) -> options.add(key, value) }
                }
            }
        }
        val added = parsed.routing.rules.size - originalRules
        if (added > 0) {
            val routing = json.getAsJsonObject("routing") ?: com.google.gson.JsonObject().also { json.add("routing", it) }
            val rules = JsonArray()
            parsed.routing.rules.take(added).forEach { rules.add(com.google.gson.Gson().toJsonTree(it)) }
            routing.getAsJsonArray("rules")?.forEach { rules.add(it) }
            routing.add("rules", rules)
        }
        return json.toString()
    }

    private fun applySButtonSettings(v2rayConfig: V2rayConfig) {
        if (MmkvManager.getSelectServer() == "spicyvpn-s-internal") return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)) return
        if (v2rayConfig.outbounds.isEmpty()) return
        val primaryOutbound = v2rayConfig.outbounds.first()
        if (MmkvManager.decodeSettingsBool("pref_tor_before", false)) {
            require(!MmkvManager.decodeSettingsBool("pref_warp_before", false) &&
                !MmkvManager.decodeSettingsBool("pref_warp_after", false)) { "Tor and WARP chaining is not configured" }
            val torOutbound = requireNotNull(CoreOutboundBuilder.convert(
                ProfileItem(configType = EConfigType.SOCKS, server = "127.0.0.1", serverPort = "21908")))
            torOutbound.tag = "spicy-tor"
            torOutbound.mux = null
            v2rayConfig.outbounds.add(torOutbound)
            primaryOutbound.ensureSockopt().dialerProxy = torOutbound.tag
        }

        // 1. Mux settings
        val muxEnabled = MmkvManager.decodeSettingsBool("pref_mux_enabled", false)
        if (muxEnabled) {
            val concurrencyStr = MmkvManager.decodeSettingsString("pref_mux_concurrency") ?: "8"
            val concurrency = concurrencyStr.toIntOrNull() ?: 8
            primaryOutbound.mux = V2rayConfig.OutboundBean.MuxBean(enabled = true, concurrency = concurrency)
        } else {
            primaryOutbound.mux = V2rayConfig.OutboundBean.MuxBean(enabled = false)
        }

        // 2. Warp settings
        val warpBefore = MmkvManager.decodeSettingsBool("pref_warp_before", false)
        val warpAfter = MmkvManager.decodeSettingsBool("pref_warp_after", false)
        if (warpBefore || warpAfter) {
            val warpEndpoint = MmkvManager.decodeSettingsString("pref_warp_endpoint") ?: "engage.cloudflareclient.com:2408"
            val parts = warpEndpoint.split(":")
            val configuredHost = parts.getOrNull(0) ?: "engage.cloudflareclient.com"
            // WARP is added after DNS routing. Resolving its default hostname through
            // WARP After can deadlock the tunnel before its first handshake.
            val host = if (configuredHost.equals(AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN, ignoreCase = true)) {
                AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES.first()
            } else {
                configuredHost
            }
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 2408

            val privKey = MmkvManager.decodeSettingsString("pref_warp_private_key").orEmpty()
            val pubKey = MmkvManager.decodeSettingsString("pref_warp_public_key").orEmpty()
            if (privKey.isBlank() || pubKey.isBlank()) {
                error("WARP requires a registered private and public key")
            } else {
            val localAddr = MmkvManager.decodeSettingsString("pref_warp_local_address") ?: "172.16.0.2/32"
            val reservedStr = MmkvManager.decodeSettingsString("pref_warp_reserved") ?: "0,0,0"
            val reservedList = try {
                reservedStr.split(",").map { it.trim().toInt() }
            } catch (e: Exception) {
                listOf(0, 0, 0)
            }
            val mtuVal = MmkvManager.decodeSettingsString("pref_warp_mtu")?.toIntOrNull() ?: 1280
            require(port in 1..65535) { "Invalid WARP endpoint port" }
            require(reservedList.size == 3 && reservedList.all { it in 0..255 }) { "WARP reserved must contain three bytes" }
            require(mtuVal in 576..9000) { "Invalid WARP MTU" }

            val keepAliveVal = MmkvManager.decodeSettingsString("pref_warp_keepalive")?.toIntOrNull() ?: 5
            require(keepAliveVal in 0..65535) { "Invalid WARP keepalive" }
            val wnoiseVal = MmkvManager.decodeSettingsString("pref_warp_wnoise") ?: "quic"
            val wnoiseCountVal = MmkvManager.decodeSettingsString("pref_warp_wnoisecount")?.toIntOrNull() ?: 15
            val wnoiseDelayVal = MmkvManager.decodeSettingsString("pref_warp_wnoisedelay") ?: "1-2"
            val wpayloadSizeVal = MmkvManager.decodeSettingsString("pref_warp_wpayloadsize") ?: "5-10"

            val warpOutbound = V2rayConfig.OutboundBean(
                protocol = "wireguard",
                tag = "warp",
                settings = V2rayConfig.OutboundBean.OutSettingsBean(
                    secretKey = privKey,
                    address = localAddr.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    peers = listOf(
                        V2rayConfig.OutboundBean.OutSettingsBean.WireGuardBean(
                            endpoint = "$host:$port",
                            publicKey = pubKey,
                            preSharedKey = MmkvManager.decodeSettingsString("pref_warp_preshared_key").orEmpty().ifBlank { null },
                            keepAlive = keepAliveVal,
                            wnoise = wnoiseVal,
                            wnoisecount = wnoiseCountVal,
                            wnoisedelay = wnoiseDelayVal,
                            wpayloadsize = wpayloadSizeVal
                        )
                    ),
                    reserved = reservedList,
                    mtu = mtuVal
                )
            )
            if (warpBefore) {
                // Reach the selected VPN server through WARP first, then let the
                // selected configuration carry the user's traffic as usual.
                v2rayConfig.outbounds.add(warpOutbound)
                v2rayConfig.outbounds.forEach { outbound ->
                    if (outbound === primaryOutbound || outbound.tag == AppConfig.TAG_PROXY || outbound.tag.startsWith("proxy_")) {
                        outbound.ensureSockopt().dialerProxy = "warp"
                    }
                }
            } else {
                // WireGuard does not support streamSettings. Use Xray's outbound
                // proxy chain so its packets are carried by the selected VPN config.
                warpOutbound.proxySettings = V2rayConfig.OutboundBean.ProxySettingsBean(
                    tag = primaryOutbound.tag
                )
                v2rayConfig.outbounds.add(warpOutbound)
                v2rayConfig.routing.rules.add(
                    0,
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp,udp",
                        outboundTag = "warp"
                    )
                )
            }
        }
    }

    }

    /**
     * Convert one analyzed outbound entry into concrete outbounds and register
     * them to the runtime configuration.
     */
    private fun buildOutbounds(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        if (resolvedOutbound.tag in existingTags) {
            LogUtil.w(AppConfig.TAG, "Resolved outbound tag '${resolvedOutbound.tag}' already exists, skipping duplicated entry")
            return
        }

        when (resolvedOutbound.resolvedType) {
            CoreResolvedType.NORMAL -> handleNormalResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.PROXYCHAIN -> handleProxyChainResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.POLICYGROUP -> handlePolicyGroupResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }
    }

    /**
     * Build and insert a single-node outbound entry.
     */
    private fun handleNormalResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val profile = resolvedOutbound.resolvedProfiles.firstOrNull() ?: run {
            LogUtil.w(AppConfig.TAG, "NORMAL resolved outbound '${resolvedOutbound.tag}' has empty resolvedProfiles, skipping")
            return
        }
        val outbound = convertProfile2Outbound(profile) ?: run {
            LogUtil.w(AppConfig.TAG, "Could not convert NORMAL resolved outbound '${resolvedOutbound.tag}' profile to outbound, skipping")
            return
        }
        outbound.tag = resolvedOutbound.tag
        if (prepend) {
            v2rayConfig.outbounds.add(0, outbound)
        } else {
            v2rayConfig.outbounds.add(outbound)
        }
        existingTags.add(resolvedOutbound.tag)
    }

    /**
     * Build and insert a multi-hop chain entry.
     */
    private fun handleProxyChainResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val chainOutbounds = resolvedOutbound.resolvedProfiles.map { profile ->
            convertProfile2Outbound(profile)
                ?: throw IllegalArgumentException("Unsupported proxy chain hop: ${profile.configType}")
        }.toMutableList()
        if (chainOutbounds.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has no valid profiles, skipping")
            return
        }
        if (chainOutbounds.size == 1) {
            val outbound = chainOutbounds.first()
            outbound.tag = resolvedOutbound.tag
            if (prepend) {
                v2rayConfig.outbounds.add(0, outbound)
            } else {
                v2rayConfig.outbounds.add(outbound)
            }
            existingTags.add(resolvedOutbound.tag)
            return
        }

        val chainTags = chainOutbounds.mapIndexed { index, _ ->
            if (index == 0) {
                resolvedOutbound.tag
            } else {
                "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-$index"
            }
        }
        if (chainTags.any { it in existingTags }) {
            LogUtil.w(
                AppConfig.TAG,
                "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has colliding hop tags, skipping"
            )
            return
        }

        chainOutbounds.forEachIndexed { index, outbound ->
            outbound.tag = chainTags[index]
        }
        for (i in 0 until chainOutbounds.size - 1) {
            chainOutbounds[i].ensureSockopt().dialerProxy = chainOutbounds[i + 1].tag
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, chainOutbounds)
        } else {
            v2rayConfig.outbounds.addAll(chainOutbounds)
        }
        chainOutbounds.forEach { existingTags.add(it.tag) }
    }

    /**
     * Build and insert a policy-group entry and its balancer metadata.
     */
    private fun handlePolicyGroupResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        val memberPairs = resolvedOutbound.resolvedProfiles.mapNotNull { profile ->
            convertProfile2Outbound(profile)?.let { ob -> ob to profile }
        }
        if (memberPairs.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' has no valid member outbounds, skipping")
            return
        }

        val memberTagPrefix = "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-"
        val membersToAdd = mutableListOf<V2rayConfig.OutboundBean>()
        memberPairs.forEachIndexed { index, (outbound, profile) ->
            val memberTag = "$memberTagPrefix${index + 1}-${profile.remarks.trim()}"
            if (memberTag in existingTags) {
                return@forEachIndexed
            }
            outbound.tag = memberTag
            membersToAdd.add(outbound)
            existingTags.add(memberTag)
        }

        if (membersToAdd.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' produced no unique member tags, skipping"
            )
            return
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, membersToAdd)
        } else {
            v2rayConfig.outbounds.addAll(membersToAdd)
        }

        val balancerTag = if (resolvedOutbound.tag == AppConfig.TAG_PROXY) {
            AppConfig.TAG_BALANCER
        } else {
            "${AppConfig.TAG_BALANCER_PRE}-${resolvedOutbound.tag}"
        }
        val strategy = buildBalancerStrategy(
            policyGroupType = resolvedOutbound.profile.policyGroupType,
            selector = listOf(memberTagPrefix),
            balancerTag = balancerTag,
        )
        val existingBalancers = v2rayConfig.routing.balancers?.toMutableList() ?: mutableListOf()
        if (existingBalancers.none { it.tag == balancerTag }) {
            existingBalancers.add(strategy.balancer)
            v2rayConfig.routing.balancers = existingBalancers
        }
        balancerStrategies.add(strategy)
        policyGroupBalancerTags[resolvedOutbound.tag] = balancerTag
    }

    /**
     * Trim runtime sections that are not needed for latency testing.
     */
    private fun postProcessForSpeedtest(v2rayConfig: V2rayConfig) {
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.inbounds.clear()
        v2rayConfig.routing.rules.clear()
        v2rayConfig.dns = null
        v2rayConfig.fakedns = null
        v2rayConfig.stats = null
        v2rayConfig.policy = null
        v2rayConfig.outbounds.forEach { key -> key.mux = null }
    }

    /**
     * Serialize a runtime configuration into a standard result object.
     */
    private fun toConfigResult(configContext: CoreConfigContext, v2rayConfig: V2rayConfig): ConfigResult {
        return ConfigResult(
            status = true,
            guid = configContext.guid,
            content = JsonUtil.toJsonPretty(v2rayConfig) ?: ""
        )
    }

    /**
     * Load the base template from cache or assets and parse it.
     */
    private fun initV2rayConfig(configContext: CoreConfigContext): V2rayConfig {
        val context = configContext.context
        val assets: String
        if (needTun()) {
            assets = initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config_with_tun.json")
            }
            initConfigCacheWithTun = assets
        } else {
            assets = initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config.json")
            }
            initConfigCache = assets
        }
        return JsonUtil.fromJson(assets, V2rayConfig::class.java)
            ?: error("Failed to parse config template")
    }


    //endregion


    //region some sub function

    private fun needTun(): Boolean {
        return SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()
    }

    /**
     * Configure inbound listeners and related runtime options.
     */
    private fun configureInbounds(v2rayConfig: V2rayConfig) {
        val vpn = SettingsManager.isVpnMode()
        val useHev = SettingsManager.isUsingHevTun()
        val forcedByHev = vpn && useHev
        val forcedBySocksRoot = SettingsManager.isRootMode()
                || MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)
        val shareProxy = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) &&
            !MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)

        val enableLocalProxy = forcedByHev || forcedBySocksRoot 
                || MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
                || shareProxy

        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val inbound1 = v2rayConfig.inbounds[0]
        if (inbound1.settings == null) {
            inbound1.settings = V2rayConfig.InboundBean.InSettingsBean()
        }

        inbound1.listen = AppConfig.LOOPBACK
        inbound1.port = socksPort
        inbound1.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        if (socksUsername != null && socksPassword != null) {
            inbound1.settings?.auth = "password"
            inbound1.settings?.accounts = listOf(
                V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(
                    user = socksUsername,
                    pass = socksPassword
                )
            )
        } else {
            inbound1.settings?.auth = "noauth"
            inbound1.settings?.accounts = null
        }
        val fakedns = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true &&
            !MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)
        val sniffAllTlsAndHttp =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false
        inbound1.sniffing?.enabled = fakedns || sniffAllTlsAndHttp
        inbound1.sniffing?.routeOnly =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
        if (!sniffAllTlsAndHttp) {
            inbound1.sniffing?.destOverride?.clear()
        }
        if (fakedns) {
            inbound1.sniffing?.destOverride?.add("fakedns")
        }

        val inbound2 = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java)
            ?: error("Failed to clone inbound template")
        inbound2.tag = EConfigType.HTTP.name.lowercase()
        inbound2.port = SettingsManager.getHttpPort()
        inbound2.protocol = EConfigType.HTTP.name.lowercase()
        inbound2.settings?.auth = null
        inbound2.settings?.udp = null
        v2rayConfig.inbounds.add(inbound2)
        val lanAddresses = if (shareProxy) LanSharingConfig.addresses() else emptyList()
        LanSharingConfig.addListeners(v2rayConfig, lanAddresses)
        MmkvManager.encodeSettings("pref_active_lan_addresses", lanAddresses.joinToString(","))
        MmkvManager.encodeSettings("pref_active_socks_port", socksPort.toString())
        MmkvManager.encodeSettings("pref_active_http_port", SettingsManager.getHttpPort().toString())

        if (!enableLocalProxy) {
            v2rayConfig.inbounds.removeIf { it.protocol == "socks" || it.protocol == "http" }
        }

        if (needTun()) {
            val inboundTun = v2rayConfig.inbounds.firstOrNull { e -> e.tag == "tun" }
            inboundTun?.settings?.mtu = SettingsManager.getVpnMtu()
            inboundTun?.sniffing = inbound1.sniffing
        }
    }

    /**
     * Enable fake DNS when local DNS and fake DNS are both enabled.
     */
    private fun configureFakeDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)) {
            v2rayConfig.fakedns = null
            return
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        ) {
            v2rayConfig.fakedns = listOf(V2rayConfig.FakednsBean())
        }
    }

    /**
     * Collect domain rules that target one outbound tag.
     */
    private fun collectUserRuleDomainsByTag(tag: String): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && key.outboundTag == tag && !key.domain.isNullOrEmpty()) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Collect domain rules that target non-builtin outbound tags.
     */
    private fun collectCustomOutboundDomains(): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && !AppConfig.BUILTIN_OUTBOUND_TAGS.contains(key.outboundTag)
                && !key.domain.isNullOrEmpty()
            ) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Configure local DNS inbounds, outbounds, and routing rules.
     */
    private fun configureLocalDns(v2rayConfig: V2rayConfig, forceTcp: Boolean = false) {
        if (!forceTcp && MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) != true
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE) != true
        ) {
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) {
            val geositeCn = arrayListOf(AppConfig.GEOSITE_CN)
            val proxyDomain = collectUserRuleDomainsByTag(AppConfig.TAG_PROXY)
            val directDomain = collectUserRuleDomainsByTag(AppConfig.TAG_DIRECT)
            val finalDomain = geositeCn.plus(proxyDomain).plus(directDomain).distinct()
            // fakedns with all domains to make it always top priority
            v2rayConfig.dns?.servers?.add(
                0,
                V2rayConfig.DnsBean.ServersBean(
                    address = "fakedns",
                    domains = finalDomain
                )
            )
        }

        if (SettingsManager.isVpnMode()) {
            if (SettingsManager.isUsingHevTun()) {
                //hev-socks5-tunnel dns routing
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("socks"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("tun"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            }
        }

        // DNS outbound
        if (v2rayConfig.outbounds.none { e -> e.protocol == "dns" && e.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = if (forceTcp) V2rayConfig.OutboundBean.OutSettingsBean(network = "tcp") else null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * In the root mode the whole device's traffic (incl. raw DNS) is funneled
     * into the core's SOCKS inbound, exactly like the VPN+hev path. Hijack port-53 to the
     * core's DNS module so queries are resolved via the configured resolver through the
     * proxy instead of leaking to (or being mis-resolved by) the local network resolver.
     * Independent of the local-DNS toggle, which is not exposed for root mode.
     */
    private fun configureRootModeDns(v2rayConfig: V2rayConfig) {
        if (!SettingsManager.isRootMode()) return

        if (v2rayConfig.routing.rules.none { it.outboundTag == "dns-out" && it.port == "53" }) {
            v2rayConfig.routing.rules.add(
                0,
                V2rayConfig.RoutingBean.RulesBean(
                    inboundTag = arrayListOf("socks"),
                    outboundTag = "dns-out",
                    port = "53",
                )
            )
        }
        if (v2rayConfig.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * Remove speed-test runtime sections when the feature is disabled.
     */
    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) {
            v2rayConfig.stats = null
            v2rayConfig.policy = null
        }
    }

    /**
     * Configure DNS servers, hosts, and DNS routing rules.
     */
    private fun configureDns(
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>,
        forceTcp: Boolean = false,
    ) {
        val hosts = mutableMapOf<String, Any>()
        val servers = ArrayList<Any>()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)) {
            val resolver = SettingsManager.getRemoteDnsServers().first()
            if (resolver.contains("://")) {
                val host = java.net.URI(resolver).host?.removeSurrounding("[", "]")
                if (!host.isNullOrBlank() && !Utils.isPureIpAddress(host)) {
                    hosts[host] = java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
                }
            }
            val adapted = DnsTlsConfig.adapt(resolver, v2rayConfig, AppConfig.TAG_DIRECT)
            val dnsServer: Any = if (adapted.startsWith("udp://")) {
                val uri = java.net.URI(adapted)
                V2rayConfig.DnsBean.ServersBean(address = uri.host.removeSurrounding("[", "]"), port = if (uri.port < 0) 53 else uri.port)
            } else adapted
            v2rayConfig.dns = V2rayConfig.DnsBean(servers = arrayListOf(dnsServer), hosts = hosts, tag = AppConfig.TAG_DNS)
            v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_DIRECT, inboundTag = arrayListOf(AppConfig.TAG_DNS)))
            return
        }

        //remote Dns
        val remoteDns = SettingsManager.getRemoteDnsServers().map { address ->
            val adapted = DnsTlsConfig.adapt(address, v2rayConfig, v2rayConfig.outbounds.first().tag)
            if (forceTcp && Utils.isPureIpAddress(adapted)) {
                if (adapted.contains(':')) "tcp://[$adapted]" else "tcp://$adapted"
            } else adapted
        }
        val proxyDomain = (collectUserRuleDomainsByTag(AppConfig.TAG_PROXY) + collectCustomOutboundDomains()).distinct()
        remoteDns.forEach {
            servers.add(it)
        }
        if (proxyDomain.isNotEmpty()) {
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = remoteDns.first(),
                    domains = proxyDomain,
                )
            )
        }

        // domestic DNS
        val domesticDns = SettingsManager.getDomesticDnsServers().map {
            DnsTlsConfig.adapt(it, v2rayConfig, AppConfig.TAG_DIRECT)
        }
        val directDomain = collectUserRuleDomainsByTag(AppConfig.TAG_DIRECT)
        val isCnRoutingMode = directDomain.contains(AppConfig.GEOSITE_CN)
        val cnRegionFilter = { domain: String ->
            domain.startsWith("geosite:") && (domain.endsWith("-cn") || domain.endsWith("@cn"))
                    || domain == AppConfig.GEOSITE_CN
        }
        val finalDirectDomain = if (isCnRoutingMode) directDomain.filterNot {
            cnRegionFilter(it)
        } else directDomain
        val domesticDnsTags = mutableListOf<String>()
        domesticDns.forEachIndexed { index, element ->
            val tag = AppConfig.TAG_DOMESTIC_DNS + index
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = element,
                    domains = finalDirectDomain,
                    skipFallback = true,
                    tag = tag
                )
            )
            domesticDnsTags.add(tag)
        }
        if (isCnRoutingMode) {
            val geoipCn = arrayListOf(AppConfig.GEOIP_CN)
            val cnRegionDomain = directDomain.filter { cnRegionFilter(it) }
            domesticDns.forEachIndexed { index, element ->
                val geositeCnDnsTag = AppConfig.TAG_DOMESTIC_DNS + index + "_cn_expect"
                servers.add(
                    V2rayConfig.DnsBean.ServersBean(
                        address = element,
                        domains = cnRegionDomain,
                        expectIPs = geoipCn,
                        skipFallback = true,
                        tag = geositeCnDnsTag
                    )
                )
                domesticDnsTags.add(geositeCnDnsTag)
            }
        }

        //block dns
        val blkDomain = collectUserRuleDomainsByTag(AppConfig.TAG_BLOCKED)
        if (blkDomain.isNotEmpty()) {
            hosts.putAll(blkDomain.map { it to AppConfig.LOOPBACK })
        }

        // hardcode googleapi rule to fix play store problems
        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN

        // hardcode popular Android Private DNS rule to fix localhost DNS problem
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES
        hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_ADGUARD_DOMAIN] = AppConfig.DNS_ADGUARD_ADDRESSES
        hosts[AppConfig.DNS_NEXTDNS_DOMAIN] = AppConfig.DNS_NEXTDNS_ADDRESSES
        hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        //User DNS hosts
        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",")
                ?.filter { it.isNotEmpty() }
                ?.filter { it.contains(":") }
                ?.associate { it.split(":").let { (k, v) -> k to v } }
            if (userHostsMap != null) {
                hosts.putAll(userHostsMap)
            }
        }

        // DNS dns
        v2rayConfig.dns = V2rayConfig.DnsBean(
            servers = servers,
            hosts = hosts,
            tag = AppConfig.TAG_DNS,
            enableParallelQuery = if ((domesticDns.size + remoteDns.size) > 2) true else null
        )

        // DNS routing
        v2rayConfig.routing.rules.add(
            V2rayConfig.RoutingBean.RulesBean(
                outboundTag = AppConfig.TAG_DIRECT,
                inboundTag = domesticDnsTags,
                domain = null
            )
        )
        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]
        if (dnsProxyBalancerTag != null) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    balancerTag = dnsProxyBalancerTag,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        } else {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_PROXY,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        }
    }


    //endregion


    //region outbound related functions


    /**
     * Resolve outbound domains to IPs and write resolved hosts to DNS map.
     */
    private fun resolveOutboundDomainsToHosts(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "1") {
            return
        }

        val proxyOutboundList = v2rayConfig.getAllProxyOutbound()
        val dns = v2rayConfig.dns ?: return
        val newHosts = dns.hosts?.toMutableMap() ?: mutableMapOf()
        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true

        for (item in proxyOutboundList) {
            val domain = item.getServerAddress()
            if (domain.isNullOrEmpty()) {
                continue
            }

            if (newHosts.containsKey(domain)) {
                item.ensureSockopt().domainStrategy = "UseIP"
                item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                    prioritizeIPv6 = preferIpv6,
                    interleave = 2
                )
                continue
            }

            val resolvedIps = HttpUtil.resolveHostToIP(domain, preferIpv6)
            if (resolvedIps.isNullOrEmpty()) {
                continue
            }

            item.ensureSockopt().domainStrategy = "UseIP"
            item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                prioritizeIPv6 = preferIpv6,
                interleave = 2
            )
            newHosts[domain] = if (resolvedIps.size == 1) {
                resolvedIps[0]
            } else {
                resolvedIps
            }
        }

        dns.hosts = newHosts
    }

    /**
     * Convert one profile object into one outbound object.
     */
    private fun convertProfile2Outbound(profileItem: ProfileItem): V2rayConfig.OutboundBean? {
        return CoreOutboundBuilder.convert(profileItem)
    }

    //endregion


    //region routing related functions


    /**
     * Merge probe settings from all balancer strategies into the runtime config.
     */
    private fun applyObservability(v2rayConfig: V2rayConfig, strategies: List<BalancerStrategy>) {
        val allObsSelectors = strategies
            .mapNotNull { it.observatory?.subjectSelector }
            .flatten()
            .distinct()
        val obsTemplate = strategies.firstNotNullOfOrNull { it.observatory }
        if (obsTemplate != null && allObsSelectors.isNotEmpty()) {
            v2rayConfig.observatory = V2rayConfig.ObservatoryObject(
                subjectSelector = allObsSelectors,
                probeUrl = obsTemplate.probeUrl,
                probeInterval = obsTemplate.probeInterval,
                enableConcurrency = obsTemplate.enableConcurrency
            )
        }

        val allBurstSelectors = strategies
            .mapNotNull { it.burstObservatory?.subjectSelector }
            .flatten()
            .distinct()
        val burstTemplate = strategies.firstNotNullOfOrNull { it.burstObservatory }
        if (burstTemplate != null && allBurstSelectors.isNotEmpty()) {
            v2rayConfig.burstObservatory = V2rayConfig.BurstObservatoryObject(
                subjectSelector = allBurstSelectors,
                pingConfig = burstTemplate.pingConfig
            )
        }
    }

    /**
     * Configure routing domain strategy and append enabled user rules.
     */
    private fun configureRouting(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {

        v2rayConfig.routing.domainStrategy =
            MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
                ?: "AsIs"

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.remarks in setOf("Bypass ir", "Bypass ru", "Bypass cn") && key.enabled && key.outboundTag == "direct") {
                // Xray combines different match fields with AND; country IPs and domains need separate rules.
                appendRoutingUserRule(configContext, key.copy(ip = null), v2rayConfig, policyGroupBalancerTags)
                appendRoutingUserRule(configContext, key.copy(domain = null), v2rayConfig, policyGroupBalancerTags)
            } else appendRoutingUserRule(configContext, key, v2rayConfig, policyGroupBalancerTags)
        }
        if (com.v2ray.ang.tunnel.CountryVpnRoutes.enabledCountries().isNotEmpty() && v2rayConfig.routing.domainStrategy == "AsIs") {
            v2rayConfig.routing.domainStrategy = "IPIfNonMatch"
        }
    }

    /**
     * Convert one rule item and append it to routing rules.
     */
    private fun appendRoutingUserRule(
        configContext: CoreConfigContext,
        item: RulesetItem?,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {
        val context = configContext.context
        if (item == null || !item.enabled) {
            return
        }

        val rule = JsonUtil.fromJson(JsonUtil.toJson(item), V2rayConfig.RoutingBean.RulesBean::class.java) ?: return

        // Check whether the geo data files are present on disk. If they are NOT,
        // we must NOT rewrite rules to ext: form — Xray would fail with
        // "common/geodata: failed to open <file>" and the whole core would refuse
        // to start. Falling back to the plain geoip:/geosite: form lets Xray use
        // its built-in compiled-in geodata, which always works (at reduced accuracy).
        val geoDir = Utils.userAssetPath(configContext.context)
        val hasGeoip = File(geoDir, AppConfig.GEOIP_DAT).exists()
        val hasGeosite = File(geoDir, AppConfig.GEOSITE_DAT).exists()
        val hasGeoipCn = File(geoDir, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT).exists()
        val hasCustomCnGeoip = File(geoDir, "geoip_cn.dat").exists()

        // Replace specific geoip rules with ext versions
        rule.ip?.let { ipList ->
            val updatedIpList = ArrayList<String>()
            ipList.forEach { ip ->
                when (ip) {
                    AppConfig.GEOIP_CN -> {
                        if (hasCustomCnGeoip) updatedIpList.add("ext:geoip_cn.dat:cn")
                        else if (hasGeoipCn) updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn")
                        else updatedIpList.add(ip) // built-in fallback
                    }
                    AppConfig.GEOIP_PRIVATE -> {
                        if (hasGeoipCn) updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private")
                        else updatedIpList.add(ip) // built-in fallback
                    }
                    else -> {
                        val geoMatch = Regex("geoip:(\\w+)").find(ip)
                        if (geoMatch != null) {
                            val countryCode = geoMatch.groupValues[1]
                            val hasC4uGeoip = File(geoDir, "geoip_c4u.dat").exists()
                            if (countryCode.equals("ir", ignoreCase = true) && hasC4uGeoip) {
                                updatedIpList.add("ext:geoip_c4u.dat:ir")
                            } else if (countryCode.equals("cn", ignoreCase = true) && hasCustomCnGeoip) {
                                updatedIpList.add("ext:geoip_cn.dat:cn")
                            } else if (hasGeoip) {
                                updatedIpList.add("ext:${AppConfig.GEOIP_DAT}:${countryCode}")
                            } else {
                                updatedIpList.add(ip)
                            }
                        } else {
                            updatedIpList.add(ip)
                        }
                    }
                }
            }
            rule.ip = updatedIpList
        }

        // Replace geosite rules with ext versions for non-CN/private entries
        rule.domain?.let { domainList ->
            val updatedDomainList = ArrayList<String>()
            domainList.forEach { domain ->
                when (domain) {
                    AppConfig.GEOSITE_CN, AppConfig.GEOSITE_PRIVATE ->
                        updatedDomainList.add(domain)
                    else -> {
                        val geoMatch = Regex("geosite:(\\S+)").find(domain)
                        if (geoMatch != null && domain.startsWith("geosite:")) {
                            val tag = geoMatch.groupValues[1]
                            val isIranBypassed = MmkvManager.decodeRoutingRulesets()?.any { it.remarks == "Bypass ir" && it.enabled } ?: false
                            val hasC4uGeosite = File(geoDir, "geosite_c4u.dat").exists()
                            if ((tag.equals("ir", ignoreCase = true) || tag.equals("category-ir", ignoreCase = true)) && hasC4uGeosite && isIranBypassed) {
                                updatedDomainList.add("ext:geosite_c4u.dat:ir")
                            } else if (hasGeosite) {
                                updatedDomainList.add("ext:${AppConfig.GEOSITE_DAT}:${tag}")
                            } else {
                                updatedDomainList.add(domain)
                            }
                        } else {
                            updatedDomainList.add(domain)
                        }
                    }
                }
            }
            rule.domain = updatedDomainList
        }

        if (SettingsManager.canUseProcessRouting()) {
            // Convert process package names to UIDs
            rule.process?.let { processList ->
                if (processList.isNotEmpty()) {
                    val uids = PackageUidResolver.packageNamesToUids(context, processList)
                    rule.process = uids.ifEmpty { null }
                }
            }
        } else {
            rule.process = null
        }

        val outboundTag = rule.outboundTag

        // Route rules targeting a custom policy-group tag should hit its balancer.
        policyGroupBalancerTags[outboundTag]?.let { balancerTag ->
            rule.outboundTag = null
            rule.balancerTag = balancerTag
        }

        // If the outbound tag is a custom one that failed to inject, fall back to proxy
        if (!outboundTag.isNullOrBlank()
            && outboundTag !in policyGroupBalancerTags
            && outboundTag !in AppConfig.BUILTIN_OUTBOUND_TAGS
            && v2rayConfig.outbounds.none { it.tag == outboundTag }
        ) {
            LogUtil.w(AppConfig.TAG, "Outbound tag '$outboundTag' not found, falling back to '${AppConfig.TAG_PROXY}'")
            rule.outboundTag = AppConfig.TAG_PROXY
        }

        v2rayConfig.routing.rules.add(rule)
    }


    /**
     * Build balancer and probe settings from one policy-group strategy value.
     */
    private fun buildBalancerStrategy(
        policyGroupType: String?,
        selector: List<String>,
        balancerTag: String = AppConfig.TAG_BALANCER,
    ): BalancerStrategy {
        val probeUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
        val strategyType = BalancerStrategyType.from(policyGroupType)
        val balancer = V2rayConfig.RoutingBean.BalancerBean(
            tag = balancerTag,
            selector = selector,
            strategy = V2rayConfig.RoutingBean.StrategyObject(type = strategyType.policyGroupType)
        )
        val observatory = if (strategyType.requiresObservatory) {
            V2rayConfig.ObservatoryObject(
                subjectSelector = selector,
                probeUrl = probeUrl,
                probeInterval = "3m",
                enableConcurrency = true
            )
        } else null
        val burstObservatory = if (strategyType.requiresBurstObservatory) {
            V2rayConfig.BurstObservatoryObject(
                subjectSelector = selector,
                pingConfig = V2rayConfig.BurstObservatoryObject.PingConfigObject(
                    destination = probeUrl,
                    interval = "5m",
                    sampling = 2,
                    timeout = "30s"
                )
            )
        } else null
        return BalancerStrategy(balancer, observatory, burstObservatory)
    }

    /**
     * Carry balancer data plus optional probe settings for later merge.
     */
    private data class BalancerStrategy(
        val balancer: V2rayConfig.RoutingBean.BalancerBean,
        val observatory: V2rayConfig.ObservatoryObject? = null,
        val burstObservatory: V2rayConfig.BurstObservatoryObject? = null,
    )

    private fun hasBundledAsset(context: Context, fileName: String): Boolean {
        return try {
            context.assets.open(fileName).use { }
            true
        } catch (e: Exception) {
            false
        }
    }

    //endregion
}
