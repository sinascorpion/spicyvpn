package com.example.spicyvpn

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.spicyvpn.ui.main.MainScreen
import com.example.spicyvpn.ui.main.MainScreenViewModel
import com.example.spicyvpn.ui.main.LanguageSelectionScreen
import com.example.spicyvpn.ui.main.RoutingSettingsScreen
import com.example.spicyvpn.ui.main.DnsSettingsScreen
import com.example.spicyvpn.ui.main.VpnTetheringScreen

@Composable
fun MainNavigation(
  startDestination: String,
  isDarkMode: Boolean,
  onThemeToggle: () -> Unit,
  onLanguageChanged: () -> Unit,
  isRunning: Boolean,
  serverDelay: String,
  onConnectClick: (Int) -> Unit,
  onStartDnsOnly: () -> Unit,
  viewModel: MainScreenViewModel
) {
  val navController = rememberNavController()

  NavHost(navController = navController, startDestination = startDestination) {
    composable(Screen.LanguageSelection.route) {
      LanguageSelectionScreen(onLanguageSelected = {
        navController.navigate(Screen.Main.route) {
            popUpTo(Screen.LanguageSelection.route) { inclusive = true }
        }
        onLanguageChanged()
      })
    }
    composable(Screen.Main.route) {
      MainScreen(
        isDarkMode = isDarkMode,
        onThemeToggle = onThemeToggle,
        onItemClick = { route -> navController.navigate(route) },
        isRunning = isRunning,
        serverDelay = serverDelay,
        onConnectClick = onConnectClick,
        viewModel = viewModel,
        modifier = Modifier.safeDrawingPadding()
      )
    }
    composable(Screen.CountryRouting.route) {
      RoutingSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable("dns_settings") {
      DnsSettingsScreen(
        onBack = { navController.popBackStack() },
        onStartDnsOnly = onStartDnsOnly
      )
    }
    composable("vpn_tethering") {
      VpnTetheringScreen(isRunning = isRunning, onBack = { navController.popBackStack() })
    }
  }
}
