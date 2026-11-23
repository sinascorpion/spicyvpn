package com.v2ray.ang.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

object DnsProbe {
    fun latency(address: String): Int {
        val started = System.nanoTime()
        val id = if (address.trim().startsWith("https", true)) 0 else Random.nextInt(65536)
        val query = byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        val value = address.trim().replace("https+local://", "https://")
        val response = when {
            value.startsWith("https://", true) -> {
                val connection = URL(value).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 2500
                    connection.readTimeout = 2500
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Content-Type", "application/dns-message")
                    connection.setRequestProperty("Accept", "application/dns-message")
                    connection.outputStream.use { it.write(query) }
                    check(connection.responseCode == 200) { "DNS HTTP request failed" }
                    check(connection.contentType.orEmpty().startsWith("application/dns-message", true)) { "Invalid DoH content type" }
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(65536)
                        var size = 0
                        while (size < buffer.size) {
                            val read = input.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        check(size < buffer.size) { "DNS response is too large" }
                        buffer.copyOf(size)
                    }
                } finally { connection.disconnect() }
            }
            value.startsWith("tls://", true) || value.startsWith("tcp://", true) -> {
                val uri = URI(value)
                val host = requireNotNull(uri.host).removeSurrounding("[", "]")
                val tls = uri.scheme.equals("tls", true)
                val port = if (uri.port > 0) uri.port else if (tls) 853 else 53
                val socket = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
                socket.use {
                    it.soTimeout = 2500
                    it.connect(InetSocketAddress(host, port), 2500)
                    if (it is SSLSocket) {
                        it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        it.startHandshake()
                    }
                    val output = DataOutputStream(it.getOutputStream())
                    output.writeShort(query.size)
                    output.write(query)
                    output.flush()
                    val input = DataInputStream(it.getInputStream())
                    ByteArray(input.readUnsignedShort()).also(input::readFully)
                }
            }
            else -> {
                require(!value.contains("://") || value.startsWith("udp://", true)) { "Unsupported DNS probe transport" }
                val uri = if (value.startsWith("udp://", true)) URI(value) else null
                val host = uri?.host ?: value.removeSurrounding("[", "]")
                val port = uri?.port?.takeIf { it > 0 } ?: 53
                DatagramSocket().use {
                    it.soTimeout = 2500
                    it.connect(InetAddress.getByName(host), port)
                    it.send(DatagramPacket(query, query.size))
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    it.receive(packet)
                    packet.data.copyOf(packet.length)
                }
            }
        }
        check(isSuccessfulResponse(response, id)) { "Invalid DNS response" }
        return ((System.nanoTime() - started) / 1_000_000).toInt()
    }

    internal fun isSuccessfulResponse(data: ByteArray, id: Int): Boolean = data.size >= 12 &&
        ((data[0].toInt() and 255) shl 8 or (data[1].toInt() and 255)) == id &&
        (data[2].toInt() and 128) != 0 && (data[3].toInt() and 15) == 0 &&
        ((data[6].toInt() and 255) shl 8 or (data[7].toInt() and 255)) > 0
}
