package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CertSha256Request
import com.v2ray.ang.dto.CertSha256Result
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object CertificateFingerprintManager {
    private const val TIMEOUT_MS = 5000L

    fun fetchForManualFill(profile: ProfileItem): String? {
        val request = buildRequest(profile) ?: return null
        val result = if (profile.configType == EConfigType.HYSTERIA2) {
            fetch("quic", request) { com.v2ray.ang.core.XrayBindingCompat.certificateFingerprint("fetchQuicCertSha256", it) }
        } else {
            fetch("tls", request) { com.v2ray.ang.core.XrayBindingCompat.certificateFingerprint("fetchTlsCertSha256", it) }
                ?: fetchTlsCertificate(request)
        }

        return result
            ?.takeIf { it.error.isBlank() }
            ?.sha256
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildRequest(profile: ProfileItem): CertSha256Request? {
        if (!isFetchable(profile)) return null

        val server = profile.server?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it > 0 } ?: AppConfig.DEFAULT_PORT

        return CertSha256Request(
            address = resolveDialAddress(server),
            port = port,
            serverName = inferServerName(profile),
            timeoutMs = TIMEOUT_MS,
        )
    }

    private fun isFetchable(profile: ProfileItem): Boolean {
        return profile.configType == EConfigType.HYSTERIA2 || profile.security == AppConfig.TLS
    }

    private fun fetch(
        type: String,
        request: CertSha256Request,
        fetcher: (String) -> String,
    ): CertSha256Result? {
        return try {
            JsonUtil.fromJsonSafe(fetcher(JsonUtil.toJson(request)), CertSha256Result::class.java)
        } catch (e: UnsatisfiedLinkError) {
            LogUtil.e(AppConfig.TAG, "Fetch $type cert SHA-256 API missing in libv2ray", e)
            null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Fetch $type cert SHA-256 failed", e)
            null
        }
    }

    private fun resolveDialAddress(server: String): String {
        if (Utils.isPureIpAddress(server) || !Utils.isDomainName(server)) return server

        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6, false)
        return HttpUtil.resolveHostToIP(server, preferIpv6)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: server
    }

    private fun inferServerName(profile: ProfileItem): String? {
        val sni = profile.sni?.trim()?.takeIf { it.isNotBlank() }
            ?: profile.server?.trim()?.takeIf { it.isNotBlank() }
        return sni?.takeUnless { Utils.isPureIpAddress(it) }
    }

    internal fun fetchTlsCertificate(request: CertSha256Request): CertSha256Result? = try {
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        // This explicit discovery action reads an untrusted certificate to pin it.
        // It does not weaken verification for normal VPN connections.
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
        }
        context.init(null, arrayOf(trust), java.security.SecureRandom())
        java.net.Socket().use { tcp ->
            val timeout = request.timeoutMs.coerceIn(1000, 30000).toInt()
            tcp.connect(java.net.InetSocketAddress(request.address.removeSurrounding("[", "]"), request.port), timeout)
            val peer = request.serverName ?: request.address.removeSurrounding("[", "]")
            (context.socketFactory.createSocket(tcp, peer, request.port, true) as javax.net.ssl.SSLSocket).use { tls ->
                tls.soTimeout = timeout
                if (!request.serverName.isNullOrBlank()) {
                    tls.sslParameters = tls.sslParameters.apply {
                        serverNames = listOf(javax.net.ssl.SNIHostName(request.serverName))
                    }
                }
                tls.startHandshake()
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(tls.session.peerCertificates.first().encoded)
                CertSha256Result(sha256 = digest.joinToString("") { "%02x".format(it.toInt() and 255) })
            }
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Fetch TLS certificate directly failed", e)
        null
    }
}
