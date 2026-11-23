package com.example.spicyvpn.ui.main

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.core.DnsProbe

data class DnsProvider(
    val name: String,
    val description: String,
    val value: String,
    val ping: Int = -1
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSettingsScreen(onBack: () -> Unit, onStartDnsOnly: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Load initial DNS
    val currentDns = MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS) ?: "https+local://dns.google/dns-query"

    // Popular providers list
    val popularProviders = remember {
        mutableStateListOf(
            DnsProvider("Google DNS (DoH)", "https://dns.google/dns-query", "https://dns.google/dns-query"),
            DnsProvider("Google DNS (DoT)", "tls://dns.google", "tls://dns.google"),
            DnsProvider("Cloudflare DNS (DoH)", "https://cloudflare-dns.com/dns-query", "https://cloudflare-dns.com/dns-query"),
            DnsProvider("Cloudflare DNS (DoT)", "tls://one.one.one.one", "tls://one.one.one.one"),
            DnsProvider("AdGuard DNS (DoH)", "https://dns.adguard-dns.com/dns-query", "https://dns.adguard-dns.com/dns-query"),
            DnsProvider("AdGuard DNS (DoT)", "tls://dns.adguard-dns.com", "tls://dns.adguard-dns.com"),
            DnsProvider("Quad9 DNS (DoH)", "https://dns.quad9.net/dns-query", "https://dns.quad9.net/dns-query"),
            DnsProvider("Quad9 DNS (DoT)", "tls://dns.quad9.net", "tls://dns.quad9.net"),
            DnsProvider("NextDNS (DoH)", "https://dns.nextdns.io", "https://dns.nextdns.io"),
            DnsProvider("OpenDNS", "208.67.222.222, 208.67.220.220", "208.67.222.222,208.67.220.220"),
            DnsProvider("Shecan DNS", "Bypass sanctions (Iran)", "178.22.122.100,185.51.200.2"),
            DnsProvider("Electro DNS", "Bypass sanctions & gaming (Iran)", "78.157.108.21,78.157.108.22")
        )
    }

    // Determine initial selected option
    val initialSelectedName = popularProviders.find { it.value == currentDns }?.name ?: "Custom"
    var selectedOption by remember { mutableStateOf(initialSelectedName) }

    // Custom DNS fields
    val savedCustomDns = MmkvManager.decodeSettingsString("pref_custom_dns_list") ?: "8.8.8.8"
    val customDnsFields = remember {
        mutableStateListOf<String>().apply {
            if (selectedOption == "Custom") {
                addAll(currentDns.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            } else {
                addAll(savedCustomDns.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
            if (isEmpty()) add("")
        }
    }

    // Ping results for custom fields
    val customFieldsPing = remember { mutableStateMapOf<Int, Int>() }

    var isPinging by remember { mutableStateOf(false) }

    fun saveConfiguration() {
        val remoteDnsVal: String
        if (selectedOption == "Custom") {
            remoteDnsVal = customDnsFields.filter { it.isNotBlank() }.joinToString(",")
            MmkvManager.encodeSettings("pref_custom_dns_list", remoteDnsVal)
        } else {
            remoteDnsVal = popularProviders.find { it.name == selectedOption }?.value ?: "8.8.8.8,8.8.4.4,https+local://dns.google/dns-query"
        }
        
        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, remoteDnsVal)
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, true)
        
        // Extract pure IP addresses for PREF_VPN_DNS
        var vpnDnsVal = remoteDnsVal.split(",")
            .map { it.trim() }
            .filter { com.v2ray.ang.util.Utils.isPureIpAddress(it) }
            .joinToString(",")
            
        if (vpnDnsVal.isEmpty()) {
            vpnDnsVal = "8.8.8.8,8.8.4.4" // Google DNS fallback
        }
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_DNS, vpnDnsVal)
    }

    fun triggerPingAll() {
        isPinging = true
        coroutineScope.launch {
            // Ping popular providers
            popularProviders.forEachIndexed { idx, provider ->
                val delay = measureDnsLatency(provider.value)
                popularProviders[idx] = provider.copy(ping = delay)
            }
            // Ping custom fields
            customDnsFields.forEachIndexed { idx, value ->
                if (value.isNotBlank()) {
                    val delay = measureDnsLatency(value)
                    customFieldsPing[idx] = delay
                }
            }
            isPinging = false
            Toast.makeText(context, "DNS test complete!", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DNS Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (!isPinging) triggerPingAll() },
                        enabled = !isPinging
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Test Latency",
                            tint = if (isPinging) Color.Gray else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // --- Custom DNS configuration at the top ---
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DNS Configuration", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { if (!isPinging) triggerPingAll() },
                        enabled = !isPinging,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPinging) "Testing..." else "Test DNS Latency")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedOption = "Custom"
                            saveConfiguration()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedOption == "Custom",
                        onClick = {
                            selectedOption = "Custom"
                            saveConfiguration()
                        }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Custom DNS Setup", fontWeight = FontWeight.Bold)
                        Text("Configure multiple custom DNS servers (DoH/UDP/TCP)", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }

            if (selectedOption == "Custom") {
                itemsIndexed(customDnsFields) { index, fieldValue ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = fieldValue,
                            onValueChange = { newValue ->
                                customDnsFields[index] = newValue
                                saveConfiguration()
                            },
                            label = { Text("DNS Server ${index + 1}") },
                            placeholder = { Text("e.g. 1.1.1.1 or https+local://...") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                        // Ping display for this field
                        val ping = customFieldsPing[index]
                        if (ping != null) {
                            if (ping < Int.MAX_VALUE) {
                                Text("${ping}ms", color = Color.Green, fontSize = 12.sp, modifier = Modifier.width(50.dp))
                            } else {
                                Text("Fail", color = Color.Red, fontSize = 12.sp, modifier = Modifier.width(50.dp))
                            }
                        }

                        // Add/Remove buttons
                        if (index == customDnsFields.size - 1) {
                            IconButton(onClick = {
                                customDnsFields.add("")
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add DNS Field")
                            }
                        } else {
                            IconButton(onClick = {
                                if (customDnsFields.size > 1) {
                                    customDnsFields.removeAt(index)
                                    customFieldsPing.remove(index)
                                    saveConfiguration()
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove DNS Field")
                            }
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (java.util.Locale.getDefault().language == "fa") "💡 راهنمای DNS رمزنگاری‌شده" else "💡 Secure DNS Guide",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (java.util.Locale.getDefault().language == "fa") {
                                "DNS معمولی با IP یا tcp://، DNS رمزنگاری‌شده HTTPS با https:// و DNS رمزنگاری‌شده TLS با tls:// قابل تنظیم است. tcp:// به‌تنهایی رمزنگاری ندارد."
                            } else {
                                "Standard DNS uses an IP or tcp://. Encrypted DNS uses https:// for DoH or tls:// for DoT. tcp:// alone is not encrypted."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // --- Popular DNS Providers Section ---
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Popular DNS Providers", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
            }

            itemsIndexed(popularProviders) { index, provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedOption = provider.name
                            saveConfiguration()
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedOption == provider.name,
                            onClick = {
                                selectedOption = provider.name
                                saveConfiguration()
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(provider.name, fontWeight = FontWeight.Bold)
                            Text(provider.description, fontSize = 13.sp, color = Color.Gray)
                        }
                    }

                    // Ping display
                    if (provider.ping >= 0) {
                        if (provider.ping < Int.MAX_VALUE) {
                            Text("${provider.ping}ms", color = Color.Green, fontSize = 13.sp)
                        } else {
                            Text("Offline", color = Color.Red, fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private suspend fun measureDnsLatency(dns: String): Int {
    return withContext(Dispatchers.IO) {
        val servers = dns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (servers.isEmpty()) return@withContext Int.MAX_VALUE

        val server = servers.first()
        try {
            DnsProbe.latency(server)
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }
}
