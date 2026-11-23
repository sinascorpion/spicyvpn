package com.example.spicyvpn.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.tunnel.TorBridgeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TorBridgeSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun defaults(value: String): String = runCatching {
        val key = if (value == "meek_lite") "meek" else value
        val list = org.json.JSONObject(context.assets.open("tor_pt_config.json").bufferedReader().use { it.readText() })
            .getJSONObject("bridges").optJSONArray(key)
        if (list == null) "" else (0 until list.length()).joinToString("\n") { list.getString(it) }
    }.getOrDefault("")
    var mode by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_tor_bridge_mode") ?: "none") }
    var lines by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_tor_bridge_lines").orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var latency by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val snowflakeOptions = remember { defaults("snowflake").lines().filter { it.isNotBlank() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tor settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box {
                TextButton(onClick = { menu = true }) { Text(if (mode == "meek_lite") "meek" else mode) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    TorBridgeConfig.modes.forEach { value ->
                        DropdownMenuItem(text = { Text(if (value == "meek_lite") "meek" else value) }, onClick = {
                            mode = value; lines = defaults(value); menu = false; error = null
                        })
                    }
                }
            }
            if (mode == "snowflake") {
                snowflakeOptions.forEachIndexed { index, bridge ->
                    Row(Modifier.fillMaxWidth()) {
                        RadioButton(selected = lines.trim() == bridge, onClick = { lines = bridge })
                        TextButton(onClick = { lines = bridge }, modifier = Modifier.weight(1f)) {
                            val result = latency[bridge]
                            Text("Snowflake ${index + 1}" + when {
                                result == null -> ""
                                result < 0 -> " - Unreachable"
                                else -> " - Broker TCP ${result}ms"
                            })
                        }
                    }
                }
                TextButton(enabled = !testing, onClick = {
                    testing = true
                    scope.launch {
                        try {
                            val results = withContext(Dispatchers.IO) {
                                snowflakeOptions.associateWith { bridge ->
                                    runCatching {
                                        val address = bridge.split(' ').first { it.startsWith("url=") }.removePrefix("url=")
                                        val uri = java.net.URI(address)
                                        val started = android.os.SystemClock.elapsedRealtime()
                                        java.net.Socket().use { socket ->
                                            socket.connect(java.net.InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 443), 5000)
                                        }
                                        android.os.SystemClock.elapsedRealtime() - started
                                    }.getOrDefault(-1L)
                                }
                            }
                            latency = results
                        } finally { testing = false }
                    }
                }) { Text(if (testing) "Testing..." else "Test broker reachability") }
            }
            OutlinedTextField(lines, { lines = it; error = null }, label = { Text("Custom bridges (optional)") },
                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
                isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val custom = lines.lines().map { it.trim().removePrefix("Bridge ") }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        if (custom.isNotEmpty()) {
            val transports = custom.map { it.substringBefore(' ') }.distinct()
            if (transports.size != 1 || transports.single() !in TorBridgeConfig.modes.drop(1)) {
                error = "Enter bridge lines for one supported transport"
                return@TextButton
            }
            mode = transports.single()
        }
        MmkvManager.encodeSettings("pref_tor_bridge_mode", mode)
        MmkvManager.encodeSettings("pref_tor_bridge_lines", lines.trim())
        onDismiss()
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
