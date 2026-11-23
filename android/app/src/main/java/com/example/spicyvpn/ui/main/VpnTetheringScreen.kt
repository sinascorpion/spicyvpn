package com.example.spicyvpn.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.painterResource
import com.v2ray.ang.R
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LanSharingConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnTetheringScreen(isRunning: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) }
    var addresses by remember { mutableStateOf(emptyList<String>()) }
    var boundAddresses by remember { mutableStateOf(emptyList<String>()) }
    var httpPort by remember { mutableIntStateOf(SettingsManager.getHttpPort()) }
    var socksPort by remember { mutableIntStateOf(SettingsManager.getSocksPort()) }
    val profile = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
    val supported = profile?.configType != EConfigType.OPENVPN && !MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)

    LaunchedEffect(enabled, isRunning) {
        while (true) {
            addresses = withContext(Dispatchers.IO) { LanSharingConfig.addresses() }
            boundAddresses = MmkvManager.decodeSettingsString("pref_active_lan_addresses").orEmpty().split(',').filter { it in addresses }
            httpPort = MmkvManager.decodeSettingsString("pref_active_http_port")?.toIntOrNull() ?: SettingsManager.getHttpPort()
            socksPort = MmkvManager.decodeSettingsString("pref_active_socks_port")?.toIntOrNull() ?: SettingsManager.getSocksPort()
            delay(2000)
        }
    }
    fun applySharing() {
        if (isRunning && supported) {
            MmkvManager.encodeSettings("pref_suppress_next_connection_ad", true)
            MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("VPN Tethering") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Local proxy sharing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, it)
                    applySharing()
                })
            }
            Button(onClick = {
                try { context.startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }, modifier = Modifier.fillMaxWidth()) { Text("Open hotspot settings") }
            Text("Connect the other device to this phone's hotspot or the same trusted Wi-Fi network.")
            Text("On that device, select Manual proxy in Wi-Fi settings and enter the HTTP address below. Apps that ignore Wi-Fi proxy settings need their own SOCKS/HTTP proxy setup.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!supported) {
                Text("Local proxy sharing is unavailable for the separate OpenVPN engine and DNS-only mode.", color = MaterialTheme.colorScheme.error)
            } else if (!isRunning) {
                Text("Connect the VPN to start sharing.")
            } else if (enabled && addresses.isEmpty()) {
                Text("No private Wi-Fi, hotspot or USB address found. Enable hotspot or connect to Wi-Fi.")
            } else if (enabled && addresses.any { it !in boundAddresses }) {
                Text("Your network address changed. Apply sharing to refresh the listeners.")
                OutlinedButton(onClick = ::applySharing) { Text("Apply sharing / reconnect") }
            }
            if (enabled && isRunning && supported) boundAddresses.forEach { address ->
                val details = "SpicyVPN proxy\nHost: $address\nHTTP port: $httpPort\nSOCKS5 port: $socksPort"
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    SelectionContainer { Text(address, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    Text("HTTP  $httpPort       SOCKS5  $socksPort")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Proxy hostname", address))
                        }) { Icon(painterResource(R.drawable.ic_copy), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy IP") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, details)
                            }, "Share proxy settings"))
                        }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share") }
                    }
                }
            }
            Text("Use a trusted network. Stop sharing when finished. The proxy listens only on detected private LAN addresses, not every network interface.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
