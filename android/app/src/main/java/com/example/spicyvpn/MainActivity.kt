package com.example.spicyvpn

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Bundle
import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.spicyvpn.theme.SpicyVPNTheme
import com.example.spicyvpn.ui.main.MainScreenViewModel
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.AdRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val screenViewModel: MainScreenViewModel by viewModels()
    private var mInterstitialAd: InterstitialAd? = null
    private var isShowingAd = false
    private var adLoadedAt = 0L
    private var targetTabIndex = 0
    private var suppressInterstitialForNextConnection = false
    private var pendingDnsOnlyPermission = false
    private var pendingProfilePermission: String? = null
    private val requestIkev2Permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) com.v2ray.ang.tunnel.Ikev2PlatformController.start(this)
    }
    private val dnsOnlyProfileGuid = "spicyvpn-dns-only"
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            if (pendingDnsOnlyPermission) {
                pendingDnsOnlyPermission = false
                startDnsOnlyProfile()
            } else if (pendingProfilePermission != null) {
                val guid = pendingProfilePermission
                pendingProfilePermission = null
                CoreServiceManager.startVService(this, guid)
            } else {
                startV2Ray(targetTabIndex)
            }
        } else if (pendingDnsOnlyPermission) {
            pendingDnsOnlyPermission = false
            MmkvManager.encodeSettings(AppConfig.PREF_DNS_ONLY_MODE, false)
        }
        if (it.resultCode != Activity.RESULT_OK) pendingProfilePermission = null
    }

    override fun attachBaseContext(newBase: Context) {
        val sharedPrefs = newBase.getSharedPreferences("SpicyVPN_Prefs", Context.MODE_PRIVATE)
        val lang = sharedPrefs.getString("selected_language", "") ?: ""
        if (lang.isNotEmpty()) {
            val locale = Locale.forLanguageTag(lang)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            val context = newBase.createConfigurationContext(config)
            super.attachBaseContext(context)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedLink(intent)
        val sharedPrefs = getSharedPreferences("SpicyVPN_Prefs", Context.MODE_PRIVATE)
        val startDestination = if (sharedPrefs.contains("selected_language")) Screen.Main.route else Screen.LanguageSelection.route
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED && !sharedPrefs.getBoolean("notification_permission_asked", false)) {
            sharedPrefs.edit().putBoolean("notification_permission_asked", true).apply()
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        enableEdgeToEdge()
        MobileAds.initialize(this) {}
        mainViewModel.startListenBroadcast()
        mainViewModel.trafficSpeed.observe(this) { values ->
            if (mainViewModel.isRunning.value == true) screenViewModel.updateTrafficSpeed(values[0], values[1])
        }


        // Migrate old default DNS (1.1.1.1) to Google DNS (8.8.8.8) to resolve connection blocks
        val currentVpnDns = com.v2ray.ang.handler.MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_VPN_DNS)
        if (currentVpnDns == null || currentVpnDns == "1.1.1.1") {
            com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_VPN_DNS, "8.8.8.8,8.8.4.4")
        }
        val currentRemoteDns = com.v2ray.ang.handler.MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_REMOTE_DNS)
        if (currentRemoteDns == null || currentRemoteDns == "1.1.1.1" || currentRemoteDns.contains("https+local://")) {
            com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_REMOTE_DNS, "8.8.8.8,8.8.4.4,https://dns.google/dns-query")
        }

        // Purge any stale legacy custom servers from MMKV to prevent unwanted connections
        try {
            val allServers = MmkvManager.decodeAllServerList()
            for (guid in allServers) {
                val cfg = MmkvManager.decodeServerConfig(guid)
                if (cfg != null && (false || false)) {
                    MmkvManager.removeServer(guid)
                    if (MmkvManager.getSelectServer() == guid) {
                        MmkvManager.setSelectServer("")
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        mainViewModel.updateTestResultAction.observe(this) { result ->
            if (!result.isNullOrEmpty() && result.contains("\n")) {
                val ipLine = result.substringAfter("\n").trim()
                val match = Regex("\\((.*?)\\)\\s*(.*)").find(ipLine)
                if (match != null) {
                    val country = match.groupValues[1].uppercase().trim()
                    val ip = match.groupValues[2].trim()
                    if (ip.isNotEmpty() && ip != "unknown") {
                        screenViewModel.updateExitIpAndCountry(ip, country)
                    }
                }
            }
        }

        var wasRunning = false
        mainViewModel.isRunning.observe(this) { running ->
            screenViewModel.updateSelectedServerGuid()
            if (running && !wasRunning) {
                // VPN just connected
                val dnsOnlyMode = MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)
                val internalRestart = MmkvManager.decodeSettingsBool("pref_suppress_next_connection_ad")
                MmkvManager.encodeSettings("pref_suppress_next_connection_ad", false)
                if (!dnsOnlyMode && !suppressInterstitialForNextConnection && !internalRestart) {
                    connectionAdJob?.cancel()
                    connectionAdPending = true
                    connectionAdDelayElapsed = false
                    loadInterstitialAd()
                    connectionAdJob = lifecycleScope.launch {
                        // A short presentation delay, not a means of bypassing ad policies.
                        delay(kotlin.random.Random.nextLong(3000L, 7001L))
                        connectionAdDelayElapsed = true
                        tryShowAd()
                        delay(5000L)
                        connectionAdPending = false
                    }
                }
                suppressInterstitialForNextConnection = false
                if (!dnsOnlyMode) {
                    screenViewModel.startBackgroundPing(this)
                    screenViewModel.fetchRealExitIp()
                }
            } else if (!running && wasRunning) {
                connectionAdJob?.cancel()
                connectionAdPending = false
                connectionAdDelayElapsed = false
                adRetryJob?.cancel()
                mInterstitialAd = null
                // VPN just stopped - check if switching server manually
                screenViewModel.stopBackgroundPing()
                screenViewModel.clearRealExitIp()
                val pendingGuid = screenViewModel.consumePendingNodeGuid()
                if (pendingGuid != null) {
                    // A server handoff is a continuation of the same session, not a new
                    // user connection. Do not show a second full-screen ad for it.
                    suppressInterstitialForNextConnection = true
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(1000)
                        CoreServiceManager.startVService(this@MainActivity, pendingGuid)
                    }
                }
            } else if (!running) {
                screenViewModel.stopBackgroundPing()
                screenViewModel.clearRealExitIp()
            }
            wasRunning = running
            if (!running) screenViewModel.updateTrafficSpeed(0, 0)
        }

        setContent {
            var isDarkMode by remember {
                mutableStateOf(sharedPrefs.getBoolean("dark_theme", true))
            }
            val isRunning by mainViewModel.isRunning.observeAsState(false)

            SpicyVPNTheme(darkTheme = isDarkMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainNavigation(
                        startDestination = startDestination,
                        isDarkMode = isDarkMode,
                        onThemeToggle = {
                            isDarkMode = !isDarkMode
                            sharedPrefs.edit().putBoolean("dark_theme", isDarkMode).apply()
                        },
                        onLanguageChanged = { recreate() },
                        isRunning = isRunning,
                        serverDelay = "",
                        viewModel = screenViewModel,
                        onConnectClick = { tabIndex ->
                            if (isRunning) {
                                CoreServiceManager.stopVService(this)
                            } else {
                                MmkvManager.encodeSettings(AppConfig.PREF_DNS_ONLY_MODE, false)
                                val intent = VpnService.prepare(this)
                                if (intent == null) {
                                    startV2Ray(tabIndex)
                                } else {
                                    targetTabIndex = tabIndex
                                    requestVpnPermission.launch(intent)
                                }
                            }
                        },
                        onStartDnsOnly = { requestDnsOnlyMode() }
                    )
                }
            }
        }
    }

    private fun requestDnsOnlyMode() {
        if (CoreServiceManager.isRunning()) {
            CoreServiceManager.stopVService(this)
            lifecycleScope.launch {
                var retries = 0
                while (CoreServiceManager.isRunning() && retries < 20) {
                    delay(250)
                    retries++
                }
                startDnsOnlyProfile()
            }
        } else {
            startDnsOnlyProfile()
        }
    }

    private fun startDnsOnlyProfile() {
        val dnsServers = com.v2ray.ang.handler.SettingsManager.getVpnDnsServers()
            .filter { com.v2ray.ang.util.Utils.isPureIpAddress(it) }
        if (dnsServers.isEmpty()) {
            Toast.makeText(this, "Choose at least one IP-based DNS resolver first.", Toast.LENGTH_LONG).show()
            return
        }

        val profile = ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = "SpicyVPN DNS-only"
            server = "127.0.0.1"
            serverPort = "53"
        }
        MmkvManager.encodeProfileDirect(dnsOnlyProfileGuid, JsonUtil.toJson(profile))
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_ONLY_MODE, true)
        MmkvManager.setSelectServer(dnsOnlyProfileGuid)

        val intent = VpnService.prepare(this)
        if (intent == null) {
            CoreServiceManager.startVService(this, dnsOnlyProfileGuid)
        } else {
            pendingDnsOnlyPermission = true
            requestVpnPermission.launch(intent)
        }
    }

    fun startVServiceSafely(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid)
        if (profile?.configType == com.v2ray.ang.enums.EConfigType.IKEV2 && MmkvManager.decodeSettingsString("pref_s_standalone") !in listOf("tor", "psiphon")) {
            if (!com.v2ray.ang.tunnel.Ikev2PlatformController.available(this)) {
                android.widget.Toast.makeText(this, "IKEv2 requires Android 11 or later with IPsec support", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)) {
                android.widget.Toast.makeText(this, "Platform IKEv2 cannot apply per-app split tunneling. Turn it off before connecting.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            runCatching {
                MmkvManager.setSelectServer(guid)
                screenViewModel.updateSelectedServerGuid()
                val consent = com.v2ray.ang.tunnel.Ikev2PlatformController.prepare(this, profile)
                if (consent != null) requestIkev2Permission.launch(consent)
                else com.v2ray.ang.tunnel.Ikev2PlatformController.start(this)
            }.onFailure { android.widget.Toast.makeText(this, it.message, android.widget.Toast.LENGTH_LONG).show() }
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) {
            CoreServiceManager.startVService(this, guid)
        } else {
            MmkvManager.setSelectServer(guid)
            screenViewModel.updateSelectedServerGuid()
            pendingProfilePermission = guid
            targetTabIndex = 2
            requestVpnPermission.launch(intent)
        }
    }

    private fun startV2Ray(tabIndex: Int) {
        if (MmkvManager.decodeSettingsString("pref_s_standalone") in listOf("tor", "psiphon")) {
            screenViewModel.cancelAutoSelection()
            if (mainViewModel.isRunning.value == true) CoreServiceManager.stopVService(this)
            else CoreServiceManager.startVService(this)
            return
        }
        if (tabIndex == 0) {
            // 1. Try ViewModel auto-connect first (fastest, uses pre-tested servers)
            val isRunning = mainViewModel.isRunning.value ?: false
            screenViewModel.connectToAutoServer(this, isRunning)
            return
        } else if (tabIndex == 2) {
            // Custom connection flow (tabIndex == 2)
            val customNodes = screenViewModel.customProxies.value
            if (customNodes.isNotEmpty()) {
                val selectUrl = MmkvManager.decodeSettingsString("pref_selected_server_url")
                val activeNode = if (!selectUrl.isNullOrEmpty()) {
                    customNodes.find { it.url == selectUrl }
                } else {
                    null
                }
                val targetNode = activeNode ?: customNodes.firstOrNull { it.ping > 0 } ?: customNodes.first()
                val isRunning = mainViewModel.isRunning.value ?: false
                screenViewModel.connectToCustomNode(this, targetNode, isRunning)
                return
            } else {
                Toast.makeText(this, getString(com.v2ray.ang.R.string.no_custom_configs), Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            // Manual connection flow (tabIndex == 1)
            // 1. If a static (starred) server is pinned, connect to it directly
            if (screenViewModel.connectToStaticServer(this)) {
                return
            }

            // 2. Otherwise connect to the selected server or best manual candidate
            val nodes = screenViewModel.manualProxies.value
            if (nodes.isNotEmpty()) {
                val selectUrl = MmkvManager.decodeSettingsString("pref_selected_server_url")
                val activeNode = if (!selectUrl.isNullOrEmpty()) {
                    nodes.find { it.url == selectUrl }
                } else {
                    val selectGuid = MmkvManager.getSelectServer()
                    val activeConfig = selectGuid?.let { MmkvManager.decodeServerConfig(it) }
                    nodes.find { it.url.contains(activeConfig?.server ?: "") || it.name == activeConfig?.remarks }
                }
                
                val targetNode = activeNode 
                    ?: nodes.firstOrNull { screenViewModel.isStarred(it.url) } 
                    ?: nodes.firstOrNull { it.ping > 0 } 
                    ?: nodes.first()
                val isRunning = mainViewModel.isRunning.value ?: false
                screenViewModel.connectToNode(this, targetNode, isRunning)
                return
            }
            return
        }

        // 3. Fallback only for Auto/Manual if there is a verified valid server
        val selectedServer = MmkvManager.getSelectServer()
        if (!selectedServer.isNullOrEmpty()) {
            val cfg = MmkvManager.decodeServerConfig(selectedServer)
            if (cfg != null && tabIndex != 2) {
                CoreServiceManager.startVService(this)
                return
            }
        }

        // 4. Fallback: load from subscription
        var subs = MmkvManager.decodeSubscriptions()

        // Force migrate old subscriptions to the new Vercel endpoint
        subs.forEach { sub ->
            if (sub.subscription.remarks == "SpicyVPN" && !sub.subscription.url.contains("/api/sub")) {
                val updatedSub = sub.subscription.copy(url = "${com.v2ray.ang.AppConfig.API_BASE_URL}/api/sub")
                MmkvManager.encodeSubscription(sub.guid, updatedSub)
            }
        }
        subs = MmkvManager.decodeSubscriptions()

        if (subs.isEmpty()) {
            // Create the CDN-based subscription and track its guid
            val subId = java.util.UUID.randomUUID().toString()
            val subItem = com.v2ray.ang.dto.entities.SubscriptionItem(
                remarks = "SpicyVPN",
                url = "${com.v2ray.ang.AppConfig.API_BASE_URL}/api/sub",
                enabled = true,
                autoUpdate = true
            )
            MmkvManager.encodeSubscription(subId, subItem)
            subs = MmkvManager.decodeSubscriptions()
        }
        val subGuid = subs.firstOrNull()?.guid ?: ""

        // Fetch subscription in background
        CoroutineScope(Dispatchers.IO).launch {
            mainViewModel.updateConfigViaSubAll()
            var servers = MmkvManager.decodeAllServerList()

            // If still empty, try the fallback hardcoded server
            if (servers.isEmpty()) {
                val fallback = "vless://b46689dd-433c-4eed-aaac-b7237ffc3ae9@151.101.3.8:80?type=ws&host=befreewithus.net&path=/&security=none#SpicyVPN"
                AngConfigManager.importBatchConfig(fallback, subGuid, false)
                servers = MmkvManager.decodeAllServerList()
            }

            if (servers.isNotEmpty()) {
                MmkvManager.setSelectServer(servers[0])
                launch(Dispatchers.Main) {
                    CoreServiceManager.startVService(this@MainActivity)
                }
            } else {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not load servers. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private var isAdLoading = false
    private var connectionAdJob: kotlinx.coroutines.Job? = null
    private var connectionAdPending = false
    private var connectionAdDelayElapsed = false
    private var adRetryJob: kotlinx.coroutines.Job? = null
    private var adRetryDelayMs = 30000L

    private fun adsCanUseTunnel(): Boolean = com.example.spicyvpn.ads.AdTrafficPolicy.canRequest(
        mainViewModel.isRunning.value == true,
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_ONLY_MODE)
    )

    private fun handleSharedLink(source: android.content.Intent?) {
        source?.getStringExtra("ikev2_guid")?.let { startVServiceSafely(it); source.removeExtra("ikev2_guid") }
        val link = source?.dataString ?: return
        if (link.startsWith(com.example.spicyvpn.ui.main.SpicyShareCodec.PREFIX)) screenViewModel.pendingSharedImport.value = link
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedLink(intent)
    }

    override fun onResume() {
        super.onResume()
        screenViewModel.refreshServerListOnResume()
        loadInterstitialAd()
    }

    private fun loadInterstitialAd() {
        if (!adsCanUseTunnel()) return
        if (isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        if (isShowingAd) return
        if (runCatching { com.example.spicyvpn.ads.AdFrequencyPolicy.active(System.currentTimeMillis(), adImpressions()).size >= 3 }
                .getOrDefault(true)) return
        if (mInterstitialAd != null && android.os.SystemClock.elapsedRealtime() - adLoadedAt >= 3600000L) mInterstitialAd = null
        if (isAdLoading || mInterstitialAd != null) return
        adRetryJob?.cancel()
        adRetryJob = null
        isAdLoading = true
        val adRequest = AdRequest.Builder().build()
        val adUnitId = if (com.v2ray.ang.BuildConfig.DEBUG) {
            "ca-app-pub-3940256099942544/1033173712"
        } else {
            "ca-app-pub-5779811685096109/3855761740"
        }
        InterstitialAd.load(
            this,
            adUnitId,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    mInterstitialAd = null
                    isAdLoading = false
                    com.v2ray.ang.util.LogUtil.w("SpicyVPN_Ads", "Interstitial ad failed to load: ${adError.message}")
                    if (adError.code != AdRequest.ERROR_CODE_INVALID_REQUEST) {
                        val retryDelay = adRetryDelayMs
                        adRetryDelayMs = (adRetryDelayMs * 2).coerceAtMost(300000L)
                        adRetryJob = lifecycleScope.launch {
                            kotlinx.coroutines.delay(retryDelay)
                            adRetryJob = null
                            loadInterstitialAd()
                        }
                    }
                }
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    if (!adsCanUseTunnel()) {
                        isAdLoading = false
                        mInterstitialAd = null
                        return
                    }
                    if (isFinishing || isDestroyed) return
                    mInterstitialAd = interstitialAd
                    adLoadedAt = android.os.SystemClock.elapsedRealtime()
                    isAdLoading = false
                    adRetryDelayMs = 30000L
                    com.v2ray.ang.util.LogUtil.i("SpicyVPN_Ads", "Interstitial ad loaded successfully")
                    
                    var impressionRecorded = false
                    mInterstitialAd?.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                        override fun onAdImpression() {
                            if (!impressionRecorded) {
                                impressionRecorded = true
                                recordAdImpression()
                            }
                        }
                        override fun onAdDismissedFullScreenContent() {
                            isShowingAd = false
                            mInterstitialAd = null
                            loadInterstitialAd()
                        }
                        override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                            isShowingAd = false
                            mInterstitialAd = null
                            loadInterstitialAd()
                        }
                    }
                    if (connectionAdPending && connectionAdDelayElapsed) tryShowAd()
                }
            }
        )
    }

    private fun tryShowAd() {
        if (!adsCanUseTunnel()) return
        if (!connectionAdPending || !connectionAdDelayElapsed) return
        try {
            if (isFinishing || isDestroyed || isShowingAd || mainViewModel.isRunning.value != true ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
            val now = System.currentTimeMillis()
            if (com.example.spicyvpn.ads.AdFrequencyPolicy.canShow(now, adImpressions())) {
                if (mInterstitialAd != null) {
                    connectionAdPending = false
                    isShowingAd = true
                    mInterstitialAd?.show(this)
                } else {
                    loadInterstitialAd()
                }
            }
        } catch (e: Exception) {
            isShowingAd = false
            com.v2ray.ang.util.LogUtil.e("SpicyVPN_Ads", "Failed to show interstitial ad", e)
        }
    }

    private fun adImpressions(): List<Long> {
        val prefs = getSharedPreferences("SpicyVPN_Ads", Context.MODE_PRIVATE)
        val saved = prefs.getString("impression_times", null)
        if (saved != null) {
            val array = org.json.JSONArray(saved)
            return (0 until array.length()).map { array.getLong(it) }
        }
        val last = prefs.getLong("last_ad_timestamp", 0)
        return if (last > 0) List(prefs.getInt("ad_count", 1).coerceIn(1, 3)) { last } else emptyList()
    }

    private fun recordAdImpression() {
        val now = System.currentTimeMillis()
        val previous = runCatching { adImpressions() }.getOrDefault(List(3) { now })
        val history = com.example.spicyvpn.ads.AdFrequencyPolicy.active(now, previous) + now
        getSharedPreferences("SpicyVPN_Ads", Context.MODE_PRIVATE).edit()
            .putString("impression_times", org.json.JSONArray(history).toString())
            .putLong("last_ad_timestamp", now).apply()
    }
}
