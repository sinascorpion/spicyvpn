package com.v2ray.ang.tunnel

import android.app.Service
import android.net.VpnService
import com.example.spicyvpn.aether.NativeAetherBridge
import com.example.spicyvpn.aether.NativeEngineListener
import com.example.spicyvpn.aether.NativeSocketProtector
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs the Aether engine in SOCKS mode as an upstream of SpicyVPN's existing VPN service. */
class AetherTunnelRunner(private val service: Service, private val profile: com.v2ray.ang.dto.entities.ProfileItem,
    private val upstreamProxy: String = "", private val requestedListenPort: Int = 0) {
    private val executor = Executors.newSingleThreadExecutor()
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<String?>(null)
    private var localPort = 0
    @Volatile private var stopped = false

    fun start(): Int {
        check(NativeAetherBridge.isLoaded) { "Aether native engine is unavailable" }
        localPort = ServerSocket(requestedListenPort).use { it.localPort }
        val configPath = File(service.filesDir, "aether.toml").absolutePath
        val uri = profile.nativeConfig?.let(android.net.Uri::parse)
        val protocol = (uri?.getQueryParameter("protocol") ?: "masque").lowercase(java.util.Locale.ROOT)
        val classicEndpoints = !uri?.getQueryParameter("outer").isNullOrBlank() ||
            !uri?.getQueryParameter("inner").isNullOrBlank()
        val transport = when (protocol) {
            "gool" -> if (classicEndpoints) "wiw" else "gool"
            "wireguard-over-masque", "wg-over-masque", "new-gool" -> "gool"
            "wiw", "warp-in-warp", "gool-classic" -> "wiw"
            "wg", "wireguard" -> "wg"
            "mim", "masque-in-masque" -> "mim"
            "masque", "h3" -> "h3"
            "h2", "masque-h2", "masque-http2" -> "h2"
            else -> error("Unsupported Aether protocol: $protocol")
        }
        val config = JSONObject()
            .put("mode", "proxy")
            .put("configPath", configPath)
            .put("listenPort", localPort)
            .put("transport", transport)
            .put("upstreamProxy", upstreamProxy.ifBlank { uri?.getQueryParameter("proxy").orEmpty() })
            .put("peer", uri?.getQueryParameter("peer"))
            .put("dnsServers", uri?.getQueryParameter("dns").orEmpty())
            .put("wgKeepalive", uri?.getQueryParameter("keepalive")?.toIntOrNull() ?: 0)
            .put("tlsGroups", uri?.getQueryParameter("groups").orEmpty())
            .put("autoReprovision", uri?.getQueryParameter("reprovision") !in listOf("0", "false"))
            .put("scanMode", uri?.getQueryParameter("scan") ?: "balanced")
            .put("ipScan", uri?.getQueryParameter("ip") ?: "both")
            .put("wiwOuter", uri?.getQueryParameter("outer") ?: "")
            .put("wiwInner", uri?.getQueryParameter("inner") ?: "")
            .put("masqueHttp2", uri?.getQueryParameter("h2") in listOf("1", "true"))
            .put("fragmentTls", uri?.getQueryParameter("fragment") in listOf("1", "true"))
            .put("encryptedHello", uri?.getQueryParameter("ech") in listOf("1", "true", "auto"))
            .put("noize", "firewall")
            .put("validationEnabled", true)
            .toString()

        NativeAetherBridge.setSocketProtector(NativeSocketProtector { fd ->
            val vpn = service as? VpnService
            vpn?.protect(fd) ?: true
        })
        val prepared = NativeAetherBridge.prepare(config).getOrElse { error ->
            NativeAetherBridge.setSocketProtector(null)
            throw IllegalStateException("Aether preparation failed: ${error.message}", error)
        }
        check(!stopped) { "Aether start was cancelled" }
        executor.execute {
            val result = NativeAetherBridge.run(config, prepared.peer, -1, object : NativeEngineListener {
                override fun onNativeReady() = ready.countDown()
            })
            if (!result.ok) failure.set(result.error ?: "Aether stopped unexpectedly")
            val wasReady = ready.count == 0L
            ready.countDown()
            if (wasReady && !stopped) android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!stopped) {
                    com.v2ray.ang.util.MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE,
                        failure.get() ?: "Aether tunnel closed")
                    com.v2ray.ang.core.CoreServiceManager.serviceControl?.get()?.stopService()
                }
            }
        }
        if (!ready.await(if (transport == "gool" || upstreamProxy.isNotBlank()) 150 else 45, TimeUnit.SECONDS)) {
            stop()
            throw IllegalStateException("Aether did not become ready in time")
        }
        failure.get()?.let { message ->
            stop()
            throw IllegalStateException(message)
        }
        LogUtil.i(AppConfig.TAG, "Aether SOCKS bridge listening on 127.0.0.1:$localPort")
        return localPort
    }

    fun stop() {
        stopped = true
        NativeAetherBridge.cancelPrepare()
        NativeAetherBridge.stop()
        NativeAetherBridge.setSocketProtector(null)
        executor.shutdownNow()
        localPort = 0
    }
}
