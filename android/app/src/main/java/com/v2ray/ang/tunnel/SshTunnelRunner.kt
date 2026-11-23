package com.v2ray.ang.tunnel

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Executes an independent SSH client session and runs a lightweight local SOCKS5 proxy
 * listener that forwards TCP connections over SSH direct-tcpip channels.
 * Outgoing SSH sockets are protected via VpnService so they bypass the local VPN TUN.
 */
class SshTunnelRunner(
    private val profile: ProfileItem,
    private val socketProtector: ((Socket) -> Boolean)? = null,
    private val tlsFactory: SSLSocketFactory? = null
) {

    private var session: Session? = null
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val udpGateways = java.util.concurrent.ConcurrentHashMap.newKeySet<SshUdpGateway>()
    @Volatile private var stopped = false
    @Volatile private var upstreamSocket: Socket? = null
    var localPort: Int = 0
        private set

    fun start(): Int {
        val isDnstt = profile.configType == com.v2ray.ang.enums.EConfigType.DNSTT
        // For DNSTT: if SSH Host (stored in profile.sni) is specified, connect to that SSH host.
        // Otherwise, if profile.server is an IP/host, use it. Never use profile.host (which is the tunnel domain).
        val remoteHost = if (isDnstt) {
            (if (!profile.sni.isNullOrBlank()) profile.sni else profile.server).orEmpty().trim()
        } else {
            profile.server.orEmpty().trim()
        }
        val remotePort = (if (isDnstt && !profile.seed.isNullOrBlank()) profile.seed?.toIntOrNull() else profile.serverPort?.toIntOrNull()) ?: 22
        val username = profile.username.orEmpty().trim()
        val password = profile.password.orEmpty()
        val enableTls = !isDnstt && profile.security == "tls"
        val sniHost = if (!isDnstt) (profile.sni?.takeIf { it.isNotBlank() } ?: remoteHost) else remoteHost

        LogUtil.i(AppConfig.TAG, "SshTunnel: Connecting to " + remoteHost + ":" + remotePort + " (user: " + username + ", TLS: " + enableTls + ", isDnstt: " + isDnstt + ")")

        val jsch = JSch()
        profile.sshPrivateKey?.takeIf { it.isNotBlank() }?.let { privateKey ->
            val passphrase = profile.sshKeyPassphrase?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("spicyvpn-imported-key", privateKey.toByteArray(Charsets.UTF_8), null, passphrase)
        }
        val jschSession = jsch.getSession(username, remoteHost, remotePort)
        if (password.isNotEmpty()) {
            jschSession.setPassword(password)
        }

        val config = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "publickey,password,keyboard-interactive")
            put("ServerAliveInterval", "15")
            put("ServerAliveCountMax", "3")
        }
        jschSession.setConfig(config)
        jschSession.serverAliveInterval = 15000
        jschSession.serverAliveCountMax = 3

        // Custom SocketFactory to protect the socket with VpnService and optionally wrap with TLS
        jschSession.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val sock = Socket()
                upstreamSocket = sock
                if (stopped) { sock.close(); error("SSH start was cancelled") }
                if (socketProtector != null || SettingsManager.isVpnMode()) {
                    val protected = try {
                        socketProtector?.invoke(sock) ?: (CoreServiceManager.serviceControl?.get()?.vpnProtect(sock) == true)
                    } catch (e: Throwable) {
                        LogUtil.e(AppConfig.TAG, "SshTunnel: Failed to protect upstream socket", e)
                        false
                    }
                    if (!protected) {
                        sock.close()
                        throw java.io.IOException("Could not protect SSH socket from VPN routing loop")
                    }
                }

                try {
                val proxy = profile.sshUpstreamProxy?.takeIf { it.isNotBlank() }?.let(java.net.URI::create)
                sock.connect(InetSocketAddress(proxy?.host ?: host, proxy?.port ?: port), 10000)
                if (proxy != null) SshProxyHandshake.connect(sock, proxy, host, port)
                sock.soTimeout = 0
                sock.tcpNoDelay = true

                if (enableTls) {
                    val sslFactory = tlsFactory ?: (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    val sslSocket = sslFactory.createSocket(sock, sniHost, port, true) as SSLSocket
                    try {
                        val params = SSLParameters()
                        if (!Utils.isPureIpAddress(sniHost)) {
                            params.serverNames = listOf(SNIHostName(sniHost))
                        }
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        sslSocket.sslParameters = params
                    } catch (e: Throwable) {
                        sslSocket.close()
                        throw e
                    }
                    sslSocket.soTimeout = 10000
                    sslSocket.startHandshake()
                    sslSocket.soTimeout = 0
                    return sslSocket
                }

                return sock
                } catch (e: Throwable) { sock.close(); throw e }
            }

            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })

        session = jschSession
        try {
            jschSession.connect(15000)
            check(!stopped) { "SSH start was cancelled" }
        } catch (t: Throwable) {
            jschSession.disconnect()
            threadPool.shutdownNow()
            throw t
        }
        session = jschSession

        // Start local SOCKS5 server listening on 127.0.0.1
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        localPort = ss.localPort

        threadPool.execute {
            acceptClients(ss, jschSession)
        }

        LogUtil.i(AppConfig.TAG, "SshTunnel: SOCKS5 bridge listening on 127.0.0.1:" + localPort)
        return localPort
    }

    private fun acceptClients(ss: ServerSocket, sshSession: Session) {
        while (!ss.isClosed && sshSession.isConnected) {
            try {
                val clientSock = ss.accept()
                clients.add(clientSock)
                threadPool.execute {
                    handleSocksClient(clientSock, sshSession)
                }
            } catch (_: Throwable) {
                break
            }
        }
    }

    /**
     * Minimal SOCKS5 handshake & direct-tcpip relay.
     */
    private fun handleSocksClient(clientSock: Socket, sshSession: Session) {
        var channel: ChannelDirectTCPIP? = null
        try {
            clientSock.tcpNoDelay = true
            val clientIn = DataInputStream(clientSock.getInputStream())
            val clientOut = DataOutputStream(clientSock.getOutputStream())

            // SOCKS Greeting
            val ver = clientIn.readUnsignedByte()
            if (ver != 5) {
                clientSock.close()
                return
            }
            val nMethods = clientIn.readUnsignedByte()
            val methods = ByteArray(nMethods)
            clientIn.readFully(methods)
            if (methods.none { it == 0.toByte() }) {
                clientOut.write(byteArrayOf(5, -1))
                clientOut.flush()
                return
            }
            // Respond: NO AUTH (0x00)
            clientOut.writeByte(5)
            clientOut.writeByte(0)
            clientOut.flush()

            // SOCKS Request
            val reqVer = clientIn.readUnsignedByte()
            val cmd = clientIn.readUnsignedByte()
            val rsv = clientIn.readUnsignedByte()
            val atyp = clientIn.readUnsignedByte()

            if (reqVer != 5 || (cmd != 1 && cmd != 3) || rsv != 0 ||
                (cmd == 3 && profile.sshUdpGatewayHost.isNullOrBlank())) {
                clientOut.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)) // Command not supported
                clientOut.flush()
                clientSock.close()
                return
            }

            val targetHost = when (atyp) {
                1 -> { // IPv4
                    val ip = ByteArray(4)
                    clientIn.readFully(ip)
                    InetAddress.getByAddress(ip).hostAddress
                }
                3 -> { // Domain name
                    val len = clientIn.readUnsignedByte()
                    val domainBytes = ByteArray(len)
                    clientIn.readFully(domainBytes)
                    String(domainBytes, Charsets.UTF_8)
                }
                4 -> { // IPv6
                    val ip = ByteArray(16)
                    clientIn.readFully(ip)
                    InetAddress.getByAddress(ip).hostAddress
                }
                else -> {
                    clientSock.close()
                    return
                }
            }
            val targetPort = clientIn.readUnsignedShort()
            if (cmd == 3) {
                val gateway = SshUdpGateway(sshSession, profile.sshUdpGatewayHost.orEmpty(),
                    profile.sshUdpGatewayPort ?: 7300, threadPool)
                udpGateways.add(gateway)
                try {
                    check(!stopped)
                    gateway.start()
                    check(!stopped)
                    clientOut.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1))
                    clientOut.writeShort(gateway.localPort)
                    clientOut.flush()
                    gateway.awaitControl(clientSock)
                } finally {
                    gateway.close()
                    udpGateways.remove(gateway)
                }
                return
            }

            // Open SSH Direct-TCPIP Channel
            val directChannel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel = directChannel
            directChannel.setHost(targetHost)
            directChannel.setPort(targetPort)
            val channelIn = directChannel.inputStream
            val channelOut = directChannel.outputStream
            try {
                directChannel.connect(10000)
            } catch (ce: Throwable) {
                LogUtil.w(AppConfig.TAG, "SshTunnel: direct-tcpip connection to $targetHost:$targetPort failed: ${ce.message}")
                clientOut.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)) // Connection refused / host unreachable
                clientOut.flush()
                clientSock.close()
                return
            }
            channel = directChannel

            // SOCKS Response: Success (0x00)
            clientOut.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            clientOut.flush()

            val rawClientIn = clientSock.getInputStream()
            val rawClientOut = clientSock.getOutputStream()

            val latch = java.util.concurrent.CountDownLatch(2)

            threadPool.execute {
                try {
                    // JSch buffers the final packet until flush; waiting for EOF stalls HTTP requests.
                    val buffer = ByteArray(16384)
                    while (true) {
                        val count = rawClientIn.read(buffer)
                        if (count < 0) break
                        channelOut.write(buffer, 0, count)
                        channelOut.flush()
                    }
                } catch (_: Throwable) {
                } finally {
                    try { channelOut.close() } catch (_: Throwable) {}
                    latch.countDown()
                }
            }

            threadPool.execute {
                try {
                    channelIn.copyTo(rawClientOut, 16384)
                    rawClientOut.flush()
                } catch (_: Throwable) {
                } finally {
                    try { clientSock.shutdownOutput() } catch (_: Throwable) {}
                    latch.countDown()
                }
            }

            latch.await()
        } catch (t: Throwable) {
            LogUtil.w(AppConfig.TAG, "SshTunnel: Client handle error: ${t.message}")
        } finally {
            try { channel?.disconnect() } catch (_: Throwable) {}
            try { clientSock.close() } catch (_: Throwable) {}
            clients.remove(clientSock)
        }
    }

    fun isConnected(): Boolean {
        return session?.isConnected == true
    }

    fun stop() {
        stopped = true
        udpGateways.forEach { it.close() }
        udpGateways.clear()
        try { upstreamSocket?.close() } catch (_: Exception) {}
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null

        try {
            session?.disconnect()
        } catch (_: Throwable) {}
        session = null

        threadPool.shutdownNow()

        localPort = 0
        LogUtil.i(AppConfig.TAG, "SshTunnel: Stopped")
    }
}
