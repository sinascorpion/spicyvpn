package com.example.spicyvpn.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.extension.toSpeedString
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R

@Composable
fun TrafficSpeedRow(viewModel: MainScreenViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(Triple(stringResource(R.string.custom_download), viewModel.downloadBytesPerSecond, true),
            Triple(stringResource(R.string.custom_upload), viewModel.uploadBytesPerSecond, false)).forEach { (label, speed, download) ->
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                color = if (download) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (download) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(speed.toSpeedString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            }
        }
    }
}
