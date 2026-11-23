package com.example.spicyvpn.ads

object AdTrafficPolicy {
    fun canRequest(connected: Boolean, dnsOnly: Boolean): Boolean =
        connected && !dnsOnly
}
