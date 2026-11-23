package com.v2ray.ang.tunnel

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService

/** BadVPN-compatible UDPGW over an SSH channel; never opens an Internet UDP socket. */
internal class SshUdpGateway(private val session: Session, private val host: String,
    private val port: Int, private val pool: ExecutorService) : AutoCloseable {
    private val udp = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
    private var channel: ChannelDirectTCPIP? = null
    @Volatile private var closed = false
    @Volatile private var peer: InetSocketAddress? = null
    val localPort: Int get() = udp.localPort

    fun start() {
        require(host.isNotBlank() && port in 1..65535)
        val ch = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
        channel = ch
        ch.setHost(host)
        ch.setPort(port)
        val input = DataInputStream(ch.inputStream)
        val output = ch.outputStream
        ch.connect(10000)
        udp.soTimeout = 1000
        pool.execute {
            try {
                while (!closed) {
                    val low = input.readUnsignedByte()
                    val size = low or (input.readUnsignedByte() shl 8)
                    require(size in 9..65535)
                    val frame = ByteArray(size)
                    input.readFully(frame)
                    val ipv6 = frame[0].toInt() and 8 != 0
                    val addressSize = if (ipv6) 16 else 4
                    require(size >= 5 + addressSize)
                    val packet = ByteArray(size - 3 + 4)
                    packet[3] = if (ipv6) 4 else 1
                    frame.copyInto(packet, 4, 3)
                    peer?.let { udp.send(DatagramPacket(packet, packet.size, it)) }
                }
            } catch (_: Exception) { } finally { close() }
        }
        pool.execute {
            val flows = LinkedHashMap<String, Int>()
            var lastWrite = System.nanoTime()
            try {
                val buffer = ByteArray(65535)
                while (!closed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { udp.receive(packet) } catch (_: SocketTimeoutException) {
                        if (System.nanoTime() - lastWrite > 15_000_000_000L) {
                            writeFrame(output, byteArrayOf(1, 0, 0))
                            lastWrite = System.nanoTime()
                        }
                        continue
                    }
                    if (!packet.address.isLoopbackAddress) continue
                    val source = InetSocketAddress(packet.address, packet.port)
                    if (peer != null && peer != source) continue
                    if (packet.length < 10 || buffer[0] != 0.toByte() || buffer[1] != 0.toByte() || buffer[2] != 0.toByte()) continue
                    val addressSize = when (buffer[3].toInt()) { 1 -> 4; 4 -> 16; else -> continue }
                    val offset = 4 + addressSize + 2
                    if (packet.length < offset || packet.length - offset > 65000) continue
                    peer = source
                    val key = buffer.copyOfRange(4, offset).joinToString(",")
                    var rebind = false
                    val id = flows[key] ?: run {
                        val newId = if (flows.size < 256) flows.size else {
                            val oldest = flows.entries.first()
                            flows.remove(oldest.key)
                            rebind = true
                            oldest.value
                        }
                        flows[key] = newId
                        newId
                    }
                    val frame = ByteArray(packet.length - 4 + 3)
                    frame[0] = ((if (addressSize == 16) 8 else 0) or (if (rebind) 2 else 0)).toByte()
                    frame[1] = id.toByte()
                    frame[2] = (id shr 8).toByte()
                    buffer.copyInto(frame, 3, 4, packet.length)
                    writeFrame(output, frame)
                    lastWrite = System.nanoTime()
                }
            } catch (_: Exception) { } finally { close() }
        }
    }

    private fun writeFrame(output: OutputStream, frame: ByteArray) {
        output.write(frame.size and 255)
        output.write(frame.size shr 8)
        output.write(frame)
        output.flush()
    }

    fun awaitControl(socket: Socket) {
        socket.soTimeout = 1000
        while (!closed && session.isConnected) {
            try { if (socket.getInputStream().read() < 0) break }
            catch (_: SocketTimeoutException) { }
        }
    }

    override fun close() {
        closed = true
        udp.close()
        channel?.disconnect()
    }
}
