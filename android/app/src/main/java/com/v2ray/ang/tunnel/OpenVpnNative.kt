package com.v2ray.ang.tunnel

import androidx.annotation.Keep

@Keep
object OpenVpnNative {
    init { System.loadLibrary("spicyopenvpn") }
    external fun version(): String
    external fun create(callback: OpenVpnTunnelRunner, content: String, user: String, password: String): Long
    external fun connect(handle: Long): String
    external fun stop(handle: Long)
    external fun destroy(handle: Long)
    external fun traffic(handle: Long): LongArray
}
