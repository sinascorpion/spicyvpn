package com.v2ray.ang.tunnel

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.Executors

@Keep
class OpenVpnTunnelRunner(
    private val service: CoreVpnService,
    private val profile: ProfileItem,
    private val content: String,
    private val connected: () -> Unit,
    private val ended: (String) -> Unit
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val handleLock = Any()
    private var handle = 0L
    @Volatile private var stopped = false
    private var builder: VpnService.Builder? = null
    @Volatile private var lastError = ""
    private var previousTraffic = longArrayOf(0, 0)

    fun trafficDelta(): LongArray = synchronized(handleLock) {
        if (handle == 0L) return@synchronized longArrayOf(0, 0)
        val counters = OpenVpnNative.traffic(handle)
        val delta = LongArray(2) { (counters[it] - previousTraffic[it]).coerceAtLeast(0) }
        previousTraffic = counters
        delta
    }

    fun start() {
        require(content.isNotBlank()) { "Import a complete .ovpn profile first" }
        worker.execute {
            var failure = ""
            try {
                val native = synchronized(handleLock) {
                    if (stopped) return@execute
                    OpenVpnNative.create(this, content, profile.username.orEmpty(), profile.password.orEmpty())
                        .also { handle = it }
                }
                failure = OpenVpnNative.connect(native)
            } catch (e: Throwable) {
                failure = e.message ?: "OpenVPN failed"
            } finally {
                synchronized(handleLock) {
                    if (handle != 0L) OpenVpnNative.destroy(handle)
                    handle = 0
                }
                worker.shutdown()
                if (!stopped) main.post { if (!stopped) ended(failure.ifBlank { lastError }) }
            }
        }
    }

    fun stop() {
        stopped = true
        synchronized(handleLock) { if (handle != 0L) OpenVpnNative.stop(handle) }
    }

    fun onEvent(name: String, info: String, error: Boolean) {
        if (stopped) return
        if (error) { lastError = "$name: $info"; LogUtil.w(AppConfig.TAG, "OpenVPN $name: $info") }
        if (name == "CONNECTED") main.post { if (!stopped) connected() }
    }

    fun operation(op: Int, text: String, number: Int): Int = synchronized(handleLock) {
        if (stopped) return -1
        return try {
            when (op) {
                1 -> return if (service.protect(number)) 1 else -1
                2 -> builder = service.newOpenVpnBuilder()
                3 -> builder!!.addAddress(text, number)
                4 -> CountryVpnRoutes.addRoute(service, builder!!, text, number)
                5 -> if (Build.VERSION.SDK_INT >= 33) builder!!.excludeRoute(IpPrefix(java.net.InetAddress.getByName(text), number)) else return -1
                6 -> builder!!.addDnsServer(text)
                7 -> builder!!.addSearchDomain(text)
                8 -> builder!!.setMtu(number)
                9 -> return service.establishOpenVpn(builder!!)
                10 -> builder!!.allowFamily(number)
                else -> return -1
            }
            1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "OpenVPN interface operation $op failed", e)
            -1
        }
    }
}
