package com.v2ray.ang.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

object NetworkTools {
    fun run(tool: String, target: String, port: Int = 443): String {
        val value = target.trim()
        require(value.isNotBlank()) { "Enter a target" }
        return when (tool) {
            "DNS lookup" -> InetAddress.getAllByName(value).joinToString("\n") { it.hostAddress.orEmpty() }
            "TCP test" -> {
                require(port in 1..65535) { "Invalid port" }
                val start = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(value, port), 5000) }
                "Connected: ${(System.nanoTime() - start) / 1000000} ms"
            }
            "HTTP test" -> {
                val url = URL(if (value.contains("://")) value else "https://$value")
                require(url.protocol in listOf("http", "https")) { "Use HTTP or HTTPS" }
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "HEAD"
                    val start = System.nanoTime()
                    val status = connection.responseCode
                    "HTTP $status\n${(System.nanoTime() - start) / 1000000} ms\n${connection.getHeaderField("Server").orEmpty()}"
                } finally { connection.disconnect() }
            }
            "Ping" -> {
                require(!value.startsWith("-") && value.none { it.isWhitespace() }) { "Invalid host" }
                val process = ProcessBuilder("/system/bin/ping", "-c", "3", "-W", "2", value).redirectErrorStream(true).start()
                try {
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "Ping timed out" }
                    process.inputStream.bufferedReader().use { it.readText().take(8192) }
                } finally { process.destroy() }
            }
            else -> error("Unknown network tool")
        }
    }
}
