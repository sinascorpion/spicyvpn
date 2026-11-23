package com.v2ray.ang.tunnel

import android.app.Service
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.MihomoFmt
import com.v2ray.ang.util.JsonUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Runs the official native core behind the existing VPN's local SOCKS bridge. */
class MihomoTunnelRunner(private val profile: ProfileItem, private val service: Service) {
    @Volatile private var stopped = false
    @Volatile private var process: Process? = null
    private var directory: File? = null

    fun start(): Int {
        check(!stopped) { "Mihomo start cancelled" }
        val binary = File(service.applicationInfo.nativeLibraryDir, "libmihomo.so")
        check(binary.isFile) { "Mihomo core is not installed for this device ABI" }
        val port = ServerSocket(0).use { it.localPort }
        val raw = if (profile.configType == com.v2ray.ang.enums.EConfigType.AMNEZIAWG) {
            com.v2ray.ang.fmt.AmneziaMihomoConfig.build(profile)
        } else requireNotNull(profile.nativeConfig)
        val config = MihomoFmt.readConfig(raw)
        // Never let an imported file expose a listener or create a second TUN.
        config["mixed-port"] = port
        config["port"] = 0
        config["socks-port"] = 0
        config["redir-port"] = 0
        config["tproxy-port"] = 0
        config["allow-lan"] = false
        config["bind-address"] = "127.0.0.1"
        config["tun"] = mapOf("enable" to false)
        config["listeners"] = emptyList<Any>()
        config["tunnels"] = emptyList<Any>()
        config["authentication"] = emptyList<Any>()
        (config["dns"] as? Map<*, *>)?.let { dns ->
            config["dns"] = dns.entries.associate { it.key.toString() to it.value }.toMutableMap().apply { this["listen"] = "" }
        }
        config["external-controller"] = ""
        config["external-controller-unix"] = ""
        config["external-controller-pipe"] = ""
        config["external-ui"] = ""
        config["log-level"] = "warning"
        val dir = File(service.cacheDir, "mihomo-session-${System.nanoTime()}").apply { mkdirs() }
        directory = dir
        val file = File(dir, "config.json").apply { writeText(JsonUtil.toJson(config)) }
        val started = ProcessBuilder(binary.absolutePath, "-d", dir.absolutePath, "-f", file.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(dir, "core.log")).start()
        process = started
        try {
            repeat(150) {
                check(!stopped && started.isAlive) { "Mihomo core stopped before its proxy was ready" }
                val ready = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                }.isSuccess
                if (ready) {
                    monitorNativeChild(service, started, "Mihomo") { stopped }
                    return port
                }
                Thread.sleep(100)
            }
            error("Mihomo local proxy did not become ready")
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    fun stop() {
        stopped = true
        process?.destroy()
        process?.let {
            if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                it.destroyForcibly()
                it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        process = null
        directory?.deleteRecursively()
        directory = null
    }
}
