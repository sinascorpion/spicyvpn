package com.v2ray.ang.tunnel

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import android.app.Service

/**
 * Singleton coordinator for background protocol tunnels (SSH and DNSTT).
 * Maintains active tunnels and provides local SOCKS5 proxy port mappings to Xray-core.
 */
object TunnelBridgeManager {

    @Volatile
    private var activeSshRunner: SshTunnelRunner? = null

    @Volatile
    private var activeDnsttRunner: DnsttTunnelRunner? = null

    @Volatile
    private var activeAetherRunner: AetherTunnelRunner? = null
    private var activeMihomoRunner: MihomoTunnelRunner? = null
    private var activeNativeDnsRunner: NativeDnsTunnelRunner? = null

    @Volatile
    private var activeBridgePort: Int = 0

    @Synchronized
    fun getBridgePort(): Int {
        return activeBridgePort
    }

    /**
     * Checks if this profile requires an independent background tunnel runner.
     */
    fun requiresTunnel(configType: EConfigType): Boolean {
        return configType in setOf(EConfigType.SSH, EConfigType.DNSTT, EConfigType.AETHER,
            EConfigType.MIHOMO, EConfigType.AMNEZIAWG, EConfigType.MASTERDNS, EConfigType.STORMDNS)
    }

    /**
     * Starts the appropriate tunnel runner for this profile.
     * Returns the local SOCKS5 port opened by the tunnel.
     */
    fun startTunnel(profile: ProfileItem, service: Service): Int {
        stopTunnel()

        when (profile.configType) {
            EConfigType.MASTERDNS, EConfigType.STORMDNS -> {
                val runner = NativeDnsTunnelRunner(profile, service)
                synchronized(this) { activeNativeDnsRunner = runner }
                val port = runner.start()
                synchronized(this) {
                    check(activeNativeDnsRunner === runner) { "DNS tunnel start was cancelled" }
                    activeBridgePort = port
                }
                return port
            }
            EConfigType.MIHOMO, EConfigType.AMNEZIAWG -> {
                val runner = MihomoTunnelRunner(profile, service)
                synchronized(this) { activeMihomoRunner = runner }
                val port = runner.start()
                synchronized(this) {
                    check(activeMihomoRunner === runner) { "Mihomo start was cancelled" }
                    activeBridgePort = port
                }
                return port
            }
            EConfigType.SSH -> {
                LogUtil.i(AppConfig.TAG, "TunnelBridge: Starting SSH tunnel runner...")
                val runner = SshTunnelRunner(profile)
                synchronized(this) { activeSshRunner = runner }
                val port = runner.start()
                synchronized(this) {
                    check(activeSshRunner === runner) { "SSH start was cancelled" }
                    activeBridgePort = port
                }
                return port
            }
            EConfigType.DNSTT -> {
                LogUtil.i(AppConfig.TAG, "TunnelBridge: Starting DNSTT tunnel runner...")
                val runner = DnsttTunnelRunner(profile)
                synchronized(this) { activeDnsttRunner = runner }
                val port = runner.start()
                synchronized(this) {
                    check(activeDnsttRunner === runner) { "DNSTT start was cancelled" }
                    activeBridgePort = port
                }
                return port
            }
            EConfigType.AETHER -> {
                LogUtil.i(AppConfig.TAG, "TunnelBridge: Starting Aether engine...")
                val runner = AetherTunnelRunner(service, profile)
                synchronized(this) { activeAetherRunner = runner }
                val port = runner.start()
                synchronized(this) {
                    check(activeAetherRunner === runner) { "Aether start was cancelled" }
                    activeBridgePort = port
                }
                return port
            }
            else -> {
                activeBridgePort = 0
                return 0
            }
        }
    }

    /**
     * Stops any currently running tunnel runner.
     */
    @Synchronized
    fun stopTunnel() {
        activeNativeDnsRunner?.stop()
        activeNativeDnsRunner = null
        activeMihomoRunner?.stop()
        activeMihomoRunner = null
        if (activeSshRunner != null) {
            LogUtil.i(AppConfig.TAG, "TunnelBridge: Stopping SSH tunnel runner...")
            try {
                activeSshRunner?.stop()
            } catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "TunnelBridge: Error stopping SSH runner: " + e.message)
            }
            activeSshRunner = null
        }
        if (activeDnsttRunner != null) {
            LogUtil.i(AppConfig.TAG, "TunnelBridge: Stopping DNSTT tunnel runner...")
            try {
                activeDnsttRunner?.stop()
            } catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "TunnelBridge: Error stopping DNSTT runner: " + e.message)
            }
            activeDnsttRunner = null
        }
        if (activeAetherRunner != null) {
            LogUtil.i(AppConfig.TAG, "TunnelBridge: Stopping Aether engine...")
            try {
                activeAetherRunner?.stop()
            } catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "TunnelBridge: Error stopping Aether: " + e.message)
            }
            activeAetherRunner = null
        }
        activeBridgePort = 0
    }
}
