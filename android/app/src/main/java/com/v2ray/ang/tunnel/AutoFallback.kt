package com.v2ray.ang.tunnel

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object AutoFallback {
    const val SUBSCRIPTION = "spicyvpn-auto-fallback"
    @Volatile private var stopProbe: (() -> Unit)? = null
    private val lock = Mutex()

    private class ProbeService(context: Context) : Service() {
        init { attachBaseContext(context.applicationContext) }
        override fun onBind(intent: Intent?): IBinder? = null
    }

    fun cancel() { stopProbe?.invoke() }

    private fun internetWorks(port: Int): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        for (url in listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")) {
            val connection = URL(url).openConnection(proxy) as HttpsURLConnection
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.instanceFollowRedirects = false
                if (connection.responseCode == 204) return true
            } catch (_: Exception) {
            } finally { connection.disconnect() }
        }
        return false
    }

    suspend fun find(context: Context): ProfileItem? = lock.withLock { withContext(Dispatchers.IO) {
        for (cdn in listOf(false, true)) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val port = ServerSocket(0).use { it.localPort }
            val runner = PsiphonTunnelRunner(context.applicationContext, port, temporary = true, automaticCdn = cdn)
            stopProbe = runner::stop
            try {
                if (internetWorks(runner.start())) return@withContext ProfileItem.create(EConfigType.SOCKS).apply {
                    server = "127.0.0.1"
                    serverPort = "21808"
                    remarks = "Psiphon"
                    subscriptionId = SUBSCRIPTION
                    nativeConfig = if (cdn) "psiphon-cdn" else "psiphon"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) {
            } finally { runner.stop(); stopProbe = null }
        }
        for (transport in listOf("gool", "h2", "wg")) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val profile = ProfileItem.create(EConfigType.AETHER).apply {
                nativeConfig = "aether://?protocol=$transport&scan=balanced&ip=v4"
                remarks = "Aether / $transport"
                subscriptionId = SUBSCRIPTION
            }
            val runner = AetherTunnelRunner(ProbeService(context), profile)
            stopProbe = runner::stop
            try {
                if (internetWorks(runner.start())) return@withContext profile
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) {
            } finally { runner.stop(); stopProbe = null }
        }
        null
    } }
}
