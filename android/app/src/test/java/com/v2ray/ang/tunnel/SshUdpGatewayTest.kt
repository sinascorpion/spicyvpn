package com.v2ray.ang.tunnel

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class SshUdpGatewayTest {
    @Test fun transfersFramedDatagramAndCloses() {
        val incoming = PipedInputStream()
        val remote = PipedOutputStream(incoming)
        val sent = ByteArrayOutputStream()
        val session = mock(Session::class.java)
        val channel = mock(ChannelDirectTCPIP::class.java)
        `when`(session.openChannel("direct-tcpip")).thenReturn(channel)
        `when`(channel.inputStream).thenReturn(incoming)
        `when`(channel.outputStream).thenReturn(sent)
        val pool = Executors.newCachedThreadPool()
        val gateway = SshUdpGateway(session, "127.0.0.1", 7300, pool)
        try {
            gateway.start()
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { client ->
                client.soTimeout = 3000
                val socks = byteArrayOf(0, 0, 0, 1, 1, 2, 3, 4, 0x12, 0x34, 42, 43)
                client.send(DatagramPacket(socks, socks.size, InetAddress.getLoopbackAddress(), gateway.localPort))
                val deadline = System.nanoTime() + 3_000_000_000L
                while (sent.size() < 13 && System.nanoTime() < deadline) Thread.sleep(10)
                assertArrayEquals(byteArrayOf(11, 0, 0, 0, 0, 1, 2, 3, 4, 0x12, 0x34, 42, 43), sent.toByteArray())
                remote.write(sent.toByteArray())
                remote.flush()
                val response = DatagramPacket(ByteArray(100), 100)
                client.receive(response)
                assertArrayEquals(socks, response.data.copyOf(response.length))
            }
            gateway.close()
            verify(channel, atLeastOnce()).disconnect()
        } finally {
            gateway.close()
            remote.close()
            incoming.close()
            pool.shutdownNow()
        }
    }
}
