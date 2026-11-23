package com.v2ray.ang.tunnel

import android.app.Service
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TorTunnelRunner(private val service: Service) {
    private var child: Process? = null
    @Volatile private var stopped = false
    val port = 21908

    fun start(upstreamPort: Int? = null): Int {
        val directory = File(service.filesDir, "tor").apply { mkdirs() }
        val config = File(directory, "torrc")
        val mode = com.v2ray.ang.handler.MmkvManager.decodeSettingsString("pref_tor_bridge_mode") ?: "none"
        val custom = com.v2ray.ang.handler.MmkvManager.decodeSettingsString("pref_tor_bridge_lines").orEmpty()
        val bridges = if (custom.isNotBlank()) custom.lines() else {
            val official = org.json.JSONObject(service.assets.open("tor_pt_config.json").bufferedReader().use { it.readText() })
                .getJSONObject("bridges").optJSONArray(if (mode == "meek_lite") "meek" else mode)
            if (official != null) (0 until official.length()).map { official.getString(it) }.take(8)
            else service.assets.open("tor_bridges_default.lst").bufferedReader().use { it.readLines() }
                .filter { it.startsWith("$mode ") }.take(8)
        }
        val bridgeConfig = TorBridgeConfig.build(mode, bridges, File(service.applicationInfo.nativeLibraryDir), upstreamPort != null)
        val upstream = upstreamPort?.let {
            require(it in 1..65535)
            "Socks5Proxy 127.0.0.1:$it"
        }.orEmpty()
        config.writeText("""
            DataDirectory ${directory.absolutePath}
            SocksPort 127.0.0.1:$port
            ClientOnly 1
            AvoidDiskWrites 1
            Log notice stdout
            $upstream
            $bridgeConfig
        """.trimIndent())
        val ready = CountDownLatch(1)
        val logFile = File(directory, "last-start.log").apply { writeText("") }
        val process = ProcessBuilder(File(service.applicationInfo.nativeLibraryDir, "libtor.so").absolutePath,
            "-f", config.absolutePath).redirectErrorStream(true).start()
        child = process
        Thread({
            runCatching {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    if (logFile.length() < 262144) logFile.appendText(it + "\n")
                    if (it.contains("Bootstrapped 100%")) ready.countDown()
                }
            }
            }
        }, "spicyvpn-tor-log").apply { isDaemon = true; start() }
        var connected = false
        repeat(150) {
            if (!connected && !stopped && process.isAlive) connected = ready.await(1, TimeUnit.SECONDS)
        }
        if (!connected || stopped || !process.isAlive) {
            stop()
            error("Tor did not finish connecting")
        }
        monitorNativeChild(service, process, "Tor") { stopped }
        return port
    }

    fun stop() {
        stopped = true
        child?.destroy()
        child = null
    }
}
