package com.example.spicyvpn.ui.main

import com.v2ray.ang.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

@Composable
fun LanguageSelectionScreen(onLanguageSelected: (String) -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("SpicyVPN_Prefs", Context.MODE_PRIVATE)
    
    val languages = listOf("English", "فارسی", "Русский", "中文")
    val codes = listOf("en", "fa", "ru", "zh")
    
    var selectedLanguageIndex by remember { mutableIntStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(id = R.string.select_language),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Column(Modifier.selectableGroup()) {
                languages.forEachIndexed { index, language ->
                    ListItem(
                        headlineContent = { Text(language) },
                        leadingContent = {
                            RadioButton(
                                selected = (index == selectedLanguageIndex),
                                onClick = null 
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (index == selectedLanguageIndex),
                                onClick = { selectedLanguageIndex = index },
                                role = Role.RadioButton
                            )
                    )
                }
            }

            Button(
                onClick = { 
                    val selectedCode = codes[selectedLanguageIndex]
                    sharedPrefs.edit().putString("selected_language", selectedCode).apply()
                    MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, selectedCode)
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedCode))
                    onLanguageSelected(selectedCode) 
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp)
            ) {
                Text(stringResource(id = R.string.continue_btn))
            }
        }
    }
}