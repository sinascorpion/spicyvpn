package com.v2ray.ang.core

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object TlsProbe {
    fun latency(ip: String, port: Int, sni: String, timeout: Int): Long {
        require(port in 1..65535 && timeout in 500..15000)
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeout)
            val host = sni.ifBlank { ip }
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket).use { tls ->
                tls.soTimeout = timeout
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = if (sni.isBlank()) emptyList() else listOf(SNIHostName(sni))
                }
                tls.startHandshake()
            }
        }
        return (System.nanoTime() - started) / 1000000
    }
}
