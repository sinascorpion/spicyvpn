package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.util.JsonUtil
import java.net.Inet4Address
import java.net.NetworkInterface

object LanSharingConfig {
    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter {
            val name = it.name.lowercase()
            it.isUp && !it.isLoopback && !it.isPointToPoint &&
                listOf("ap", "wlan", "swlan", "rndis", "usb", "eth").any(name::startsWith)
        }.sortedBy { if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }.distinct()
    } catch (_: Exception) { emptyList() }

    fun addListeners(config: V2rayConfig, addresses: List<String>) {
        val local = config.inbounds.filter { it.protocol == "http" || it.protocol == "socks" }
        addresses.distinct().forEachIndexed { index, address ->
            require(isPrivateIpv4(address)) { "Sharing requires a private LAN address" }
            local.forEach { original ->
                val copy = requireNotNull(JsonUtil.fromJson(JsonUtil.toJson(original), V2rayConfig.InboundBean::class.java))
                copy.listen = address
                copy.tag = "lan-${original.tag}-$index"
                config.inbounds.add(copy)
            }
        }
    }

    internal fun isPrivateIpv4(address: String): Boolean {
        val octets = address.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
