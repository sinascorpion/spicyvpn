package com.v2ray.ang.tunnel

import java.io.File

object TorBridgeConfig {
    val modes = listOf("none", "obfs4", "meek_lite", "webtunnel", "snowflake", "conjure")

    fun build(mode: String, bridges: List<String>, nativeDir: File, upstream: Boolean): String {
        require(mode in modes) { "Unknown Tor bridge transport" }
        if (mode == "none") return "UseBridges 0"
        require(!(upstream && mode == "conjure")) { "Conjure does not support a SOCKS upstream chain" }
        val executable = File(nativeDir, if (mode == "conjure") "libconjure.so" else "liblyrebird.so")
        require(executable.isFile) { "Tor bridge binary is missing for this device" }
        val selected = bridges.map { it.trim().removePrefix("Bridge ") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        require(selected.isNotEmpty()) { "No bridge configured for $mode" }
        require(selected.all { it.startsWith("$mode ") && !it.contains('\n') && !it.contains('\r') }) {
            "Bridge lines must match the selected transport"
        }
        return buildString {
            appendLine("UseBridges 1")
            append("ClientTransportPlugin $mode exec ${executable.absolutePath}")
            if (mode == "conjure") append(" -registerURL https://registration.refraction.network")
            appendLine()
            selected.forEach { appendLine("Bridge $it") }
        }
    }
}
