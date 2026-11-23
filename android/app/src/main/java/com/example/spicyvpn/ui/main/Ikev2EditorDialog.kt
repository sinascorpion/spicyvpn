package com.example.spicyvpn.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.Ikev2Fmt

@Composable
fun Ikev2EditorDialog(profile: ProfileItem?, onSave: (String, String) -> Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profile?.remarks ?: "IKEv2") }
    var host by remember { mutableStateOf(profile?.server.orEmpty()) }
    var user by remember { mutableStateOf(profile?.username.orEmpty()) }
    var password by remember { mutableStateOf(profile?.password.orEmpty()) }
    var identity by remember { mutableStateOf(profile?.sni.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("IKEv2") }, text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(identity, { identity = it }, label = { Text("Identity (optional)") }, singleLine = true)
            if (error) Text("Check the host, username and password", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = host.isNotBlank() && user.isNotBlank() && password.isNotBlank(), onClick = {
        val value = ProfileItem(configType = EConfigType.IKEV2, remarks = name.trim(), server = host.trim(),
            username = user.trim(), password = password, sni = identity.trim().ifBlank { user.trim() })
        val link = "ikev2://" + Ikev2Fmt.toUri(value)
        error = !runCatching { Ikev2Fmt.parse(link); onSave(name.trim(), link) }.getOrDefault(false)
        if (!error) onDismiss()
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
