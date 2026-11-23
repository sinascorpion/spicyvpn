package com.v2ray.ang.tunnel

import android.content.Context
import com.example.spicyvpn.ui.main.WarpRegistrationResult
import com.example.spicyvpn.ui.main.registerWarpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket

object WarpBootstrap {
    private val lock = Mutex()
    @Volatile private var active: PsiphonTunnelRunner? = null

    fun cancel() { active?.stop() }

    suspend fun force(context: Context): WarpRegistrationResult? = lock.withLock {
        withContext(Dispatchers.IO) {
            for (cdn in listOf(false, true)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val port = ServerSocket(0).use { it.localPort }
                val runner = PsiphonTunnelRunner(context.applicationContext, port, temporary = true, automaticCdn = cdn)
                active = runner
                try {
                    runner.start()
                    val result = registerWarpClient(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                    if (result != null) return@withContext result
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Try the built-in CDN scan only after the normal tunnel failed.
                } finally {
                    runner.stop()
                    active = null
                }
            }
            null
        }
    }
}
