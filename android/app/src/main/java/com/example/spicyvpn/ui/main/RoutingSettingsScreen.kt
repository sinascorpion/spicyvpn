package com.example.spicyvpn.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import com.v2ray.ang.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.handler.MmkvManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSettingsScreen(onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var bypassIran by remember { mutableStateOf(isCountryBypassed("ir")) }
    var bypassRussia by remember { mutableStateOf(isCountryBypassed("ru")) }
    var bypassChina by remember { mutableStateOf(isCountryBypassed("cn")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.country_routing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.country_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.country_bypass_description), color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(16.dp))
            
            CountryToggle(java.util.Locale("", "IR").getDisplayCountry(locale), bypassIran) { checked ->
                bypassIran = checked
                setCountryBypass("ir", checked)
            }
            CountryToggle(java.util.Locale("", "RU").getDisplayCountry(locale), bypassRussia) { checked ->
                bypassRussia = checked
                setCountryBypass("ru", checked)
            }
            CountryToggle(java.util.Locale("", "CN").getDisplayCountry(locale), bypassChina) { checked ->
                bypassChina = checked
                setCountryBypass("cn", checked)
            }
        }
    }
}

@Composable
fun CountryToggle(name: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(name, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun isCountryBypassed(countryCode: String): Boolean {
    val rulesets = MmkvManager.decodeRoutingRulesets() ?: return false
    return rulesets.any { it.remarks == "Bypass $countryCode" && it.enabled }
}

private fun setCountryBypass(countryCode: String, enable: Boolean) {
    var rulesets = MmkvManager.decodeRoutingRulesets()
    if (rulesets == null) {
        rulesets = mutableListOf()
    }
    
    // Remove existing rule for this country
    rulesets.removeAll { it.remarks == "Bypass $countryCode" }
    
    if (enable) {
        val domainList = when (countryCode.lowercase()) {
            "cn" -> listOf("geosite:cn")
            "ir" -> listOf("geosite:category-ir")
            "ru" -> listOf("geosite:category-ru")
            else -> listOf("geosite:category-$countryCode")
        }
        val rule = RulesetItem(
            remarks = "Bypass $countryCode",
            ip = listOf("geoip:$countryCode"),
            domain = domainList,
            outboundTag = "direct",
            enabled = true
        )
        rulesets.add(0, rule) // Add at top priority
    }
    
    MmkvManager.encodeRoutingRulesets(rulesets)
}
