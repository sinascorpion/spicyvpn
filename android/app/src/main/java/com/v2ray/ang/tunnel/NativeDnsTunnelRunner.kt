package com.v2ray.ang.tunnel

import android.app.Service
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class NativeDnsTunnelRunner(private val profile: ProfileItem, private val service: Service) {
    @Volatile private var stopped = false
    private var process: Process? = null
    private var directory: File? = null

    fun start(): Int {
        check(!stopped)
        val name = if (profile.configType == EConfigType.MASTERDNS) "masterdns" else "stormdns"
        val binary = File(service.applicationInfo.nativeLibraryDir, "libspicy$name.so")
        check(binary.isFile) { "$name core is missing for this device ABI" }
        val root = JsonParser.parseString(requireNotNull(profile.nativeConfig)).asJsonObject
        val config = root.getAsJsonObject("config").deepCopy()
        val port = ServerSocket(0).use { it.localPort }
        config.addProperty("LISTEN_IP", "127.0.0.1")
        config.addProperty("LISTEN_PORT", port)
        config.addProperty("PROTOCOL_TYPE", "SOCKS5")
        config.addProperty("SOCKS5_AUTH", false)
        config.addProperty("LOCAL_DNS_ENABLED", false)
        config.addProperty("STARTUP_MODE", "resolvers")
        val dir = File(service.cacheDir, "$name-session-${System.nanoTime()}").apply { mkdirs() }
        directory = dir
        val file = File(dir, "client_config.toml")
        file.writeText(config.entrySet().joinToString("\n") { (key, value) ->
            require(key.matches(Regex("[A-Z][A-Z0-9_]*"))) { "Invalid native DNS option name" }
            "$key = ${tomlValue(value)}"
        })
        val resolvers = File(dir, "client_resolvers.txt")
        resolvers.writeText(root.getAsJsonArray("resolvers").joinToString("\n") {
            val address = it.asString
            require(address.isNotBlank() && !address.contains('\n') && !address.contains('\r'))
            address
        })
        val child = ProcessBuilder(binary.absolutePath, "-config", file.absolutePath, "-resolvers", resolvers.absolutePath)
            .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "core.log")).start()
        process = child
        child.outputStream.close()
        try {
            repeat(300) {
                check(!stopped && child.isAlive) { "$name core stopped during startup" }
                if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess) {
                    monitorNativeChild(service, child, name) { stopped }
                    return port
                }
                Thread.sleep(100)
            }
            error("$name local proxy did not become ready")
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    private fun tomlValue(value: JsonElement): String {
        require(!value.isJsonNull && !value.isJsonObject) { "Native DNS options must be scalar values or arrays" }
        if (value.isJsonArray) return value.asJsonArray.joinToString(",", "[", "]") { tomlValue(it) }
        return value.toString()
    }

    fun stop() {
        stopped = true
        process?.let {
            it.destroy()
            if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                it.destroyForcibly()
                it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        process = null
        directory?.deleteRecursively()
        directory = null
    }
}
