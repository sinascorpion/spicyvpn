package com.example.spicyvpn

sealed class Screen(val route: String) {
    object LanguageSelection : Screen("language_selection")
    object Main : Screen("main")
    object CountryRouting : Screen("country_routing")
}
