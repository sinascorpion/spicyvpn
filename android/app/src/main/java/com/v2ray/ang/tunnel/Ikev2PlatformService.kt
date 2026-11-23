package com.v2ray.ang.tunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnManager
import android.net.VpnProfileState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.*

class Ikev2PlatformService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> MessageUtil.sendMsg2UI(this@Ikev2PlatformService,
                    if (Ikev2PlatformController.connected) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING, "")
                AppConfig.MSG_STATE_STOP -> stopSelf()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, android.content.IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(connected: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ikev2", "IKEv2 connection", NotificationManager.IMPORTANCE_LOW))
        val disconnect = PendingIntent.getService(this, 0, Intent(this, Ikev2PlatformService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, com.example.spicyvpn.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(22020, NotificationCompat.Builder(this, "ikev2").setSmallIcon(com.v2ray.ang.R.drawable.ic_stat_proxy)
            .setContentTitle("SpicyVPN · IKEv2").setContentText(if (connected) "Connected" else "Connecting…")
            .setContentIntent(open).setOngoing(true).addAction(0, "Disconnect", disconnect).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        if (Build.VERSION.SDK_INT < 30) { stopSelf(); return START_NOT_STICKY }
        notification(false)
        Ikev2PlatformController.active = true
        MmkvManager.encodeSettings("pref_active_http_port", "0")
        MmkvManager.encodeSettings("pref_ads_tunnel_route_ready", false)
        scope.launch {
            val manager = getSystemService(VpnManager::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 33) manager.startProvisionedVpnProfileSession() else manager.startProvisionedVpnProfile()
                val deadline = System.currentTimeMillis() + 90000
                while (isActive) {
                    val connectivity = getSystemService(ConnectivityManager::class.java)
                    val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                    val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    val state = if (Build.VERSION.SDK_INT >= 33) manager.provisionedVpnProfileState?.state else null
                    val ready = vpn && (state == null || state == VpnProfileState.STATE_CONNECTED)
                    if (ready && !Ikev2PlatformController.connected) {
                        Ikev2PlatformController.connected = true
                        notification(true)
                        MessageUtil.sendMsg2UI(this@Ikev2PlatformService, AppConfig.MSG_STATE_START_SUCCESS, "")
                    }
                    if (state == VpnProfileState.STATE_FAILED || (!Ikev2PlatformController.connected && System.currentTimeMillis() > deadline))
                        error("IKEv2 connection failed; check credentials, server certificate and UDP 500/4500 access")
                    if (Ikev2PlatformController.connected && !ready) error("IKEv2 connection was lost")
                    delay(1000)
                }
            } catch (error: Exception) {
                if (error !is CancellationException) MessageUtil.sendMsg2UI(this@Ikev2PlatformService, AppConfig.MSG_STATE_START_FAILURE, error.message.orEmpty())
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        unregisterReceiver(receiver)
        scope.cancel()
        if (Build.VERSION.SDK_INT >= 30) runCatching { getSystemService(VpnManager::class.java).stopProvisionedVpnProfile() }
        Ikev2PlatformController.active = false; Ikev2PlatformController.connected = false
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        super.onDestroy()
    }
}
