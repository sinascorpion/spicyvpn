package com.v2ray.ang.tunnel

import android.util.Log
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class SshTunnelRunnerIntegrationTest {
    @Test fun sshTransfersDataAndCloses() = exercise(false)
    @Test fun sshTlsTransfersDataAndCloses() = exercise(true)

    private fun exercise(tls: Boolean) {
        val keyFile = File("../../.tmp/protocol-test/client_key")
        // The fixture is intentionally local-only and never needs production credentials.
        assumeTrue("Local SSH integration fixture is not enabled", System.getenv("SPICY_SSH_TEST") == "true" && keyFile.isFile)
        val certificate = File("../../.tmp/protocol-test/ssh-tls-cert.pem")
        val ssl = if (tls) {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                certificate.inputStream().use {
                    setCertificateEntry("local-test", CertificateFactory.getInstance("X.509").generateCertificate(it))
                }
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
        } else null
        mockStatic(Log::class.java).use {
            val logLevel = com.v2ray.ang.util.LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
            val previousLogLevel = logLevel.getInt(null)
            logLevel.setInt(null, Int.MAX_VALUE)
            val profile = ProfileItem(configType = EConfigType.SSH, server = System.getenv("SPICY_SSH_TEST_HOST") ?: "127.0.0.1",
                serverPort = if (tls) "2443" else "2222", username = "root",
                sshPrivateKey = keyFile.readText(), security = if (tls) "tls" else null, sni = "localhost")
            val runner = SshTunnelRunner(profile, socketProtector = { true }, tlsFactory = ssl)
            val echo = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
            val remoteEchoPort = System.getenv("SPICY_TEST_ECHO_PORT")?.toIntOrNull()
            val echoWorker = if (remoteEchoPort == null) Thread {
                echo.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    val bytes = ByteArray(payload.size)
                    input.readFully(bytes)
                    client.getOutputStream().write(bytes)
                }
            }.apply { start() } else null
            try {
                val port = runner.start()
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 15000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0))
                    assertEquals(5, input.readUnsignedByte())
                    assertEquals(0, input.readUnsignedByte())
                    val wslHost = System.getenv("SPICY_TEST_ECHO_HOST") ?: "127.0.0.1"
                    val address = wslHost.toByteArray()
                    output.write(byteArrayOf(5, 1, 0, 3, address.size.toByte()))
                    output.write(address)
                    val targetPort = remoteEchoPort ?: echo.localPort
                    output.write(byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
                    val reply = ByteArray(10)
                    input.readFully(reply)
                    assertEquals("SSH direct-tcpip failed", 0, reply[1].toInt())
                    output.write(payload)
                    output.flush()
                    val response = ByteArray(payload.size)
                    input.readFully(response)
                    assertArrayEquals(payload, response)
                }
            } finally {
                runner.stop()
                echo.close()
                echoWorker?.join(2000)
                logLevel.setInt(null, previousLogLevel)
            }
            assertFalse(runner.isConnected())
        }
    }
}
