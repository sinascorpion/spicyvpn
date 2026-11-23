package com.example.spicyvpn.ui.main

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CustomShareDialog(folder: SharedFolder, guid: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    fun share(asQr: Boolean) {
        busy = true; error = null
        scope.launch {
            runCatching {
                val link = withContext(Dispatchers.Default) {
                    if (guid != null && folder.profiles.size == 1 && folder.profiles.single().profile.configType in setOf(
                            com.v2ray.ang.enums.EConfigType.VLESS, com.v2ray.ang.enums.EConfigType.VMESS,
                            com.v2ray.ang.enums.EConfigType.TROJAN, com.v2ray.ang.enums.EConfigType.SHADOWSOCKS,
                            com.v2ray.ang.enums.EConfigType.HYSTERIA2) && password.isEmpty() && message.isEmpty())
                        AngConfigManager.shareConfig(guid).takeIf { it.isNotBlank() }
                            ?: SpicyShareCodec.encode(folder)
                    else if (guid == null) SpicyShareCodec.encodeConnectOnly(folder.copy(message = message), password)
                    else SpicyShareCodec.encode(folder.copy(message = message), password)
                }
                if (asQr) {
                    qr = withContext(Dispatchers.Default) { QRCodeDecoder.createQRCode(link) }
                    require(qr != null) { "This configuration is too large for a QR code. Share the link instead." }
                } else context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link)
                }, "Share"))
            }.onFailure { error = it.message ?: "Unable to share" }
            busy = false
        }
    }
    qr?.let { bitmap ->
        AlertDialog(onDismissRequest = { qr = null }, title = { Text("QR code") },
            text = { Image(bitmap.asImageBitmap(), "QR code", Modifier.size(240.dp)) },
            confirmButton = { TextButton(onClick = { qr = null }) { Text("Close") } })
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(folder.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (guid == null) {
                OutlinedTextField(password, { password = it }, label = { Text("Editing password (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(message, { if (it.codePointCount(0, it.length) <= 500) message = it }, label = { Text("Message (500 characters)") }, minLines = 3)
            }
            TextButton(enabled = !busy, onClick = { share(false) }) { Text("Share link") }
            TextButton(enabled = !busy, onClick = { share(true) }) { Text("QR code") }
            if (guid != null) TextButton(onClick = {
                val profile = MmkvManager.decodeServerConfig(guid)
                val full = profile?.nativeConfig?.takeIf { it.isNotBlank() } ?: MmkvManager.decodeServerRaw(guid)?.takeIf { it.isNotBlank() }
                if (full != null) Utils.setClipboard(context, full)
                else AngConfigManager.shareFullContent2Clipboard(context, guid)
            }) { Text("Copy full configuration") }
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
