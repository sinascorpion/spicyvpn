package com.v2ray.ang.tunnel

import android.net.IpPrefix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.service.CoreVpnService
import org.infradead.libopenconnect.LibOpenConnect
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class OpenConnectTunnelRunner(
    private val service: CoreVpnService,
    private val profile: ProfileItem,
    private val connected: () -> Unit,
    private val ended: (String) -> Unit
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var stopped = false
    @Volatile private var native: LibOpenConnect? = null
    private var totals = longArrayOf(0, 0)
    private var previous = longArrayOf(0, 0)
    private var authAttempts = 0
    private var caFile: File? = null
    private var tunDescriptor: ParcelFileDescriptor? = null

    fun trafficDelta(): LongArray {
        return synchronized(lock) {
            native?.requestStats()
            LongArray(2) { (totals[it] - previous[it]).coerceAtLeast(0) }.also { previous = totals.copyOf() }
        }
    }

    fun start() {
        worker.execute {
            var failure = ""
            try {
                System.loadLibrary("openconnect")
                val client = object : LibOpenConnect("SpicyVPN") {
                    override fun onProgress(level: Int, msg: String) {
                        // Authentication responses may contain secrets; do not log native messages.
                    }

                    override fun onProtectSocket(fd: Int) {
                        check(service.protect(fd)) { "Could not protect OpenConnect socket" }
                    }

                    override fun onValidatePeerCert(msg: String): Int {
                        val pin = profile.pinSHA256
                        return if (!pin.isNullOrBlank() && checkPeerCertHash(pin) == 0) 0 else -1
                    }

                    override fun onProcessAuthForm(form: AuthForm): Int {
                        if (stopped || ++authAttempts > 3) return OC_FORM_RESULT_CANCELLED
                        if (!form.error.isNullOrBlank()) return OC_FORM_RESULT_ERR
                        for (option in form.opts) {
                            if (option.flags and OC_FORM_OPT_IGNORE.toLong() != 0L) continue
                            when (option.type) {
                                OC_FORM_OPT_HIDDEN -> Unit
                                OC_FORM_OPT_TEXT -> option.value = profile.username.orEmpty()
                                OC_FORM_OPT_PASSWORD -> option.value = profile.password.orEmpty()
                                OC_FORM_OPT_SELECT -> {
                                    val choice = option.choices.firstOrNull {
                                        it.name == profile.serviceName || it.label == profile.serviceName
                                    } ?: option.choices.firstOrNull()
                                    option.value = choice?.name ?: return OC_FORM_RESULT_ERR
                                }
                                else -> return OC_FORM_RESULT_ERR
                            }
                        }
                        return OC_FORM_RESULT_OK
                    }

                    override fun onStatsUpdate(stats: VPNStats) {
                        synchronized(lock) { totals = longArrayOf(stats.rxBytes, stats.txBytes) }
                    }

                    override fun onSetupTun() {
                        if (stopped) return
                        val info = ipInfo ?: error("OpenConnect supplied no IP configuration")
                        val builder = service.newOpenVpnBuilder().setSession("SpicyVPN OpenConnect")
                        if (!info.addr.isNullOrBlank()) builder.addAddress(info.addr, prefix(info.netmask))
                        if (!info.addr6.isNullOrBlank()) {
                            val address = info.addr6.substringBefore('/')
                            val bits = info.addr6.substringAfter('/', "").toIntOrNull()
                                ?: info.netmask6?.substringAfter('/')?.toIntOrNull() ?: 128
                            builder.addAddress(address, bits)
                        }
                        builder.setMtu((if (info.MTU > 0) info.MTU else 1500).coerceIn(576, 9000))
                        info.DNS.filter { it.isNotBlank() }.forEach { builder.addDnsServer(it) }
                        if (!info.domain.isNullOrBlank()) builder.addSearchDomain(info.domain)
                        if (info.splitIncludes.isEmpty()) {
                            if (!info.addr.isNullOrBlank()) CountryVpnRoutes.addRoute(service, builder, "0.0.0.0", 0)
                            if (!info.addr6.isNullOrBlank()) CountryVpnRoutes.addRoute(service, builder, "::", 0)
                        } else info.splitIncludes.forEach {
                            CountryVpnRoutes.addRoute(service, builder, it.substringBefore('/'), prefix(it.substringAfter('/')))
                        }
                        if (Build.VERSION.SDK_INT >= 33) info.splitExcludes.forEach {
                            builder.excludeRoute(IpPrefix(InetAddress.getByName(it.substringBefore('/')),
                                prefix(it.substringAfter('/'))))
                        }
                        // setupTunFD borrows the descriptor when no vpnc script is configured.
                        tunDescriptor?.close()
                        val descriptor = ParcelFileDescriptor.adoptFd(service.establishOpenVpn(builder))
                        tunDescriptor = descriptor
                        check(setupTunFD(descriptor.fd) == 0) { "OpenConnect TUN setup failed" }
                    }
                }
                synchronized(lock) {
                    if (stopped) { client.destroy(); return@execute }
                    native = client
                }
                client.setLogLevel(LibOpenConnect.PRG_ERR)
                check(client.setProtocol(profile.method ?: "anyconnect") == 0) { "Unsupported OpenConnect protocol" }
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(null as KeyStore?)
                }
                val pem = buildString {
                    trust.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
                        .forEach { certificate ->
                            append("-----BEGIN CERTIFICATE-----\n")
                            append(Base64.encodeToString(certificate.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
                            append("\n-----END CERTIFICATE-----\n")
                        }
                    profile.nativeConfig?.takeIf { it.isNotBlank() }?.let {
                        append(Base64.decode(it, Base64.DEFAULT).toString(Charsets.US_ASCII))
                    }
                }
                require(pem.isNotBlank()) { "No trusted OpenConnect certificate authorities" }
                caFile = File.createTempFile("openconnect-ca-", ".pem", service.cacheDir).also {
                    it.writeText(pem); client.setCAFile(it.absolutePath)
                }
                client.setSystemTrust(false)
                profile.sni?.takeIf { it.isNotBlank() }?.let { client.setSNI(it) }
                val host = profile.server.orEmpty().let { if (it.contains(':')) "[$it]" else it }
                val url = "https://$host:${profile.serverPort ?: "443"}${profile.path ?: "/"}"
                check(client.parseURL(url) == 0) { "Invalid OpenConnect endpoint" }
                check(client.obtainCookie() == 0) { "OpenConnect authentication or certificate validation failed" }
                check(client.makeCSTPConnection() == 0) { "OpenConnect CSTP negotiation failed" }
                client.onSetupTun()
                client.setupDTLS(60)
                main.post { if (!stopped) connected() }
                val result = client.mainloop(300, LibOpenConnect.RECONNECT_INTERVAL_MIN)
                if (!stopped && result != 0) failure = "OpenConnect tunnel ended ($result)"
            } catch (e: Throwable) {
                failure = e.message ?: "OpenConnect failed"
            } finally {
                synchronized(lock) { native?.destroy(); native = null }
                tunDescriptor?.close()
                tunDescriptor = null
                caFile?.delete()
                worker.shutdown()
                if (!stopped) main.post { if (!stopped) ended(failure) }
            }
        }
    }

    fun stop() {
        stopped = true
        synchronized(lock) { native?.cancel() }
    }

    companion object {
        internal fun prefix(mask: String?): Int {
            mask?.toIntOrNull()?.let { return it }
            val bytes = InetAddress.getByName(requireNotNull(mask)).address
            var bits = 0
            var zero = false
            for (byte in bytes) for (shift in 7 downTo 0) {
                val set = byte.toInt() and (1 shl shift) != 0
                require(!zero || !set) { "Non-contiguous network mask" }
                if (set) bits++ else zero = true
            }
            return bits
        }
    }
}
