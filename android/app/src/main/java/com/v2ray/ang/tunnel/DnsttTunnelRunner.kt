package com.v2ray.ang.tunnel

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import com.spicyvpn.dnstt.mobile.DnsttClient
import com.spicyvpn.dnstt.mobile.Mobile
import com.spicyvpn.dnstt.mobile.SocketProtector
import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.launch

/** Runs the DNSTT client and exposes its local SOCKS5 endpoint to Xray. */
class DnsttTunnelRunner(private val profile: ProfileItem) {
    private var client: DnsttClient? = null
    @Volatile private var stopped = false
    private var ssh: SshTunnelRunner? = null
    var localPort: Int = 0
        private set

    fun start(): Int {
        check(!stopped) { "DNSTT start was cancelled" }
        val resolver = profile.server?.trim().orEmpty()
        val resolverPort = profile.serverPort?.toIntOrNull() ?: 53
        val domain = profile.host?.trim().orEmpty()
        val publicKey = profile.publicKey?.trim().orEmpty()
        if (resolver.isBlank() || resolverPort !in 1..65535 || domain.isBlank() || publicKey.isBlank()) {
            throw IOException("DNSTT requires a DNS resolver, tunnel domain, and server public key")
        }

        val port = ServerSocket(0).use { it.localPort }
        val dnsAddress = if (resolver.contains(":")) "[$resolver]:$resolverPort" else "$resolver:$resolverPort"
        val dnsttClient = Mobile.newClient(dnsAddress, domain, publicKey, "127.0.0.1:$port")
        dnsttClient.setProtectSocket(object : SocketProtector {
            override fun protectSocket(fd: Long): Boolean =
                !com.v2ray.ang.handler.SettingsManager.isVpnMode() ||
                    com.v2ray.ang.core.CoreServiceManager.serviceControl?.get()?.vpnProtect(fd.toInt()) == true
        })
        client = dnsttClient
        try {
            dnsttClient.start()
            check(!stopped) { "DNSTT start was cancelled" }
            if (!dnsttClient.isRunning) throw IOException("DNSTT client did not establish its tunnel")
        } catch (e: Exception) {
            try {
                dnsttClient.stop()
            } catch (_: Exception) {
            }
            throw IOException("Could not start DNSTT tunnel: ${e.message ?: e.javaClass.simpleName}", e)
        }

        localPort = if (!profile.username.isNullOrBlank()) {
            val sshProfile = profile.copy(configType = com.v2ray.ang.enums.EConfigType.SSH,
                server = "127.0.0.1", serverPort = port.toString(), security = null, sni = null)
            SshTunnelRunner(sshProfile, socketProtector = { true }).also { ssh = it }.start()
        } else port
        if (stopped) {
            ssh?.stop()
            dnsttClient.stop()
            throw IOException("DNSTT start was cancelled")
        }
        LogUtil.i(AppConfig.TAG, "DNSTT client running on local SOCKS5 port $port")
        return localPort
    }

    fun stop() {
        stopped = true
        ssh?.stop()
        ssh = null
        try {
            val closing = client
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { closing?.stop() }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to stop DNSTT client: ${e.message}")
        } finally {
            client = null
            localPort = 0
        }
    }
}
