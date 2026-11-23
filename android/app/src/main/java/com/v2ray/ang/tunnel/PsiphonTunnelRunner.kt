package com.v2ray.ang.tunnel

import android.app.Service
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class PsiphonTunnelRunner(
    private val service: android.content.Context,
    val port: Int = 21808,
    private val temporary: Boolean = false,
    private val automaticCdn: Boolean? = null
) {
    private var child: Process? = null
    @Volatile private var stopped = false

    fun start(upstreamPort: Int? = null): Int {
        val directory = File(service.filesDir, if (temporary) "psiphon-request-$port" else "psiphon").apply { mkdirs() }
        val base = JSONObject(service.assets.open("psiphon_bootstrap.json").bufferedReader().use { it.readText() })
        base.put("DataRootDirectory", directory.absolutePath)
        base.put("LocalSocksProxyPort", port)
        base.put("LocalHttpProxyPort", 0)
        base.put("EmitDiagnosticNotices", false)
        base.put("EstablishTunnelTimeoutSeconds", 90)
        val preferences = PsiphonPreferences.build(
            { com.v2ray.ang.handler.MmkvManager.decodeSettingsString(it) },
            { key, fallback -> com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(key, fallback) },
            upstreamPort != null)
        val settings = JSONObject(com.google.gson.Gson().toJson(preferences))
        settings.keys().forEach { base.put(it, settings.get(it)) }
        if (automaticCdn != null) {
            base.remove("EgressRegion")
            base.remove("LimitTunnelProtocols")
            base.remove("FrontedMeekCDNScanSpec")
            base.remove("FrontedMeekCDNScanBuiltInSets")
            base.put("FrontedMeekCDNScanUseBuiltInSpec", true)
            base.put("AggressiveEstablishment", true)
            if (automaticCdn) {
                base.put("LimitTunnelProtocols", org.json.JSONArray(listOf(
                    "FRONTED-MEEK-OSSH", "FRONTED-MEEK-CDN-OSSH",
                    "FRONTED-MEEK-HTTP-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH")))
                base.put("FrontedMeekCDNScanBuiltInSets", org.json.JSONArray(listOf("psiphon-akamai")))
            }
        }
        if (upstreamPort != null) {
            require(upstreamPort in 1..65535)
            base.put("UpstreamProxyURL", "socks5://127.0.0.1:$upstreamPort")
            val protocols = base.optJSONArray("LimitTunnelProtocols")
            if (protocols != null) {
                val tcpProtocols = org.json.JSONArray()
                for (i in 0 until protocols.length()) {
                    val protocol = protocols.getString(i)
                    if (!protocol.contains("QUIC")) tcpProtocols.put(protocol)
                }
                require(tcpProtocols.length() > 0) { "Psiphon chaining requires a TCP transport" }
                base.put("LimitTunnelProtocols", tcpProtocols)
            }
        }
        val country = com.v2ray.ang.handler.MmkvManager.decodeSettingsString("pref_psiphon_country").orEmpty()
        if (automaticCdn == null && country.matches(Regex("[A-Z]{2}"))) base.put("EgressRegion", country)
        val config = File(directory, "client.json").apply { writeText(base.toString()) }
        val ready = CountDownLatch(1)
        val process = ProcessBuilder(File(service.applicationInfo.nativeLibraryDir, "libspicypsiphon.so").absolutePath,
            "-config", config.absolutePath).redirectErrorStream(true).start()
        child = process
        Thread({
            runCatching {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val notice = runCatching { JSONObject(line) }.getOrNull()
                    if (notice?.optString("noticeType") == "AvailableEgressRegions") {
                        notice.optJSONObject("data")?.optJSONArray("regions")?.let { regions ->
                            val valid = (0 until regions.length()).map { regions.optString(it) }
                                .filter { it.matches(Regex("[A-Z]{2}")) }.distinct()
                            if (valid.isNotEmpty()) com.v2ray.ang.handler.MmkvManager.encodeSettings(
                                "pref_psiphon_available_regions", valid.joinToString(","))
                        }
                    }
                    if (notice?.optString("noticeType") == "Tunnels" && (notice.optJSONObject("data")?.optInt("count", 0) ?: 0) > 0) ready.countDown()
                }
            }
            }
        }, "spicyvpn-psiphon-log").apply { isDaemon = true; start() }
        var connected = false
        repeat(95) { if (!connected && !stopped && process.isAlive) connected = ready.await(1, TimeUnit.SECONDS) }
        if (!connected || stopped || !process.isAlive) { stop(); error("Psiphon did not finish connecting") }
        if (!temporary && service is Service) monitorNativeChild(service, process, "Psiphon") { stopped }
        return port
    }

    fun stop() {
        stopped = true
        child?.let { process ->
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        child = null
        if (temporary) File(service.filesDir, "psiphon-request-$port").deleteRecursively()
    }
}
