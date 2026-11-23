package com.example.spicyvpn.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v2ray.ang.core.NetworkTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NetworkToolsDialog(onDismiss: () -> Unit) {
    var target by remember { mutableStateOf("example.com") }
    var port by remember { mutableStateOf("443") }
    var tool by remember { mutableStateOf("DNS lookup") }
    var menu by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Network tools") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !running) { Text(tool) }
                DropdownMenu(menu, { menu = false }) {
                    listOf("DNS lookup", "TCP test", "HTTP test", "Ping").forEach { item ->
                        DropdownMenuItem(text = { Text(item) }, onClick = { tool = item; menu = false })
                    }
                }
            }
            OutlinedTextField(target, { target = it }, label = { Text("Host or URL") }, enabled = !running)
            if (tool == "TCP test") OutlinedTextField(port, { port = it }, label = { Text("Port") }, enabled = !running)
            Text(result)
        }
    }, confirmButton = {
        TextButton(enabled = !running, onClick = {
            scope.launch {
                running = true
                try { result = withContext(Dispatchers.IO) {
                    runCatching { NetworkTools.run(tool, target, port.toIntOrNull() ?: 443) }
                        .getOrElse { it.message ?: "Failed" }
                } } finally { running = false }
            }
        }) { Text(if (running) "Running..." else "Run") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
