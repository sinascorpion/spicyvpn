package com.v2ray.ang.tunnel

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SshProxyHandshakeTest {
    private fun roundTrip(type: String, authenticated: Boolean) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    if (type == "socks5") {
                        assertEquals(5, input.readUnsignedByte())
                        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        assertTrue(methods.contains(if (authenticated) 2.toByte() else 0.toByte()))
                        output.write(byteArrayOf(5, if (authenticated) 2 else 0)); output.flush()
                        if (authenticated) {
                            assertEquals(1, input.readUnsignedByte())
                            val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
                            val pass = ByteArray(input.readUnsignedByte()).also(input::readFully)
                            assertEquals("user+name", String(user))
                            assertEquals("p:ass", String(pass))
                            output.write(byteArrayOf(1, 0)); output.flush()
                        }
                        assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte()); assertEquals(3, input.readUnsignedByte())
                        val host = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        assertEquals("ssh.example.com", String(host)); assertEquals(443, input.readUnsignedShort())
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 22)); output.flush()
                    } else {
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) header.append(input.readUnsignedByte().toChar())
                        assertTrue(header.startsWith("CONNECT ssh.example.com:443 HTTP/1.1"))
                        if (authenticated) assertTrue(header.contains("Proxy-Authorization: Basic dXNlcituYW1lOnA6YXNz"))
                        else assertFalse(header.contains("Proxy-Authorization"))
                        // Bytes after the header must remain available to SSH/TLS.
                        output.write("HTTP/1.1 200 Connection established\r\n\r\nSSH".toByteArray()); output.flush()
                    }
                    assertEquals(42, input.readUnsignedByte())
                    output.write(43); output.flush()
                }
            }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val auth = if (authenticated) "user%2Bname:p%3Aass@" else ""
                SshProxyHandshake.connect(socket, URI("$type://$auth" + "127.0.0.1:${server.localPort}"), "ssh.example.com", 443)
                if (type == "http") assertEquals("SSH", String(ByteArray(3).also { DataInputStream(socket.getInputStream()).readFully(it) }))
                socket.getOutputStream().write(42)
                assertEquals(43, socket.getInputStream().read())
            }
            result.get(5, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow() }
    }
    @Test fun socks5WithoutAuthentication() = roundTrip("socks5", false)
    @Test fun socks5WithAuthentication() = roundTrip("socks5", true)
    @Test fun httpWithoutAuthentication() = roundTrip("http", false)
    @Test fun httpWithAuthentication() = roundTrip("http", true)
}
