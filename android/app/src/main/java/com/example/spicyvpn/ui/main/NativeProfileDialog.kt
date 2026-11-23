package com.example.spicyvpn.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils

@Composable
fun NativeProfileDialog(protocol: String, onDismiss: () -> Unit, onImport: (String, String) -> Boolean, onFile: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("0") }
    var resolvers by remember { mutableStateOf("1.1.1.1:53\n8.8.8.8:53") }
    var aetherProtocol by remember { mutableStateOf("gool") }
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val title = when (protocol) { "openconnect" -> "OpenConnect"; "masterdns" -> "MasterDNS"; "stormdns" -> "StormDNS"; else -> "Aether" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.config_name)) }, modifier = Modifier.fillMaxWidth())
            if (protocol == "aether") {
                listOf("gool" to "Gool (HTTP/2)", "h2" to "MASQUE HTTP/2", "h3" to "MASQUE HTTP/3", "wg" to "WireGuard", "wiw" to "WARP-in-WARP", "mim" to "MASQUE-in-MASQUE").forEach { (value, label) ->
                    Row { RadioButton(aetherProtocol == value, { aetherProtocol = value }); Text(label, Modifier.padding(top = 12.dp)) }
                }
            } else if (protocol == "openconnect") {
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.server_address)) }, placeholder = { Text("vpn.example.com:443/group") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.ssh_username)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password_optional)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(domain, { domain = it }, label = { Text(stringResource(R.string.dnstt_domain)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.native_dns_key)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(method, { method = it }, label = { Text(stringResource(R.string.native_dns_method)) }, isError = method.toIntOrNull() !in 0..5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(resolvers, { resolvers = it }, label = { Text(stringResource(R.string.native_dns_resolvers)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(raw, { raw = it; error = false }, label = { Text(stringResource(R.string.configuration_text)) }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { raw = clipboard.getText()?.text.orEmpty() }) { Text(stringResource(R.string.import_clipboard)) }
                TextButton(onClick = onFile) { Text(stringResource(R.string.import_file)) }
            }
            if (error) Text(stringResource(R.string.custom_config_invalid), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Button(onClick = {
            val content = runCatching {
                if (raw.isNotBlank()) raw.trim() else when (protocol) {
                    "aether" -> "aether://?protocol=$aetherProtocol&h2=1"
                    "openconnect" -> {
                        val address = host.trim().removePrefix("https://").removePrefix("openconnect://")
                        require(address.isNotBlank() && !address.contains('@'))
                        "openconnect://${Utils.encodeURIComponent(user)}:${Utils.encodeURIComponent(password)}@$address"
                    }
                    else -> com.v2ray.ang.fmt.NativeDnsFmt.manualPayload(domain.trim(), key.trim(), method.toInt(), resolvers.lines().map(String::trim).filter(String::isNotBlank)).toString()
                }
            }.getOrNull()
            error = content == null || !onImport(content, name.trim())
            if (!error) onDismiss()
        }) { Text(stringResource(R.string.save)) }
    }, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
