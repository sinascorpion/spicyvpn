package com.v2ray.ang.tunnel

import java.io.DataInputStream
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Negotiate the upstream on the already VPN-protected socket, before optional TLS. */
object SshProxyHandshake {
    fun connect(socket: Socket, proxy: URI, host: String, port: Int) {
        require(proxy.scheme in setOf("socks5", "http"))
        require(!host.contains('\r') && !host.contains('\n'))
        socket.soTimeout = 10000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val credentials = proxy.rawUserInfo?.split(":", limit = 2)?.map {
            URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
        }
        if (proxy.scheme == "socks5") {
            output.write(if (credentials == null) byteArrayOf(5, 1, 0) else byteArrayOf(5, 2, 0, 2))
            output.flush()
            check(input.readUnsignedByte() == 5) { "Invalid SOCKS5 response" }
            when (input.readUnsignedByte()) {
                0 -> Unit
                2 -> {
                    check(credentials != null) { "SOCKS5 requires credentials" }
                    val user = credentials[0].toByteArray(Charsets.UTF_8)
                    val pass = credentials.getOrElse(1) { "" }.toByteArray(Charsets.UTF_8)
                    require(user.size in 1..255 && pass.size <= 255)
                    output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                    output.flush()
                    check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "SOCKS5 authentication failed" }
                }
                else -> error("SOCKS5 rejected authentication methods")
            }
            val address = host.toByteArray(Charsets.UTF_8)
            require(address.size in 1..255 && port in 1..65535)
            output.write(byteArrayOf(5, 1, 0, 3, address.size.toByte()) + address + byteArrayOf((port shr 8).toByte(), port.toByte()))
            output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "SOCKS5 connection failed" }
            check(input.readUnsignedByte() == 0)
            val length = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("Invalid SOCKS5 address") }
            input.readFully(ByteArray(length + 2))
        } else {
            val authority = (if (host.contains(':')) "[$host]" else host) + ":$port"
            val auth = credentials?.let {
                "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString((it[0] + ":" + it.getOrElse(1) { "" }).toByteArray(Charsets.UTF_8)) + "\r\n"
            }.orEmpty()
            output.write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n${auth}\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                require(header.length < 16384) { "HTTP proxy response too large" }
                val value = input.read()
                check(value >= 0) { "HTTP proxy closed the connection" }
                header.append(value.toChar())
            }
            check(header.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() in 200..299) { "HTTP CONNECT rejected" }
        }
        socket.soTimeout = 0
    }
}
