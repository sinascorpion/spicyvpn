package com.example.spicyvpn.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.DnsProbe
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.ui.platform.LocalContext
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context

@Composable
fun DnsScannerDialog(onDismiss: () -> Unit) {
    var candidates by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_dns_scanner_candidates")
        ?: "1.1.1.1\n8.8.8.8\n9.9.9.9\nhttps://cloudflare-dns.com/dns-query\nhttps://dns.google/dns-query\ntls://dns.google") }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<String, Int?>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var total by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { scanJob?.cancel() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("DNS Scanner") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(candidates, { candidates = it }, enabled = !running,
                label = { Text("DNS resolvers") }, modifier = Modifier.fillMaxWidth(), maxLines = 8)
            Text("${results.size} / $total")
            TextButton(enabled = results.any { it.second != null }, onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("DNS", results.filter { it.second != null }.sortedBy { it.second }.joinToString("\n") { it.first }))
            }) { Text("Copy working resolvers") }
            results.sortedBy { it.second ?: Int.MAX_VALUE }.forEach { (resolver, latency) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(resolver, modifier = Modifier.weight(1f))
                    TextButton(enabled = latency != null, onClick = {
                        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, resolver)
                        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, true)
                        onDismiss()
                    }) { Text(latency?.let { "$it ms" } ?: "Failed") }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (running) { scanJob?.cancel(); return@TextButton }
            val list = candidates.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .flatMap { if (it.contains('/') && !it.contains("://")) expandCidr(it).asSequence() else sequenceOf(it) }
                .distinct().take(512).toList()
            MmkvManager.encodeSettings("pref_dns_scanner_candidates", candidates)
            results = emptyList()
            total = list.size
            scanJob = scope.launch {
                running = true
                try {
                    val limit = Semaphore(8)
                    list.map { resolver -> async {
                        limit.withPermit {
                            val latency = withContext(Dispatchers.IO) { runCatching { DnsProbe.latency(resolver) }.getOrNull() }
                            results = results + (resolver to latency)
                        }
                    } }.awaitAll()
                } finally { running = false }
            }
        }) { Text(if (running) "Stop" else "Scan") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
