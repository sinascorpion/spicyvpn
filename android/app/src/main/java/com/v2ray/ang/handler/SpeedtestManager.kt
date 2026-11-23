package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getRemoteIPInfo(context: android.content.Context? = null): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = MmkvManager.decodeSettingsString("pref_active_http_port")?.toIntOrNull()
            ?: SettingsManager.getHttpPort()
        val selectedType = MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it)?.configType }
        val nativeNetwork = if (selectedType in listOf(com.v2ray.ang.enums.EConfigType.OPENVPN, com.v2ray.ang.enums.EConfigType.OPENCONNECT, com.v2ray.ang.enums.EConfigType.IKEV2)) {
            val connectivity = context?.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
            val network = connectivity.activeNetwork ?: return null
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true) return null
            network
        } else null
        if (httpPort == 0 && nativeNetwork == null) return null
        val torRoute = MmkvManager.decodeSettingsString("pref_s_standalone") == "tor" ||
            MmkvManager.decodeSettingsBool("pref_tor_before", false) ||
            MmkvManager.decodeSettingsBool("pref_tor_after", false)
        val requestTimeout = if (torRoute) 15000 else 5000
        val traceUrl = "https://www.cloudflare.com/cdn-cgi/trace"
        val endpoints = (listOf(traceUrl, "https://api.ip.sb/geoip", url, "https://ipwho.is/") +
            if (torRoute) listOf("https://check.torproject.org/api/ip") else emptyList()).distinct()
        var fallbackIp: String? = null
        for (endpoint in endpoints) {
        val content = try { if (nativeNetwork != null) {
            val connection = nativeNetwork.openConnection(java.net.URL(endpoint), java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            try {
                connection.connectTimeout = requestTimeout
                connection.readTimeout = requestTimeout
                if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
            } finally { connection.disconnect() }
        } else HttpUtil.getUrlContent(
            UrlContentRequest(
                url = endpoint,
                timeout = requestTimeout,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) } catch (_: Exception) { null } ?: continue
        val ipInfo = (if (endpoint == traceUrl) IPAPIInfo.fromTrace(content)
            else JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java)) ?: continue

        val ip = ipInfo.resolveIp()
        val country = ipInfo.resolveCountryCode()

        if (!ip.isNullOrBlank() && com.v2ray.ang.util.Utils.isPureIpAddress(ip)) {
            fallbackIp = ip
        if (country?.matches(Regex("[A-Za-z]{2}")) == true) {
            return "(${country.uppercase()}) $ip"
        }
        }
        }
        return fallbackIp?.let { "(unknown) $it" }
    }
}
