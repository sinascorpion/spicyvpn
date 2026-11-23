package com.example.spicyvpn.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.math.BigInteger
import org.json.JSONObject
import java.security.SecureRandom

// Comprehensive list of countries with flag emojis
val psiphonCountries = listOf(
    Triple("Auto", "Auto", "🌐"),
    Triple("US", "United States", "🇺🇸"),
    Triple("CA", "Canada", "🇨🇦"),
    Triple("GB", "United Kingdom", "🇬🇧"),
    Triple("DE", "Germany", "🇩🇪"),
    Triple("NL", "Netherlands", "🇳🇱"),
    Triple("SG", "Singapore", "🇸🇬"),
    Triple("JP", "Japan", "🇯🇵"),
    Triple("TR", "Turkey", "🇹🇷"),
    Triple("FR", "France", "🇫🇷"),
    Triple("AU", "Australia", "🇦🇺"),
    Triple("IN", "India", "🇮🇳"),
    Triple("KR", "South Korea", "🇰🇷"),
    Triple("HK", "Hong Kong", "🇭🇰"),
    Triple("AE", "United Arab Emirates", "🇦🇪"),
    Triple("IT", "Italy", "🇮🇹"),
    Triple("ES", "Spain", "🇪🇸"),
    Triple("CH", "Switzerland", "🇨🇭"),
    Triple("SE", "Sweden", "🇸🇪"),
    Triple("FI", "Finland", "🇫🇮"),
    Triple("PL", "Poland", "🇵🇱"),
    Triple("BR", "Brazil", "🇧🇷"),
    Triple("AT", "Austria", "🇦🇹"),
    Triple("BE", "Belgium", "🇧🇪"),
    Triple("DK", "Denmark", "🇩🇰"),
    Triple("NO", "Norway", "🇳🇴"),
    Triple("RU", "Russia", "🇷🇺")
)

val psiphonProtocols = listOf("auto", "normal", "conduit", "cdn_fronting")

// Clean Cloudflare Warp IPs preset list
val cleanWarpIps = listOf(
    "162.159.192.1", "162.159.192.2", "162.159.192.3", "162.159.192.4",
    "162.159.192.5", "162.159.192.6", "162.159.192.7", "162.159.192.8",
    "162.159.192.9", "162.159.192.10", "162.159.193.1", "162.159.193.2",
    "162.159.193.3", "162.159.193.4", "162.159.193.5", "162.159.195.1",
    "162.159.195.2", "162.159.195.3", "162.159.195.4", "162.159.195.5",
    "188.114.96.1", "188.114.96.2", "188.114.96.3", "188.114.97.1",
    "188.114.97.2", "188.114.98.1", "188.114.99.1"
)
val warpPorts = listOf("2408", "1701", "500", "4500", "854")

// Helper to get local IP address
fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val inetAddress = addresses.nextElement()
                if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                    return inetAddress.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "10.0.0.103"
}

// Simple AES encryption helper for configs
fun encryptAES(plainText: String): String {
    try {
        val secretKey = javax.crypto.spec.SecretKeySpec("SpicyVpnPsiphonKey".toByteArray().copyOf(16), "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encryptedBytes, Base64.DEFAULT).trim().replace("\n", "").replace("\r", "")
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return plainText
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CombinedButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(40.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = ButtonDefaults.shape,
        color = ButtonDefaults.buttonColors().containerColor,
        contentColor = ButtonDefaults.buttonColors().contentColor
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

fun expandCidr(cidr: String): List<String> {
    try {
        val trimmed = cidr.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split("/")
        if (parts.size != 2) return listOf(trimmed)
        val ipPart = parts[0]
        val maskPart = parts[1].toIntOrNull() ?: return listOf(trimmed)
        if (maskPart !in 0..32) return emptyList()
        
        val ipParts = ipPart.split(".").map { it.toLongOrNull() ?: return emptyList() }
        if (ipParts.size != 4 || ipParts.any { it !in 0..255 }) return emptyList()
        
        val address = (ipParts[0] shl 24) or (ipParts[1] shl 16) or (ipParts[2] shl 8) or ipParts[3]
        val baseIp = address and ((0xffffffffL shl (32 - maskPart)) and 0xffffffffL)
        val numAddresses = 1L shl (32 - maskPart)
        val samples = minOf(numAddresses, 512)
        val step = maxOf(1, numAddresses / samples)
        
        val list = mutableListOf<String>()
        for (i in 0 until samples) {
            val currentIp = baseIp + i * step
            val ipStr = "${(currentIp shr 24) and 0xFF}.${(currentIp shr 16) and 0xFF}.${(currentIp shr 8) and 0xFF}.${currentIp and 0xFF}"
            list.add(ipStr)
        }
        return list
    } catch (e: Exception) {
        return listOf(cidr)
    }
}

@Composable
fun IpScannerDialog(
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scanResults = remember { mutableStateListOf<Pair<String, String>>() }
    var scanSni by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_sni_server") ?: "www.akamai.com") }
    val scanContext = LocalContext.current
    
    val defaultIps = remember {
        """
        23.1.244.170
        23.3.93.99
        23.4.195.140
        23.5.215.110
        23.51.127.18
        104.86.113.161
        104.81.12.13
        104.79.112.55
        104.75.12.3
        104.72.12.8
        104.68.12.9
        104.66.12.10
        184.25.12.11
        184.28.12.12
        184.30.12.13
        184.85.12.14
        184.87.12.15
        184.50.12.16
        184.51.12.17
        96.7.12.18
        """.trimIndent()
    }
    
    var ipListInput by remember { mutableStateOf(defaultIps) }
    var portInput by remember { mutableStateOf("443") }
    var concurrencyInput by remember { mutableStateOf("100") }
    var timeoutInput by remember { mutableStateOf("1000") }
    var progressText by remember { mutableStateOf("Ready to scan") }

    AlertDialog(
        onDismissRequest = {
            scanJob?.cancel()
            onDismiss()
        },
        title = { Text("Spicy IP Scanner", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Scan candidate CDN IPs / Subnets concurrently to find clean fronting servers.", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = ipListInput,
                    onValueChange = { ipListInput = it },
                    label = { Text("Target IPs / subnets (one per line)") },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    maxLines = 10,
                    enabled = !scanning
                )
                OutlinedTextField(scanSni, { scanSni = it }, label = { Text("TLS server name (SNI)") },
                    modifier = Modifier.fillMaxWidth(), enabled = !scanning)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        enabled = !scanning
                    )
                    OutlinedTextField(
                        value = concurrencyInput,
                        onValueChange = { concurrencyInput = it },
                        label = { Text("Threads") },
                        modifier = Modifier.weight(1f),
                        enabled = !scanning
                    )
                    OutlinedTextField(
                        value = timeoutInput,
                        onValueChange = { timeoutInput = it },
                        label = { Text("Timeout (ms)") },
                        modifier = Modifier.weight(1f),
                        enabled = !scanning
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                if (scanning) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!scanning) {
                        Button(
                            onClick = {
                                scanning = true
                                scanResults.clear()
                                progressText = "Starting scan..."
                                val lines = ipListInput.split("\n")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                val candidates = mutableListOf<String>()
                                lines.forEach { line ->
                                    if (line.contains("/")) {
                                        candidates.addAll(expandCidr(line))
                                    } else {
                                        candidates.add(line)
                                    }
                                }
                                
                                val finalCandidates = candidates.take(500)
                                val port = portInput.toIntOrNull() ?: 443
                                val concurrency = (concurrencyInput.toIntOrNull() ?: 16).coerceIn(1, 32)
                                val timeout = (timeoutInput.toIntOrNull() ?: 3000).coerceIn(500, 15000)
                                
                                scanJob = coroutineScope.launch(Dispatchers.IO) {
                                    val semaphore = Semaphore(concurrency)
                                    val total = finalCandidates.size
                                    var currentScanned = 0
                                    
                                    val jobsList = finalCandidates.map { ip ->
                                        async {
                                            if (!isActive) return@async
                                            semaphore.withPermit {
                                                if (!isActive) return@async
                                                val start = System.currentTimeMillis()
                                                var ok = false
                                                try {
                                                    com.v2ray.ang.core.TlsProbe.latency(ip, port, scanSni.trim(), timeout)
                                                    ok = true
                                                } catch (e: Exception) {
                                                    // failed
                                                }
                                                
                                                if (ok && isActive) {
                                                    val latency = System.currentTimeMillis() - start
                                                    withContext(Dispatchers.Main) {
                                                        scanResults.add(ip to "${latency}ms")
                                                        scanResults.sortBy { it.second.replace("ms", "").toLongOrNull() ?: 99999L }
                                                    }
                                                }
                                                
                                                withContext(Dispatchers.Main) {
                                                    currentScanned++
                                                    progressText = "Scanned $currentScanned / $total (Found: ${scanResults.size})"
                                                }
                                            }
                                        }
                                    }
                                    jobsList.awaitAll()
                                    scanning = false
                                    scanJob = null
                                    progressText = "Scan completed! Found ${scanResults.size} working IPs"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("START SCAN")
                        }
                    } else {
                        Button(
                            onClick = {
                                scanJob?.cancel()
                                scanning = false
                                scanJob = null
                                progressText = "Scan stopped manually. Found ${scanResults.size} IPs"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("STOP SCAN")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text("Scan Results (Sorted by Ping):", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(4.dp))
                
                LazyColumn(modifier = Modifier.height(180.dp).fillMaxWidth()) {
                    items(scanResults.size) { index ->
                        val res = scanResults[index]
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(res.first, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = res.second,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                Row {
                    TextButton(enabled = scanResults.isNotEmpty(), onClick = {
                        (scanContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                            ClipData.newPlainText("IP scan", scanResults.joinToString("\n") { it.first }))
                    }) { Text("Copy all") }
                    TextButton(enabled = scanResults.isNotEmpty(), onClick = {
                        (scanContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                            ClipData.newPlainText("IP scan", scanResults.take(16).joinToString("\n") { it.first }))
                    }) { Text("Copy top 16") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scanJob?.cancel()
                    val successfulIps = scanResults.map { it.first }
                    if (successfulIps.isNotEmpty()) {
                        MmkvManager.encodeSettings("pref_psiphon_sni_server", scanSni.trim())
                        onApply(successfulIps.take(5).joinToString(","))
                    }
                    onDismiss()
                },
                enabled = scanResults.isNotEmpty()
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                scanJob?.cancel()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun WarpSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRegistering by remember { mutableStateOf(false) }
    var isForcing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (isForcing) com.v2ray.ang.tunnel.WarpBootstrap.cancel() }
    }

    // State bindings
    var ip by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_ip") ?: "engage.cloudflareclient.com") }
    var port by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_port") ?: "2408") }
    var publicKey by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_public_key") ?: "") }
    var privateKey by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_private_key") ?: "") }
    var presharedKey by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_preshared_key") ?: "") }
    var localAddress by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_local_address") ?: "172.16.0.2/32") }
    var reserved by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_reserved") ?: "0,0,0") }
    var mtu by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_mtu") ?: "1280") }
    var keepAlive by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_keepalive") ?: "5") }
    var wnoise by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_wnoise") ?: "quic") }
    var wnoisecount by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_wnoisecount") ?: "15") }
    var wnoisedelay by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_wnoisedelay") ?: "1-2") }
    var wpayloadsize by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_wpayloadsize") ?: "5-10") }
    var remark by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_remark") ?: "warp") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create new Warp", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text(
                        "create your own warp config or get from t.me/freewireguard",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Row 1: PASTE CONFIG and CLEAR
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotEmpty()) {
                                        val parsed = parseWireguardConfig(text)
                                        if (parsed != null) {
                                            ip = parsed.endpointHost
                                            port = parsed.endpointPort
                                            publicKey = parsed.publicKey
                                            privateKey = parsed.privateKey
                                            presharedKey = parsed.presharedKey
                                            localAddress = parsed.localAddress
                                            reserved = parsed.reserved
                                            mtu = parsed.mtu
                                            Toast.makeText(context, "Config pasted successfully!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Invalid Wireguard format in clipboard.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("PASTE CONFIG")
                        }

                        Button(
                            onClick = {
                                ip = "engage.cloudflareclient.com"
                                port = "2408"
                                publicKey = ""
                                privateKey = ""
                                presharedKey = ""
                                localAddress = "172.16.0.2/32"
                                reserved = "0,0,0"
                                mtu = "1280"
                                keepAlive = "5"
                                wnoise = "quic"
                                wnoisecount = "15"
                                wnoisedelay = "1-2"
                                wpayloadsize = "5-10"
                                remark = "warp"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CLEAR")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Row 2: NEW WARP KEY and NEW WARP IP
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isRegistering = true
                                coroutineScope.launch {
                                    val result = registerWarpClient()
                                    isRegistering = false
                                    if (result != null) {
                                        privateKey = result.privateKey
                                        publicKey = result.publicKey
                                        localAddress = result.localAddress
                                        reserved = result.reserved
                                        ip = result.endpointHost
                                        port = result.endpointPort
                                        Toast.makeText(context, "New Warp Config Registered successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Registration failed, check connection.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isRegistering,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC2185B))
                        ) {
                            Text(if (isRegistering) "Registering..." else "NEW WARP KEY")
                        }

                        Button(
                            onClick = {
                                isRegistering = true
                                isForcing = true
                                coroutineScope.launch {
                                    try {
                                        val result = com.v2ray.ang.tunnel.WarpBootstrap.force(context)
                                        if (result != null) {
                                            privateKey = result.privateKey
                                            publicKey = result.publicKey
                                            localAddress = result.localAddress
                                            reserved = result.reserved
                                            ip = result.endpointHost
                                            port = result.endpointPort
                                            Toast.makeText(context, "WARP key received", Toast.LENGTH_SHORT).show()
                                        } else Toast.makeText(context, "No working bootstrap route", Toast.LENGTH_LONG).show()
                                    } finally { isRegistering = false; isForcing = false }
                                }
                            },
                            enabled = !isRegistering,
                            modifier = Modifier.weight(1f)
                        ) { Text("Force") }

                        Button(
                            onClick = {
                                ip = cleanWarpIps.random()
                                port = warpPorts.random()
                                Toast.makeText(context, "Clean IP selected: $ip", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                        ) {
                            Text("NEW WARP IP")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP (clean ip)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("port (clean port)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = publicKey, onValueChange = { publicKey = it }, label = { Text("public key") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = privateKey, onValueChange = { privateKey = it }, label = { Text("private key") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = presharedKey, onValueChange = { presharedKey = it }, label = { Text("preshared key") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = localAddress, onValueChange = { localAddress = it }, label = { Text("local address") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = reserved, onValueChange = { reserved = it }, label = { Text("Reserved") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = mtu, onValueChange = { mtu = it }, label = { Text("mtu") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = keepAlive, onValueChange = { keepAlive = it }, label = { Text("keepAlive") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = wnoise, onValueChange = { wnoise = it }, label = { Text("wnoise") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = wnoisecount, onValueChange = { wnoisecount = it }, label = { Text("wnoisecount") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = wnoisedelay, onValueChange = { wnoisedelay = it }, label = { Text("wnoisedelay") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = wpayloadsize, onValueChange = { wpayloadsize = it }, label = { Text("wpayloadsize") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("remark") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    val profileItem = com.v2ray.ang.dto.entities.ProfileItem.create(com.v2ray.ang.enums.EConfigType.WIREGUARD)
                    profileItem.server = ip
                    profileItem.serverPort = port
                    profileItem.publicKey = publicKey
                    profileItem.secretKey = privateKey
                    profileItem.preSharedKey = presharedKey
                    profileItem.localAddress = localAddress
                    profileItem.reserved = reserved
                    profileItem.mtu = mtu.toIntOrNull() ?: 1280
                    profileItem.remarks = remark.ifEmpty { "Warp Config" }

                    val key = MmkvManager.encodeServerConfig("", profileItem)
                    val rawUrl = "wireguard://$privateKey@$ip:$port?address=${java.net.URLEncoder.encode(localAddress, "UTF-8")}&publickey=${java.net.URLEncoder.encode(publicKey, "UTF-8")}&reserved=${java.net.URLEncoder.encode(reserved, "UTF-8")}&mtu=$mtu&keepalive=$keepAlive&wnoise=$wnoise&wnoisecount=$wnoisecount&wnoisedelay=$wnoisedelay&wpayloadsize=$wpayloadsize#${java.net.URLEncoder.encode(profileItem.remarks, "UTF-8")}"
                    MmkvManager.encodeServerRaw(key, rawUrl)

                    // Save internal preferences too
                    MmkvManager.encodeSettings("pref_warp_ip", ip)
                    MmkvManager.encodeSettings("pref_warp_port", port)
                    MmkvManager.encodeSettings("pref_warp_public_key", publicKey)
                    MmkvManager.encodeSettings("pref_warp_private_key", privateKey)
                    MmkvManager.encodeSettings("pref_warp_preshared_key", presharedKey)
                    MmkvManager.encodeSettings("pref_warp_local_address", localAddress)
                    MmkvManager.encodeSettings("pref_warp_reserved", reserved)
                    MmkvManager.encodeSettings("pref_warp_mtu", mtu)
                    MmkvManager.encodeSettings("pref_warp_keepalive", keepAlive)
                    MmkvManager.encodeSettings("pref_warp_wnoise", wnoise)
                    MmkvManager.encodeSettings("pref_warp_wnoisecount", wnoisecount)
                    MmkvManager.encodeSettings("pref_warp_wnoisedelay", wnoisedelay)
                    MmkvManager.encodeSettings("pref_warp_wpayloadsize", wpayloadsize)
                    MmkvManager.encodeSettings("pref_warp_remark", remark)
                    MmkvManager.encodeSettings("pref_warp_endpoint", "$ip:$port")

                    Toast.makeText(context, "Warp added to server list!", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) {
                    Text("ADD TO CONFIG", style = MaterialTheme.typography.labelSmall)
                }

                Button(onClick = {
                    // Save internal preferences
                    MmkvManager.encodeSettings("pref_warp_ip", ip)
                    MmkvManager.encodeSettings("pref_warp_port", port)
                    MmkvManager.encodeSettings("pref_warp_public_key", publicKey)
                    MmkvManager.encodeSettings("pref_warp_private_key", privateKey)
                    MmkvManager.encodeSettings("pref_warp_preshared_key", presharedKey)
                    MmkvManager.encodeSettings("pref_warp_local_address", localAddress)
                    MmkvManager.encodeSettings("pref_warp_reserved", reserved)
                    MmkvManager.encodeSettings("pref_warp_mtu", mtu)
                    MmkvManager.encodeSettings("pref_warp_keepalive", keepAlive)
                    MmkvManager.encodeSettings("pref_warp_wnoise", wnoise)
                    MmkvManager.encodeSettings("pref_warp_wnoisecount", wnoisecount)
                    MmkvManager.encodeSettings("pref_warp_wnoisedelay", wnoisedelay)
                    MmkvManager.encodeSettings("pref_warp_wpayloadsize", wpayloadsize)
                    MmkvManager.encodeSettings("pref_warp_remark", remark)
                    MmkvManager.encodeSettings("pref_warp_endpoint", "$ip:$port")

                    Toast.makeText(context, "Warp settings saved!", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) {
                    Text("SAVE", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    val rawUrl = "wireguard://$privateKey@$ip:$port?address=${java.net.URLEncoder.encode(localAddress, "UTF-8")}&publickey=${java.net.URLEncoder.encode(publicKey, "UTF-8")}&reserved=${java.net.URLEncoder.encode(reserved, "UTF-8")}&mtu=$mtu&keepalive=$keepAlive&wnoise=$wnoise&wnoisecount=$wnoisecount&wnoisedelay=$wnoisedelay&wpayloadsize=$wpayloadsize#${java.net.URLEncoder.encode(remark.ifEmpty { "Warp" }, "UTF-8")}"
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Warp URI", rawUrl))
                    Toast.makeText(context, "Exported to clipboard!", Toast.LENGTH_SHORT).show()
                }) {
                    Text("EXPORT", style = MaterialTheme.typography.labelSmall)
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PsiphonSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State bindings
    val availableRegions = MmkvManager.decodeSettingsString("pref_psiphon_available_regions")
        .orEmpty().split(',').filter { it.matches(Regex("[A-Z]{2}")) }
    val selectableCountries = if (availableRegions.isEmpty()) psiphonCountries else {
        listOf(psiphonCountries.first()) + availableRegions.map { code ->
            psiphonCountries.firstOrNull { it.first == code } ?: Triple(code,
                java.util.Locale("", code).displayCountry,
                code.map { Character.toChars(0x1F1E6 + it.code - 'A'.code).concatToString() }.joinToString(""))
        }
    }
    var country by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_country") ?: "Auto") }
    val region = if (country.equals("Auto", ignoreCase = true)) "auto" else country
    var protocol by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_protocol") ?: "auto") }
    var aggressive by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_aggressive") ?: "ON") }
    var socksPort by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_socks_port") ?: "20808") }
    var httpPort by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_http_port") ?: "20809") }
    var allowLan by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_psiphon_allow_lan", false)) }

    // CDN protocols
    val cdnProtocolsStr = MmkvManager.decodeSettingsString("pref_psiphon_cdn_protocols") ?: "tls,http,quic"
    var tlsChecked by remember { mutableStateOf(cdnProtocolsStr.contains("tls")) }
    var httpChecked by remember { mutableStateOf(cdnProtocolsStr.contains("http")) }
    var quicChecked by remember { mutableStateOf(cdnProtocolsStr.contains("quic")) }

    var skipCert by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_psiphon_skip_cert", true)) }
    var addBuiltin by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_psiphon_add_builtin", true)) }
    var cdnType by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_cdn_type") ?: "akamai") }
    var akamaiCdnIp by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_akamai_cdn_ip") ?: "") }
    var noSni by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_psiphon_no_sni", false)) }
    var sniServer by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_psiphon_sni_server") ?: "www.python.org") }

    // Dropdowns visibility
    var expandedCountry by remember { mutableStateOf(false) }
    var expandedProtocol by remember { mutableStateOf(false) }
    var expandedAggressive by remember { mutableStateOf(false) }
    var expandedCdnType by remember { mutableStateOf(false) }

    // Dialog state
    var showAllowLanInfo by remember { mutableStateOf(false) }
    var showCopyInfo by remember { mutableStateOf(false) }
    var showIpScanner by remember { mutableStateOf(false) }

    val aggressiveOptions = listOf("ON", "OFF")
    val cdnTypes = listOf("akamai", "fronting")

    if (showIpScanner) {
        IpScannerDialog(
            onDismiss = { showIpScanner = false },
            onApply = { akamaiCdnIp = it }
        )
    }

    if (showAllowLanInfo) {
        val ipAddr = getLocalIpAddress()
        AlertDialog(
            onDismissRequest = { showAllowLanInfo = false },
            title = { Text("Allow LAN", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "When \"Allow LAN\" is ON, Psiphon's proxy listens on all network interfaces, so other devices on the same Wi-Fi / LAN can route their traffic through this phone:\n\n" +
                            "HTTP proxy:\n$ipAddr:$httpPort\n\n" +
                            "SOCKS5 proxy:\n$ipAddr:$socksPort\n\n" +
                            "When \"Allow LAN\" is OFF, the proxy is reachable only from this device (127.0.0.1).",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showAllowLanInfo = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showCopyInfo) {
        AlertDialog(
            onDismissRequest = { showCopyInfo = false },
            title = { Text("buttons instruction", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "generate psiphon:// config from settings\n\n" +
                            "tap = copy URL to clipboard\n" +
                            "long-press = add to config list\n" +
                            "AES button generate crypted url",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showCopyInfo = false }) {
                    Text("OK")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Psiphon settings", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Country Selection with Flags
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Country", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        Box {
                            val selectedCountry = selectableCountries.find { it.first == country } ?: Triple("Auto", "Auto", "🌐")
                            OutlinedButton(onClick = { expandedCountry = true }) {
                                Text("${selectedCountry.third} ${selectedCountry.first}")
                            }
                            DropdownMenu(expanded = expandedCountry, onDismissRequest = { expandedCountry = false }) {
                                selectableCountries.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text("${c.third} ${c.second} (${c.first})") },
                                        onClick = { country = c.first; expandedCountry = false }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Region
                item {
                    OutlinedTextField(
                        value = region,
                        onValueChange = {},
                        label = { Text("Region") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Protocol
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Protocol *", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        Box {
                            OutlinedButton(onClick = { expandedProtocol = true }) {
                                Text(protocol)
                            }
                            DropdownMenu(expanded = expandedProtocol, onDismissRequest = { expandedProtocol = false }) {
                                psiphonProtocols.forEach { p ->
                                    DropdownMenuItem(text = { Text(p) }, onClick = { protocol = p; expandedProtocol = false })
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Aggressive
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Aggressive", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        Box {
                            OutlinedButton(onClick = { expandedAggressive = true }) {
                                Text(aggressive)
                            }
                            DropdownMenu(expanded = expandedAggressive, onDismissRequest = { expandedAggressive = false }) {
                                aggressiveOptions.forEach { a ->
                                    DropdownMenuItem(text = { Text(a) }, onClick = { aggressive = a; expandedAggressive = false })
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Socks Port & Http Port
                item {
                    OutlinedTextField(value = socksPort, onValueChange = { socksPort = it }, label = { Text("socks port") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = httpPort, onValueChange = { httpPort = it }, label = { Text("http port") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Allow LAN
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Allow LAN", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showAllowLanInfo = true }, modifier = Modifier.size(24.dp)) {
                                Text("ℹ️", color = Color(0xFF4CAF50))
                            }
                        }
                        Switch(checked = allowLan, onCheckedChange = { allowLan = it })
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // CDN Fronting Settings
                if (protocol == "cdn_fronting") {
                    item {
                        Text("CDN Fronting Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text("Enabled when Protocol == cdn_fronting", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("CDN protocols:", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = tlsChecked, onCheckedChange = { tlsChecked = it ?: false })
                            Text("tls")
                            Spacer(modifier = Modifier.width(16.dp))
                            Checkbox(checked = httpChecked, onCheckedChange = { httpChecked = it ?: false })
                            Text("http")
                            Spacer(modifier = Modifier.width(16.dp))
                            Checkbox(checked = quicChecked, onCheckedChange = { quicChecked = it ?: false })
                            Text("quic")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Skip Cert Verification", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            Switch(checked = skipCert, onCheckedChange = { skipCert = it })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Add built-in configs", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            Switch(checked = addBuiltin, onCheckedChange = { addBuiltin = it })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("CDN type:", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            Box {
                                OutlinedButton(onClick = { expandedCdnType = true }) {
                                    Text(cdnType)
                                }
                                DropdownMenu(expanded = expandedCdnType, onDismissRequest = { expandedCdnType = false }) {
                                    cdnTypes.forEach { t ->
                                        DropdownMenuItem(text = { Text(t) }, onClick = { cdnType = t; expandedCdnType = false })
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("akamai CDN ip", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { showIpScanner = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                            ) {
                                Text("Spicy IP Scanner", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = akamaiCdnIp,
                            onValueChange = { akamaiCdnIp = it },
                            label = { Text("one IP per line or comma-separated") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // no-SNI & SNI server name
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("no-SNI (DisableSNI)", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        Switch(checked = noSni, onCheckedChange = { noSni = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sniServer,
                        onValueChange = { sniServer = it },
                        label = { Text("SNI server name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Styled Copy buttons with Instructions Icon
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Helper to generate the configuration URL
                        val makeUrl = {
                            "psiphon://?region=$region&protocol=$protocol&aggressive=$aggressive" +
                                    "&cdn_type=$cdnType&cdn_sni=${java.net.URLEncoder.encode(sniServer, "UTF-8")}" +
                                    "&no_sni=$noSni&skip_cert=$skipCert" +
                                    "&proto_tls=$tlsChecked&proto_http=$httpChecked&proto_quic=$quicChecked" +
                                    "&builtin=$addBuiltin#SpicyVPN"
                        }

                        val importConfig = { url: String ->
                            val (count, _) = AngConfigManager.importBatchConfig(url, "", true)
                            if (count > 0) {
                                Toast.makeText(context, "Psiphon added to server list!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to import Psiphon config", Toast.LENGTH_SHORT).show()
                            }
                        }

                        CombinedButton(
                            text = "COPY RAW URL",
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Psiphon Config", makeUrl()))
                                Toast.makeText(context, "Config copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            onLongClick = {
                                importConfig(makeUrl())
                            },
                            modifier = Modifier.weight(1f)
                        )

                        CombinedButton(
                            text = "COPY AES URL",
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val encrypted = "psiphon-aes://${encryptAES(makeUrl())}"
                                clipboard.setPrimaryClip(ClipData.newPlainText("Psiphon AES", encrypted))
                                Toast.makeText(context, "Encrypted config copied!", Toast.LENGTH_SHORT).show()
                            },
                            onLongClick = {
                                val encrypted = "psiphon-aes://${encryptAES(makeUrl())}"
                                importConfig(encrypted)
                            },
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { showCopyInfo = true }, modifier = Modifier.size(32.dp)) {
                            Text("ℹ️", color = Color(0xFF4CAF50))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cdnProtocols = mutableListOf<String>()
                if (tlsChecked) cdnProtocols.add("tls")
                if (httpChecked) cdnProtocols.add("http")
                if (quicChecked) cdnProtocols.add("quic")

                MmkvManager.encodeSettings("pref_psiphon_country", country)
                MmkvManager.encodeSettings("pref_psiphon_region", region)
                MmkvManager.encodeSettings("pref_psiphon_protocol", protocol)
                MmkvManager.encodeSettings("pref_psiphon_aggressive", aggressive)
                MmkvManager.encodeSettings("pref_psiphon_socks_port", socksPort)
                MmkvManager.encodeSettings("pref_psiphon_http_port", httpPort)
                MmkvManager.encodeSettings("pref_psiphon_allow_lan", allowLan)
                MmkvManager.encodeSettings("pref_psiphon_cdn_protocols", cdnProtocols.joinToString(","))
                MmkvManager.encodeSettings("pref_psiphon_skip_cert", skipCert)
                MmkvManager.encodeSettings("pref_psiphon_add_builtin", addBuiltin)
                MmkvManager.encodeSettings("pref_psiphon_cdn_type", cdnType)
                MmkvManager.encodeSettings("pref_psiphon_akamai_cdn_ip", akamaiCdnIp)
                MmkvManager.encodeSettings("pref_psiphon_no_sni", noSni)
                MmkvManager.encodeSettings("pref_psiphon_sni_server", sniServer)
                MmkvManager.encodeSettings("pref_psiphon_port", socksPort)

                // MahsaNG/Shirokhorshid compatible keys
                MmkvManager.encodeSettings("pref_psiphon_cdn_sni", sniServer)
                MmkvManager.encodeSettings("pref_psiphon_builtin", addBuiltin)
                MmkvManager.encodeSettings("pref_psiphon_proto_tls", tlsChecked)
                MmkvManager.encodeSettings("pref_psiphon_proto_http", httpChecked)
                MmkvManager.encodeSettings("pref_psiphon_proto_quic", quicChecked)

                Toast.makeText(context, "Psiphon settings saved!", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text("SAVE PSIPHON SETTING")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

class WarpRegistrationResult(
    val privateKey: String,
    val publicKey: String,
    val localAddress: String,
    val reserved: String,
    val endpointHost: String,
    val endpointPort: String
)

suspend fun registerWarpClient(proxy: java.net.Proxy? = null): WarpRegistrationResult? = withContext(Dispatchers.IO) {
    try {
        val privateKey = ByteArray(32).also(SecureRandom()::nextBytes)
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = ((privateKey[31].toInt() and 127) or 64).toByte()
        val publicKey = x25519(privateKey, byteArrayOf(9) + ByteArray(31))
        val privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
        val publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)

        val customDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                return okhttp3.Dns.SYSTEM.lookup(hostname)
            }
        }

        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .dns(customDns)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val json = JSONObject().apply {
            put("key", publicKeyBase64)
            put("tos", "2020-05-18T00:00:00.000Z")
            put("warp_enabled", true)
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, json.toString())
        val request = Request.Builder()
            .url("https://api.cloudflareclient.com/v0a2158/reg")
            .addHeader("CF-Client-Version", "a-6.11-2158")
            .addHeader("User-Agent", "okhttp/3.12.1")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
            val respStr = response.body?.string() ?: return@withContext null
            val respJson = JSONObject(respStr)

            val configObj = respJson.getJSONObject("config")
            val interfaceObj = configObj.getJSONObject("interface")
            val addressesObj = interfaceObj.getJSONObject("addresses")
            val localAddrV4 = addressesObj.optString("v4", "172.16.0.2/32")
            val localAddrV6 = addressesObj.optString("v6", "")

            val peersArr = configObj.getJSONArray("peers")
            val peerObj = peersArr.getJSONObject(0)
            val endpointObj = peerObj.getJSONObject("endpoint")
            val endpoint = endpointObj.optString("v4", "engage.cloudflareclient.com:2408")
            val endpointHost = endpoint.substringBefore(':')
            val endpointPort = endpoint.substringAfter(':', "2408").toIntOrNull()?.takeIf { it in 1..65535 } ?: 2408

            val clientIdHex = respJson.optString("client_id", "")
            val reservedStr = if (clientIdHex.isNotEmpty()) {
                try {
                    val decoded = Base64.decode(clientIdHex, Base64.DEFAULT)
                    decoded.map { it.toInt() and 0xFF }.joinToString(",")
                } catch (e: Exception) {
                    "0,0,0"
                }
            } else {
                "0,0,0"
            }

            val peerPublicKey = peerObj.optString("public_key", "")
            if (peerPublicKey.isBlank()) return@withContext null

            WarpRegistrationResult(
                privateKey = privateKeyBase64,
                publicKey = peerPublicKey,
                localAddress = listOfNotNull(
                    if ('/' in localAddrV4) localAddrV4 else "$localAddrV4/32",
                    localAddrV6.takeIf { it.isNotBlank() }?.let { if ('/' in it) it else "$it/128" }
                ).joinToString(","),
                reserved = reservedStr,
                endpointHost = endpointHost,
                endpointPort = endpointPort.toString()
            )
        } else {
            null
        }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        null
    }
}

private val x25519Prime: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

private fun x25519(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
    val x1 = littleEndianToBigInteger(uCoordinate).mod(x25519Prime)
    var x2 = BigInteger.ONE
    var z2 = BigInteger.ZERO
    var x3 = x1
    var z3 = BigInteger.ONE
    var swap = 0

    for (bit in 254 downTo 0) {
        val currentBit = (scalar[bit / 8].toInt() ushr (bit and 7)) and 1
        if ((swap xor currentBit) == 1) {
            val nextX2 = x3
            x3 = x2
            x2 = nextX2
            val nextZ2 = z3
            z3 = z2
            z2 = nextZ2
        }
        swap = currentBit

        val a = x2.add(z2).mod(x25519Prime)
        val aa = a.multiply(a).mod(x25519Prime)
        val b = x2.subtract(z2).mod(x25519Prime)
        val bb = b.multiply(b).mod(x25519Prime)
        val e = aa.subtract(bb).mod(x25519Prime)
        val c = x3.add(z3).mod(x25519Prime)
        val d = x3.subtract(z3).mod(x25519Prime)
        val da = d.multiply(a).mod(x25519Prime)
        val cb = c.multiply(b).mod(x25519Prime)
        x3 = da.add(cb).mod(x25519Prime).pow(2).mod(x25519Prime)
        z3 = x1.multiply(da.subtract(cb).mod(x25519Prime).pow(2)).mod(x25519Prime)
        x2 = aa.multiply(bb).mod(x25519Prime)
        z2 = e.multiply(aa.add(BigInteger.valueOf(121665).multiply(e))).mod(x25519Prime)
    }

    if (swap == 1) {
        val nextX2 = x3
        x3 = x2
        x2 = nextX2
        val nextZ2 = z3
        z3 = z2
        z2 = nextZ2
    }
    return bigIntegerToLittleEndian(x2.multiply(z2.modInverse(x25519Prime)).mod(x25519Prime))
}

private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger =
    BigInteger(1, bytes.reversedArray())

private fun bigIntegerToLittleEndian(value: BigInteger): ByteArray {
    val bigEndian = value.toByteArray()
    val output = ByteArray(32)
    for (index in bigEndian.indices) {
        val target = bigEndian.size - 1 - index
        if (target in output.indices) {
            output[target] = bigEndian[index]
        }
    }
    return output
}

class WireguardParsedConfig(
    val privateKey: String,
    val publicKey: String,
    val presharedKey: String,
    val localAddress: String,
    val reserved: String,
    val endpointHost: String,
    val endpointPort: String,
    val mtu: String
)

fun parseWireguardConfig(text: String): WireguardParsedConfig? {
    val trimmed = text.trim()
    if (trimmed.startsWith("wireguard://", ignoreCase = true)) {
        val parsedItem = com.v2ray.ang.fmt.WireguardFmt.parse(trimmed)
        if (parsedItem != null) {
            return WireguardParsedConfig(
                privateKey = parsedItem.secretKey.orEmpty(),
                publicKey = parsedItem.publicKey.orEmpty(),
                presharedKey = parsedItem.preSharedKey.orEmpty(),
                localAddress = parsedItem.localAddress.orEmpty().ifEmpty { "172.16.0.2/32" },
                reserved = parsedItem.reserved.orEmpty().ifEmpty { "0,0,0" },
                endpointHost = parsedItem.server.orEmpty(),
                endpointPort = parsedItem.serverPort.orEmpty().ifEmpty { "2408" },
                mtu = parsedItem.mtu?.toString() ?: "1280"
            )
        }
    } else if (trimmed.contains("[Interface]", ignoreCase = true) || trimmed.contains("[Peer]", ignoreCase = true)) {
        val parsedItem = com.v2ray.ang.fmt.WireguardFmt.parseWireguardConfFile(trimmed)
        return WireguardParsedConfig(
            privateKey = parsedItem.secretKey.orEmpty(),
            publicKey = parsedItem.publicKey.orEmpty(),
            presharedKey = parsedItem.preSharedKey.orEmpty(),
            localAddress = parsedItem.localAddress.orEmpty().ifEmpty { "172.16.0.2/32" },
            reserved = parsedItem.reserved.orEmpty().ifEmpty { "0,0,0" },
            endpointHost = parsedItem.server.orEmpty(),
            endpointPort = parsedItem.serverPort.orEmpty().ifEmpty { "2408" },
            mtu = parsedItem.mtu?.toString() ?: "1280"
        )
    } else {
        try {
            var privateKey = ""
            var publicKey = ""
            var presharedKey = ""
            var localAddress = ""
            var reserved = "0,0,0"
            var endpointHost = "engage.cloudflareclient.com"
            var endpointPort = "2408"
            var mtu = "1280"

            val lines = text.split("\n", "\r")
            for (line in lines) {
                val cleanLine = line.trim()
                if (cleanLine.startsWith("#") || cleanLine.isEmpty()) continue
                if (cleanLine.contains("=")) {
                    val parts = cleanLine.split("=", limit = 2)
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when {
                        key == "privatekey" || key == "private_key" -> privateKey = value
                        key == "publickey" || key == "public_key" -> publicKey = value
                        key == "presharedkey" || key == "preshared_key" -> presharedKey = value
                        key == "address" -> localAddress = value
                        key == "reserved" -> reserved = value
                        key == "mtu" -> mtu = value
                        key == "endpoint" -> {
                            val epParts = value.split(":")
                            endpointHost = epParts.getOrNull(0) ?: "engage.cloudflareclient.com"
                            endpointPort = epParts.getOrNull(1) ?: "2408"
                        }
                    }
                }
            }
            if (privateKey.isNotEmpty() && publicKey.isNotEmpty()) {
                return WireguardParsedConfig(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    presharedKey = presharedKey,
                    localAddress = localAddress.ifEmpty { "172.16.0.2/32" },
                    reserved = reserved,
                    endpointHost = endpointHost,
                    endpointPort = endpointPort,
                    mtu = mtu
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return null
}
