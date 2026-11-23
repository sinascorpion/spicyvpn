package com.example.spicyvpn.ui.main

import com.v2ray.ang.util.JsonUtil

import android.content.Context
import com.example.spicyvpn.MainActivity
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URI
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.dto.entities.SubscriptionItem
import java.net.InetSocketAddress
import java.net.Socket

class MainScreenViewModel : ViewModel() {
    var downloadBytesPerSecond by mutableStateOf(0L)
        private set
    var uploadBytesPerSecond by mutableStateOf(0L)
        private set

    fun updateTrafficSpeed(up: Long, down: Long) {
        uploadBytesPerSecond = up.coerceAtLeast(0)
        downloadBytesPerSecond = down.coerceAtLeast(0)
    }

    companion object {
        private const val TAG = "SpicyVPN"
        private const val SPICYVPN_SUB_ID = "spicyvpn-default"
        private const val SPICYVPN_AUTO_SUB_ID = "spicyvpn-auto-candidates"
        private const val SPICYVPN_CUSTOM_SUB_ID = "spicyvpn-custom-user"

        // Primary API URL for healthy proxies list
        private const val HEALTHY_PROXIES_PRIMARY =
            "${com.v2ray.ang.AppConfig.API_BASE_URL}/api/healthy-proxies"

        private const val PREFS_NAME = "SpicyVPN_Prefs"
        private const val KEY_STATIC_ENABLED = "static_ip_enabled"
        private const val KEY_STATIC_SERVER_URL = "static_server_url"
        private const val KEY_STARRED_SERVERS = "starred_servers"
        private const val KEY_CUSTOM_IMPORTED_SUBSCRIPTIONS = "custom_imported_subscriptions"
        private const val KEY_CUSTOM_LINKED_SUBSCRIPTIONS = "custom_linked_subscriptions"
        private const val KEY_CUSTOM_PROXIES = "custom_proxies_list"

        /** How often the background ping loop re-tests candidate servers (ms). */
        private const val PING_INTERVAL_MS = 60_000L

        /** Manual probing is deliberately slower so it never competes with normal use. */
        private const val MANUAL_PROBE_INTERVAL_MS = 5 * 60_000L
        private const val MANUAL_VISIBLE_PROBE_COUNT = 3
        private const val MANUAL_RESERVE_PROBE_COUNT = 2
        private const val AUTO_RESERVE_PROBE_COUNT = 2

        /** Allow a new tunnel to complete its handshake before evaluating it. */
        private const val INITIAL_HEALTH_CHECK_DELAY_MS = 45_000L
    }

    private val _autoConnectProxies = MutableStateFlow<List<ProxyNode>>(emptyList())
    val autoConnectProxies: StateFlow<List<ProxyNode>> = _autoConnectProxies.asStateFlow()

    private val _manualProxies = MutableStateFlow<List<ProxyNode>>(emptyList())
    val manualProxies: StateFlow<List<ProxyNode>> = _manualProxies.asStateFlow()

    private val _customProxies = MutableStateFlow<List<ProxyNode>>(emptyList())
    val customProxies: StateFlow<List<ProxyNode>> = _customProxies.asStateFlow()

    // These candidates come from the same healthy-proxy response but are kept out of
    // the visible lists until the current device has measured them successfully.
    private var manualReserveProxies: List<ProxyNode> = emptyList()
    private var autoReserveProxies: List<ProxyNode> = emptyList()
    private var pinnedManualProxies: List<ProxyNode> = emptyList()
    private var manualVisibleProbeCursor = 0
    private var manualReserveProbeCursor = 0
    private var autoReserveProbeCursor = 0
    private var lastManualProbeAtMs = 0L
    private var hasManualPingTest = false

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _starredServers = MutableStateFlow<Set<String>>(emptySet())
    val starredServers: StateFlow<Set<String>> = _starredServers.asStateFlow()

    private val _isTestingManual = MutableStateFlow(false)
    val isTestingManual: StateFlow<Boolean> = _isTestingManual.asStateFlow()

    private val _isTestingCustom = MutableStateFlow(false)
    val isTestingCustom: StateFlow<Boolean> = _isTestingCustom.asStateFlow()

    private val _isImportingCustom = MutableStateFlow(false)
    val isImportingCustom: StateFlow<Boolean> = _isImportingCustom.asStateFlow()

    private val _selectedServerGuid = MutableStateFlow<String?>(null)
    val selectedServerGuid: StateFlow<String?> = _selectedServerGuid.asStateFlow()

    fun updateSelectedServerGuid() {
        _selectedServerGuid.value = MmkvManager.getSelectServer()
    }

    private val _pendingNodeGuid = MutableStateFlow<String?>(null)
    val pendingNodeGuid: StateFlow<String?> = _pendingNodeGuid.asStateFlow()

    fun consumePendingNodeGuid(): String? {
        val guid = _pendingNodeGuid.value
        _pendingNodeGuid.value = null
        return guid
    }


    val showAnnouncementDialog = mutableStateOf(false)
    val announcementTitle = mutableStateOf("")
    val announcementMessage = mutableStateOf("")
    val announcementActionUrl = mutableStateOf("")
    var activeAnnouncementId = ""

    fun checkForAnnouncement(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "${com.v2ray.ang.AppConfig.API_BASE_URL}/api/announcement"
                val response = com.v2ray.ang.util.HttpUtil.getUrlContent(
                    com.v2ray.ang.dto.UrlContentRequest(url = url, timeout = 5000)
                ) ?: return@launch
                
                val json = JSONObject(response)
                val id = json.optString("id", "")
                val title = json.optString("title", "")
                val message = json.optString("message", "")
                val actionUrl = json.optString("actionUrl", "")
                val isActive = json.optBoolean("isActive", false)

                if (isActive && id.isNotEmpty() && title.isNotEmpty() && message.isNotEmpty()) {
                    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val lastSeenId = sharedPrefs.getString("last_seen_announcement_id", "")
                    
                    if (id != lastSeenId) {
                        withContext(Dispatchers.Main) {
                            activeAnnouncementId = id
                            announcementTitle.value = title
                            announcementMessage.value = message
                            announcementActionUrl.value = actionUrl
                            showAnnouncementDialog.value = true
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "checkForAnnouncement failed", e)
            }
        }
    }

    fun dismissAnnouncement(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            showAnnouncementDialog.value = false
            withContext(Dispatchers.IO) {
                val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sharedPrefs.edit()
                    .putString("last_seen_announcement_id", activeAnnouncementId)
                    .apply()
            }
        }
    }

    private var pingJob: Job? = null
    private var autoSelectionJob: Job? = null
    private val _isFindingAuto = MutableStateFlow(false)
    val isFindingAuto: StateFlow<Boolean> = _isFindingAuto.asStateFlow()
    private var appContext: Context? = null
    private var autoConnectionStartedAtMs = 0L

    init {
        fetchProxies()
    }

    fun initContext(context: Context) {
        appContext = context.applicationContext
        loadStarredServers(context)
        val cachedManual = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("manual_test_results", null)?.let {
                JsonUtil.fromJsonSafe(it, Array<ProxyNode>::class.java)?.toList()
            }.orEmpty()
        if (cachedManual.isNotEmpty()) {
            _manualProxies.value = cachedManual
            hasManualPingTest = true
        }
        loadCustomProxies(context)
        updateSelectedServerGuid()
        checkForAnnouncement(context)
    }

    // --------------------------------------------------------------- data fetch

    private fun fetchProxies() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    com.v2ray.ang.util.HttpUtil.getUrlContent(
                        com.v2ray.ang.dto.UrlContentRequest(url = HEALTHY_PROXIES_PRIMARY)
                    ) ?: throw Exception("Failed to fetch proxies from GitHub API")
                }
                val jsonObj = JSONObject(jsonString)
                val repoTestedAt = jsonObj.optLong("tested_at", 0)
                val manualPrefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val newerRepositoryTest = repoTestedAt > (manualPrefs?.getLong("manual_repo_tested_at", 0) ?: 0)

                // manual: map country → list of {url, ping}
                val manualObj = jsonObj.getJSONObject("manual")
                val manualMap = mutableMapOf<String, List<ProxyNode>>()
                val flatManualList = mutableListOf<ProxyNode>()
                for (key in manualObj.keys()) {
                    val countryArray = manualObj.getJSONArray(key)
                    val nodeList = mutableListOf<ProxyNode>()
                    val countryName = getCountryName(key)
                    for (i in 0 until countryArray.length()) {
                        val nodeObj = countryArray.getJSONObject(i)
                        val url = nodeObj.getString("url")
                        val ping = nodeObj.optInt("ping", 0)
                        
                        // Subscription remarks are often identical. Keep each Manual
                        // entry visibly distinct, even when every URI says "SpicyVPN".
                        val displayName = "Server${flatManualList.size + 1}"
                        
                        val node = ProxyNode(
                            url = url,
                            ping = ping,
                            name = displayName,
                            country = if (nodeObj.optBoolean("country_verified", false)) key else ""
                        )
                        nodeList.add(node)
                        flatManualList.add(node)
                    }
                    manualMap[key] = nodeList
                }
                
                val pinnedNodes = mutableListOf<ProxyNode>()
                val pinnedArray = jsonObj.optJSONArray("pinned_servers")
                if (pinnedArray != null) {
                    for (i in 0 until pinnedArray.length()) {
                        val item = pinnedArray.optJSONObject(i) ?: continue
                        val url = item.optString("url").trim()
                        if (url.isNotEmpty() && pinnedNodes.none { it.url == url }) {
                            val metadata = jsonObj.optJSONObject("exit_metadata")?.optJSONObject(url)
                            val verifiedCountry = if (metadata?.optBoolean("country_verified", false) == true)
                                metadata.optString("country") else ""
                            pinnedNodes.add(ProxyNode(url = url, ping = 0, name = "", country = verifiedCountry))
                        }
                    }
                }
                pinnedManualProxies = pinnedNodes.take(50)
                val pinnedUrls = pinnedManualProxies.map { it.url }.toSet()

                // Pinned servers are always retained in the Manual set, but they are
                // ranked with every other server after the client-side ping test.
                val currentStarredUrls = _starredServers.value.filterNot { it in pinnedUrls }.toSet()
                val starredNodes = mutableListOf<ProxyNode>()

                // Gather existing starred nodes from the current manual proxies list (keeps their names/details)
                _manualProxies.value.forEach { node ->
                    if (currentStarredUrls.contains(node.url) && node.url !in pinnedUrls) {
                        starredNodes.add(node)
                    }
                }

                // If any starred URL is not in current list, check the new flatManualList
                val gatheredUrls = starredNodes.map { it.url }.toSet()
                flatManualList.forEach { node ->
                    if (currentStarredUrls.contains(node.url) && node.url !in pinnedUrls && !gatheredUrls.contains(node.url)) {
                        starredNodes.add(node)
                    }
                }

                // For any remaining starred URL, create a default ProxyNode so we don't lose it
                val finalGatheredUrls = starredNodes.map { it.url }.toSet()
                currentStarredUrls.forEach { url ->
                    if (url !in pinnedUrls && !finalGatheredUrls.contains(url)) {
                        val originalName = extractServerName(url)
                        val displayName = if (originalName.isNotEmpty() && originalName != "Unknown") originalName else "Starred Server"
                        starredNodes.add(
                            ProxyNode(
                                url = url,
                                ping = 0,
                                name = displayName,
                                country = "Auto"
                            )
                        )
                    }
                }

                // Repository candidates fill the remaining Manual capacity.
                val filteredFlatList = flatManualList.filter { newNode ->
                    newNode.url !in pinnedUrls && starredNodes.none { starredNode -> starredNode.url == newNode.url }
                }

                val deltaCount = (50 - pinnedManualProxies.size - starredNodes.size).coerceAtLeast(0)
                val orderedCandidates = filteredFlatList
                    .sortedWith(compareBy<ProxyNode> { if (it.ping > 0) 0 else 1 }.thenBy { it.ping })

                // Show the server-side best fifty immediately, then retain the rest as
                // a local reserve for gradual testing on this user's own network.
                val bestCandidates = orderedCandidates.take(deltaCount)
                manualReserveProxies = orderedCandidates.drop(deltaCount)
                val cachedManual = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.getString("manual_test_results", null)?.let {
                        JsonUtil.fromJsonSafe(it, Array<ProxyNode>::class.java)?.toList()
                    }.orEmpty()
                val previousManual = (_manualProxies.value + cachedManual).associateBy { it.url }
                val refreshed = (pinnedManualProxies + starredNodes + bestCandidates).map { node ->
                    previousManual[node.url]?.let { previous ->
                        node.copy(ping = when {
                            newerRepositoryTest && previous.ping < 0 && node.ping > 0 -> node.ping
                            previous.ping == -2 -> 0
                            else -> previous.ping
                        })
                    } ?: node
                }
                _manualProxies.value = labelManualNodes(refreshed)
                if (repoTestedAt > 0) manualPrefs?.edit()?.putLong("manual_repo_tested_at", repoTestedAt)?.apply()
                hasManualPingTest = hasManualPingTest || cachedManual.isNotEmpty()
                if (hasManualPingTest) sortManualProxies()

                // auto_connect: list of URLs
                val autoArray = jsonObj.getJSONArray("auto_connect")
                val autoList = mutableListOf<ProxyNode>()
                for (i in 0 until autoArray.length()) {
                    val url = autoArray.getString(i)
                    var foundCountryCode = ""
                    var foundIndex = 0
                    for ((code, nodes) in manualMap) {
                        val matchIdx = nodes.indexOfFirst { it.url == url }
                        if (matchIdx >= 0) {
                            foundCountryCode = code
                            foundIndex = matchIdx
                            break
                        }
                    }

                    val serverName = if (foundCountryCode.isNotEmpty()) {
                        "${getCountryName(foundCountryCode)} ${foundIndex + 1} (Auto)"
                    } else {
                        "Auto Connect ${i + 1}"
                    }

                    autoList.add(
                        ProxyNode(
                            url = url,
                            ping = 0,
                            name = serverName,
                            country = foundCountryCode
                        )
                    )
                }
                // The API publishes a fixed ready-to-use Auto set. Health checks may rank it,
                // but must never remove a candidate after a transient probe failure.
                // Pinned servers are tried first in Auto. The tested repository list
                // remains immediately behind them as a fallback pool.
                val prioritizedAuto = (pinnedManualProxies + autoList).distinctBy { it.url }
                _autoConnectProxies.value = prioritizedAuto.take(5)
                val readyAutoUrls = _autoConnectProxies.value.map { it.url }.toSet()
                autoReserveProxies = (prioritizedAuto.drop(5) + orderedCandidates)
                    .filterNot { it.url in readyAutoUrls }
                    .distinctBy { it.url }

            } catch (e: Exception) {
                LogUtil.e(TAG, "Failed to fetch proxies", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshServerListOnResume() {
        fetchProxies()
    }

    private fun getCountryName(code: String): String {
        return try {
            val locale = java.util.Locale.Builder().setLanguage("en").setRegion(code).build()
            locale.displayCountry
        } catch (e: java.lang.Exception) {
            code
        }
    }

    fun refresh(context: Context) {
        val lastFetch = MmkvManager.decodeSettingsLong("pref_last_get_config_time", 0L)
        val now = System.currentTimeMillis()
        val elapsed = now - lastFetch
        val cooldown = 5 * 60 * 1000L
        if (elapsed < cooldown) {
            val remaining = ((cooldown - elapsed) / 1000).toInt()
            val msg = if (java.util.Locale.getDefault().language == "fa") {
                "لطفاً برای دریافت مجدد کانفیگ‌ها $remaining ثانیه صبر کنید."
            } else {
                "Please wait $remaining seconds before fetching configs again."
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        MmkvManager.encodeSettings("pref_last_get_config_time", now)
        fetchProxies()
    }

    private val _vpnDelay = mutableStateOf<String>("")
    val vpnDelay: State<String> = _vpnDelay

    private val _realExitIp = mutableStateOf<String>("")
    val realExitIp: State<String> = _realExitIp

    private val _realExitCountryCode = mutableStateOf<String>("")
    val realExitCountryCode: State<String> = _realExitCountryCode
    private var exitIpJob: Job? = null

    fun updateExitIpAndCountry(ip: String, country: String) {
        _realExitIp.value = ip
        _realExitCountryCode.value = country.uppercase()
    }

    fun fetchRealExitIp() {
        exitIpJob?.cancel()
        exitIpJob = viewModelScope.launch(Dispatchers.Main) {
            _realExitIp.value = "Fetching..."
            _realExitCountryCode.value = ""

            var resolvedIp = ""
            var resolvedCountry = ""

            withContext(Dispatchers.IO) {
                // Retry up to 5 times with 2 second gaps to allow the tunnel to come up
                for (attempt in 1..5) {
                    try {
                        // getRemoteIPInfo() routes through the local HTTP proxy to ipinfo.io
                        val info = com.v2ray.ang.handler.SpeedtestManager.getRemoteIPInfo(appContext)
                        if (!info.isNullOrEmpty()) {
                            val match = Regex("\\((.*?)\\)\\s*(.*)").find(info)
                            if (match != null) {
                                val c = match.groupValues[1].uppercase().trim()
                                val ip = match.groupValues[2].trim()
                                if (com.v2ray.ang.util.Utils.isPureIpAddress(ip)) {
                                    resolvedIp = ip
                                    resolvedCountry = c.takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "getRemoteIPInfo attempt $attempt failed: ${e.message}")
                    }

                    if (attempt < 5) kotlinx.coroutines.delay(2000)
                }
            }

            if (resolvedIp.isNotEmpty()) {
                _realExitIp.value = resolvedIp
                _realExitCountryCode.value = resolvedCountry
            } else {
                _realExitIp.value = "Unavailable"
                _realExitCountryCode.value = ""
            }
        }
    }

    fun clearRealExitIp() {
        exitIpJob?.cancel()
        exitIpJob = null
        viewModelScope.launch(Dispatchers.Main) {
            _realExitIp.value = ""
            _realExitCountryCode.value = ""
        }
    }

    fun testCurrentVpnDelay() {
        viewModelScope.launch(Dispatchers.Main) {
            _vpnDelay.value = "Testing..."
            val delayResult = withContext(Dispatchers.IO) {
                try {
                    val start = System.currentTimeMillis()
                    val client = okhttp3.OkHttpClient.Builder()
                        .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,
                            java.net.InetSocketAddress("127.0.0.1", com.v2ray.ang.handler.SettingsManager.getHttpPort())))
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder()
                        .url("https://www.gstatic.com/generate_204")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code == 204) {
                            val delay = System.currentTimeMillis() - start
                            "${delay}ms"
                        } else {
                            "Fail"
                        }
                    }
                } catch (e: Exception) {
                    "Fail"
                }
            }
            _vpnDelay.value = delayResult
        }
    }

    // --------------------------------------------------------------- starred servers

    private fun loadStarredServers(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_STARRED_SERVERS, emptySet()) ?: emptySet()
        _starredServers.value = stored
    }

    fun toggleStar(context: Context, url: String) {
        val current = _starredServers.value.toMutableSet()
        if (current.contains(url)) {
            current.remove(url)
        } else {
            current.add(url)
        }
        _starredServers.value = current
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_STARRED_SERVERS, current)
            .apply()
        // Immediately sort manual proxies so starred servers pin/unpin instantly
        sortManualProxies()
    }

    fun isStarred(url: String): Boolean = _starredServers.value.contains(url)

    // --------------------------------------------------------------- connection

    /**
     * Ensure a SpicyVPN subscription exists so imported servers are grouped under it.
     */
    private fun ensureSubscription(context: Context): String {
        val existing = MmkvManager.decodeSubscription(SPICYVPN_SUB_ID)
        if (existing == null) {
            val subItem = SubscriptionItem(
                remarks = "SpicyVPN",
                url = "",
                enabled = true,
                autoUpdate = false
            )
            MmkvManager.encodeSubscription(SPICYVPN_SUB_ID, subItem)
        }
        return SPICYVPN_SUB_ID
    }

    /**
     * Import a single proxy URL into the server list and return its GUID, or null on failure.
     */
    private fun importProxy(context: Context, url: String, subId: String): String? {
        return try {
            // Use append = false to keep the SpicyVPN group clean (only 1 active proxy at a time).
            // This prevents duplicate accumulation and ensures the imported proxy is always the fresh one.
            val (count, _) = AngConfigManager.importBatchConfig(url, subId, false)
            // Failed parsing leaves the previous group intact; never connect that stale profile.
            if (count != 1) return null
            val servers = MmkvManager.decodeServerList(subId)
            val guid = findImportedGuid(context, url, subId)
            if (guid != null) {
                return guid
            }
            if (servers.size == 1) {
                return servers.single()
            }
            null
        } catch (e: Exception) {
            LogUtil.e(TAG, "importProxy failed for $url", e)
            null
        }
    }

    /**
     * Connect to the best available auto-connect server.
     */
    fun cancelAutoSelection() {
        autoSelectionJob?.cancel()
        com.v2ray.ang.tunnel.AutoFallback.cancel()
    }

    fun connectToAutoServer(context: Context, isRunning: Boolean): Boolean {
        if (autoSelectionJob?.isActive == true) {
            cancelAutoSelection()
            return true
        }
        val proxies = _autoConnectProxies.value
        _isFindingAuto.value = true
        autoSelectionJob = viewModelScope.launch(Dispatchers.IO) {
        val candidates = (proxies + autoReserveProxies).distinctBy { it.url }.take(25)
        var target: ProxyNode? = null
        for (candidate in candidates) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val result = measureCandidates(context, listOf(candidate), SPICYVPN_AUTO_SUB_ID, "SpicyVPN Auto Candidates").firstOrNull()
            if (result != null && result.ping > 0) { target = result; break }
        }
        val working = target
        val guid = if (working != null) {
            importProxy(context, working.url, ensureSubscription(context)) ?: return@launch
        } else {
            // Never run a second Aether engine while another VPN is already active.
            if (isRunning || CoreServiceManager.isRunning()) return@launch
            val fallback = com.v2ray.ang.tunnel.AutoFallback.find(context) ?: return@launch
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            MmkvManager.encodeServerConfig("spicyvpn-auto-fallback-profile", fallback)
            "spicyvpn-auto-fallback-profile"
        }
        withContext(Dispatchers.Main) {
        MmkvManager.setSelectServer(guid)
        MmkvManager.encodeSettings("pref_selected_server_url", working?.url.orEmpty())
        MmkvManager.encodeSettings("pref_connection_mode", "auto")
        autoConnectionStartedAtMs = System.currentTimeMillis()
        updateSelectedServerGuid()
        if (isRunning) {
            _pendingNodeGuid.value = guid
            CoreServiceManager.stopVService(context)
        } else {
            val activity = findActivity(context)
            if (activity != null) {
                activity.startVServiceSafely(guid)
            } else {
                CoreServiceManager.startVService(context, guid)
            }
        }
        }
        }
        val selection = autoSelectionJob
        selection?.invokeOnCompletion {
            if (autoSelectionJob === selection) _isFindingAuto.value = false
        }
        return true
    }

    /**
     * Import and select a manual node without starting the connection.
     */
    fun selectNode(context: Context, node: ProxyNode) {
        cancelAutoSelection()
        viewModelScope.launch(Dispatchers.IO) {
            val guid = importProxy(context, node.url, ensureSubscription(context)) ?: return@launch
            MmkvManager.setSelectServer(guid)
            MmkvManager.encodeSettings("pref_selected_server_url", node.url)
            MmkvManager.encodeSettings("pref_connection_mode", "manual")
            withContext(Dispatchers.Main) {
                updateSelectedServerGuid()
            }
        }
    }

    /**
     * Connect to a specific node directly.
     */
    fun connectToNode(context: Context, node: ProxyNode, isRunning: Boolean): Boolean {
        cancelAutoSelection()
        val guid = importProxy(context, node.url, ensureSubscription(context)) ?: return false
        MmkvManager.setSelectServer(guid)
        MmkvManager.encodeSettings("pref_selected_server_url", node.url)
        MmkvManager.encodeSettings("pref_connection_mode", "manual")
        updateSelectedServerGuid()
        if (isRunning) {
            _pendingNodeGuid.value = guid
            CoreServiceManager.stopVService(context)
        } else {
            val activity = findActivity(context)
            if (activity != null) {
                activity.startVServiceSafely(guid)
            } else {
                CoreServiceManager.startVService(context, guid)
            }
        }
        return true
    }

    fun testManualProxies(context: Context) {
        if (_isTestingManual.value) return
        hasManualPingTest = true
        _isTestingManual.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val currentNodes = _manualProxies.value.toMutableList()
            if (currentNodes.isEmpty()) {
                _isTestingManual.value = false
                return@launch
            }

            // Set all pings to -2 ("Testing..."). Pinned nodes have a separate
            // backing list, so update that list too before the first re-rank.
            val testedUrls = currentNodes.map { it.url }.toSet()
            pinnedManualProxies = pinnedManualProxies.map { node ->
                if (node.url in testedUrls) node.copy(ping = -2) else node
            }
            _manualProxies.value = currentNodes.map { it.copy(ping = -2) }

            val testSubId = "spicyvpn-manual-test"
            if (MmkvManager.decodeSubscription(testSubId) == null) {
                MmkvManager.encodeSubscription(
                    testSubId,
                    SubscriptionItem(
                        remarks = "SpicyVPN Manual Test",
                        url = "",
                        enabled = true,
                        autoUpdate = false
                    )
                )
            }

            try {
                val untestedUrls = currentNodes.map { it.url }.toMutableList()

                currentNodes.forEach { node ->
                    val nodeUrl = node.url
                    // Import one profile at a time: duplicate hosts and reversed import
                    // order must not attach a probe result to another configuration.
                    AngConfigManager.importBatchConfig(nodeUrl, testSubId, false)
                    val guid = MmkvManager.decodeServerList(testSubId).firstOrNull()
                        ?: return@forEach
                    untestedUrls.remove(nodeUrl)

                    // These tests use a dedicated temporary subscription, so selecting or
                    // connecting to a Manual server does not interrupt the remaining probes.
                    var finalPing = -1
                    val configResult = com.v2ray.ang.core.CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                    if (configResult.status) {
                        val delayUrl = com.v2ray.ang.handler.SettingsManager.getDelayTestUrl().takeIf { it.isNotEmpty() }
                            ?: "https://www.gstatic.com/generate_204"
                        val realDelay = com.v2ray.ang.core.CoreNativeManager.measureUsableOutbound(configResult.content, delayUrl)
                        if (realDelay > 0 && realDelay < Int.MAX_VALUE) finalPing = realDelay.toInt()
                    }

                    // Publish and rank every result immediately. The list may move while this
                    // worker continues testing, but updates are always matched by URL.
                    pinnedManualProxies = pinnedManualProxies.map {
                        if (it.url == nodeUrl) it.copy(ping = finalPing) else it
                    }
                    _manualProxies.value = _manualProxies.value.map {
                        if (it.url == nodeUrl) it.copy(ping = finalPing) else it
                    }
                    sortManualProxies()
                }

                // Invalid imports have no GUID to test; mark them complete rather than leave
                // a permanent "Testing" state in the list.
                if (untestedUrls.isNotEmpty()) {
                    pinnedManualProxies = pinnedManualProxies.map {
                        if (it.url in untestedUrls && it.ping == -2) it.copy(ping = -1) else it
                    }
                    _manualProxies.value = _manualProxies.value.map {
                        if (it.url in untestedUrls && it.ping == -2) it.copy(ping = -1) else it
                    }
                    sortManualProxies()
                }
                val protectedUrls = pinnedManualProxies.map { it.url }.toSet() + _starredServers.value
                for (reserve in manualReserveProxies.toList().take(16)) {
                    val replacement = _manualProxies.value.lastOrNull {
                        it.ping <= 0 && it.url !in protectedUrls
                    } ?: break
                    val tested = measureCandidates(context, listOf(reserve), testSubId, "SpicyVPN Manual Test").firstOrNull()
                    if (tested != null && tested.ping > 0) {
                        _manualProxies.value = labelManualNodes(_manualProxies.value.map {
                            if (it.url == replacement.url) tested else it
                        })
                        manualReserveProxies = manualReserveProxies.filterNot { it.url == tested.url }
                        sortManualProxies()
                    }
                }
            } finally {
                _isTestingManual.value = false
            }
        }
    }

    fun sortManualProxies() {
        if (!hasManualPingTest) return
        val currentNodes = _manualProxies.value
        val starredList = _starredServers.value.toList() // preserved order

        val sorted = currentNodes.sortedWith(Comparator { n1, n2 ->
            val idx1 = starredList.indexOf(n1.url)
            val idx2 = starredList.indexOf(n2.url)

            when {
                idx1 >= 0 && idx2 >= 0 -> {
                    // Both starred: newest starred (highest index) first
                    idx2.compareTo(idx1)
                }
                idx1 >= 0 -> -1 // n1 is starred, n2 is not -> n1 first
                idx2 >= 0 -> 1  // n2 is starred, n1 is not -> n2 first
                else -> {
                    // Neither starred: sort by ping
                    val p1 = if (n1.ping > 0) n1.ping else Int.MAX_VALUE
                    val p2 = if (n2.ping > 0) n2.ping else Int.MAX_VALUE
                    p1.compareTo(p2)
                }
            }
        })
        _manualProxies.value = labelManualNodes(sorted)
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString("manual_test_results", JsonUtil.toJson(_manualProxies.value))?.apply()
    }

    private fun labelManualNodes(nodes: List<ProxyNode>): List<ProxyNode> {
        val pinnedIndexes = pinnedManualProxies.mapIndexed { index, node -> node.url to index }.toMap()
        var repositoryIndex = 0

        return nodes.take(50).map { node ->
            val pinnedIndex = pinnedIndexes[node.url]
            if (pinnedIndex != null) {
                node.copy(name = "spicyvpn${pinnedIndex + 1}")
            } else {
                repositoryIndex += 1
                node.copy(name = "Server$repositoryIndex")
            }
        }
    }

    // --------------------------------------------------------------- static IP (star)

    fun isStaticEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STATIC_ENABLED, false)

    fun getStaticServerUrl(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATIC_SERVER_URL, null)

    fun setStaticServer(context: Context, enabled: Boolean, url: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putBoolean(KEY_STATIC_ENABLED, enabled)
        if (url != null) {
            prefs.putString(KEY_STATIC_SERVER_URL, url)
        }
        prefs.apply()
    }

    // --------------------------------------------------------------- custom user proxies

    private fun loadCustomProxies(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CUSTOM_PROXIES, null) ?: return
        try {
            val jsonArr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<ProxyNode>()
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val url = obj.getString("url")
                val name = obj.optString("name", extractServerName(url))
                val ping = obj.optInt("ping", 0)
                val country = obj.optString("country", "")
                list.add(ProxyNode(url = url, ping = ping, name = name, country = country))
            }
            _customProxies.value = list
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to load custom proxies", e)
        }
    }

    private fun saveCustomProxies(context: Context) {
        try {
            val jsonArr = org.json.JSONArray()
            for (node in _customProxies.value) {
                val obj = org.json.JSONObject()
                obj.put("url", node.url)
                obj.put("name", node.name)
                obj.put("ping", node.ping)
                obj.put("country", node.country)
                jsonArr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_CUSTOM_PROXIES, jsonArr.toString())
                .apply()
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to save custom proxies", e)
        }
    }

    fun importCustomConfigs(context: Context, text: String, defaultName: String? = null): Int {
        if (text.isBlank()) return 0
        val trimmed = text.trim()
        val guidsBefore = MmkvManager.decodeServerList(SPICYVPN_CUSTOM_SUB_ID).toSet()
        val count = try {
            val (importedCount, _) = AngConfigManager.importBatchConfig(text, SPICYVPN_CUSTOM_SUB_ID, true)
            importedCount
        } catch (e: Exception) {
            0
        }
        if (count <= 0) return 0

        val guidsAfter = MmkvManager.decodeServerList(SPICYVPN_CUSTOM_SUB_ID)
        val newGuids = guidsAfter.filter { it !in guidsBefore }

        val current = _customProxies.value.toMutableList()
        val existingUrls = current.map { it.url }.toSet()

        if (newGuids.isNotEmpty()) {
            for (guid in newGuids) {
                val config = MmkvManager.decodeServerConfig(guid) ?: continue
                val host = config.server.orEmpty()
                val port = config.serverPort?.toIntOrNull() ?: 0
                val customName = defaultName?.takeIf { it.isNotBlank() }
                    ?: config.remarks.takeIf { it.isNotBlank() && it != "none" && !it.matches(Regex("^\\d+$")) }
                    ?: "${config.configType.name}-${current.size + 1}"
                config.remarks = customName
                MmkvManager.encodeServerConfig(guid, config)

                val nodeUrl = "mmkv://$guid"
                if (nodeUrl !in existingUrls) {
                    current.add(0, ProxyNode(url = nodeUrl, ping = 0, name = customName, country = ""))
                }
            }
        } else {
            // Fallback for lines if batch didn't create new GUIDs directly
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            for (line in lines) {
                if (line.contains("://") && line !in existingUrls) {
                    val displayName = defaultName?.takeIf { it.isNotBlank() } ?: extractServerName(line).ifBlank { "Custom-${current.size + 1}" }
                    current.add(0, ProxyNode(url = line, ping = 0, name = displayName, country = ""))
                }
            }
        }

        _customProxies.value = current
        saveCustomProxies(context)
        return count
    }

    val pendingSharedImport = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun sharedProfiles(nodes: List<ProxyNode>): List<SharedProfile> {
        val profiles = linkedMapOf<String, SharedProfile>()
        fun add(guid: String) {
            if (guid in profiles) return
            require(profiles.size < 500)
            val profile = MmkvManager.decodeServerConfig(guid) ?: error("Configuration is not available")
            profiles[guid] = SharedProfile(profile.copy(subscriptionId = ""), MmkvManager.decodeServerRaw(guid).orEmpty(), guid)
            profile.proxyChainProfiles.orEmpty().split(',').filter { it.isNotBlank() }.forEach { add(it.trim()) }
        }
        nodes.forEach { add(findGuidForNode(it) ?: error("Configuration is not available")) }
        return profiles.values.toList()
    }

    fun importSharedFolder(context: Context, folder: SharedFolder): List<String> {
        val ids = folder.profiles.associate { it.sourceId to java.util.UUID.randomUUID().toString() }
        val urls = folder.profiles.map { shared ->
            val chain = shared.profile.proxyChainProfiles?.split(',')?.filter { it.isNotBlank() }?.joinToString(",") {
                ids[it.trim()] ?: error("Missing proxy chain member")
            }
            val profile = shared.profile.copy(subscriptionId = SPICYVPN_CUSTOM_SUB_ID, proxyChainProfiles = chain)
            val guid = MmkvManager.encodeServerConfig(ids[shared.sourceId] ?: "", profile)
            if (shared.raw.isNotBlank()) MmkvManager.encodeServerRaw(guid, shared.raw)
            "mmkv://$guid"
        }
        syncCustomProxiesFromMmkv(context)
        return urls
    }

    suspend fun importCustomConfigsAsync(context: Context, text: String, defaultName: String? = null): Int {
        if (_isImportingCustom.value || text.isBlank()) return 0
        _isImportingCustom.value = true
        return try {
            withContext(Dispatchers.IO) {
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val subscriptionUrls = lines.filter { com.v2ray.ang.util.Utils.isValidSubUrl(it) }
                val configText = lines.filterNot { com.v2ray.ang.util.Utils.isValidSubUrl(it) }.joinToString("\n")
                var imported = if (configText.isNotBlank()) importCustomConfigs(context, configText, defaultName) else 0
                var addedSubscriptions = 0
                var failedSubscriptionUpdates = 0

                for (url in subscriptionUrls) {
                    val (subscription, created) = AngConfigManager.getOrCreateSubscriptionFromUrl(url)
                        .takeIf { it.first != null } ?: continue

                    if (created && subscription != null) {
                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val tracked = prefs.getStringSet(KEY_CUSTOM_IMPORTED_SUBSCRIPTIONS, emptySet()).orEmpty().toMutableSet()
                        tracked.add(subscription.guid)
                        prefs.edit().putStringSet(KEY_CUSTOM_IMPORTED_SUBSCRIPTIONS, tracked).apply()
                    }

                    val sub = subscription ?: continue
                    if (created) addedSubscriptions++
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val linked = prefs.getStringSet(KEY_CUSTOM_LINKED_SUBSCRIPTIONS, emptySet()).orEmpty().toMutableSet()
                    linked.add(sub.guid)
                    prefs.edit().putStringSet(KEY_CUSTOM_LINKED_SUBSCRIPTIONS, linked).apply()
                    val updateResult = AngConfigManager.updateConfigViaSub(sub)
                    if (updateResult.failureCount > 0) failedSubscriptionUpdates++
                    if (updateResult.configCount > 0) imported += updateResult.configCount
                }

                if (subscriptionUrls.isNotEmpty()) {
                    syncCustomProxiesFromSubscriptions(context)
                    if (imported == 0 && addedSubscriptions > 0) imported = addedSubscriptions
                    if (imported == 0 && failedSubscriptionUpdates > 0) imported = -failedSubscriptionUpdates
                }

                imported
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to import custom configs", e)
            0
        } finally {
            _isImportingCustom.value = false
        }
    }

    /** Sync any newly added servers from MMKV sub back to _customProxies. Call after ServerActivity returns. */
    fun syncCustomProxiesFromMmkv(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val subIds = prefs.getStringSet(KEY_CUSTOM_LINKED_SUBSCRIPTIONS, emptySet()).orEmpty() +
                prefs.getStringSet(KEY_CUSTOM_IMPORTED_SUBSCRIPTIONS, emptySet()).orEmpty() + SPICYVPN_CUSTOM_SUB_ID
            val guids = subIds.flatMap { MmkvManager.decodeServerList(it) }.toSet()
            val current = _customProxies.value.toMutableList()

            // Remove any mmkv:// or mmkv- nodes whose GUID is no longer in MMKV custom sub
            current.removeAll { node ->
                if (node.url.startsWith("mmkv://")) {
                    val guid = node.url.removePrefix("mmkv://").trim()
                    guid !in guids
                } else if (node.url.contains("mmkv-")) {
                    val guid = node.url.substringAfter("mmkv-", "").substringBefore("@").trim()
                    guid.isNotBlank() && guid !in guids
                } else {
                    false
                }
            }

            for (guid in guids) {
                val config = MmkvManager.decodeServerConfig(guid) ?: continue
                val host = config.server.orEmpty()
                val port = config.serverPort?.toIntOrNull() ?: 0
                val remarks = config.remarks.orEmpty().ifBlank { "${config.configType}-${current.size + 1}" }
                val nodeUrl = "mmkv://$guid"
                val existingIndex = current.indexOfFirst { it.url == nodeUrl || it.url.contains("mmkv-$guid") }
                if (existingIndex >= 0) {
                    // Update remarks if changed
                    if (current[existingIndex].name != remarks) {
                        current[existingIndex] = current[existingIndex].copy(name = remarks)
                    }
                } else {
                    current.add(0, ProxyNode(url = nodeUrl, ping = 0, name = remarks, country = ""))
                }
            }
            _customProxies.value = current
            saveCustomProxies(context)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to sync custom proxies from MMKV", e)
        }
    }

    /** Find the MMKV guid for the node stored under the custom subscription. */
    fun findGuidForNode(node: ProxyNode): String? {
        if (node.url.startsWith("mmkv://")) {
            val guid = node.url.removePrefix("mmkv://").trim()
            if (MmkvManager.decodeServerConfig(guid) != null) return guid
        }
        val guidFromUrl = node.url.substringAfter("mmkv-", "").substringBefore("@").takeIf { it.isNotBlank() }
        if (guidFromUrl != null) {
            if (MmkvManager.decodeServerConfig(guidFromUrl) != null) return guidFromUrl
        }
        val (host, port) = extractHostPort(node.url) ?: return null
        val guids = MmkvManager.decodeServerList(SPICYVPN_CUSTOM_SUB_ID)
        for (guid in guids) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (config.server == host && config.serverPort == port.toString()) return guid
        }
        return null
    }

    /** Update an existing custom node (after editing). */
    fun updateCustomNode(context: Context, oldNode: ProxyNode, newName: String, username: String? = null, password: String? = null) {
        val current = _customProxies.value.toMutableList()
        val idx = current.indexOfFirst { it.url == oldNode.url }
        if (idx >= 0) {
            current[idx] = current[idx].copy(name = newName.ifBlank { oldNode.name })
        }
        val guid = findGuidForNode(oldNode)
        if (guid != null) {
            val config = MmkvManager.decodeServerConfig(guid)
            if (config != null) {
                config.remarks = newName.ifBlank { oldNode.name }
                if (username != null) config.username = username
                if (password != null) config.password = password
                MmkvManager.encodeServerConfig(guid, config)
            }
        }
        _customProxies.value = current
        saveCustomProxies(context)
    }

    fun editCustomRawConfig(context: Context, node: ProxyNode, name: String, text: String): Boolean {
        val guid = findGuidForNode(node) ?: return false
        val previous = MmkvManager.decodeServerConfig(guid) ?: return false
        val staging = "custom-edit-${java.util.UUID.randomUUID()}"
        try {
            val importText = if (previous.configType in listOf(com.v2ray.ang.enums.EConfigType.MASTERDNS,
                    com.v2ray.ang.enums.EConfigType.STORMDNS) && text.trimStart().startsWith("{")) {
                previous.configType.protocolScheme + java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            } else text
            val (count, _) = AngConfigManager.importBatchConfig(importText, staging, true)
            val imported = MmkvManager.decodeServerList(staging)
            if (count != 1 || imported.size != 1) return false
            val replacement = MmkvManager.decodeServerConfig(imported.single()) ?: return false
            if (replacement.configType != previous.configType) return false
            val raw = MmkvManager.decodeServerRaw(imported.single())
            replacement.subscriptionId = previous.subscriptionId
            replacement.remarks = name.ifBlank { previous.remarks }
            if (replacement.configType == com.v2ray.ang.enums.EConfigType.OPENVPN) {
                replacement.username = previous.username
                replacement.password = previous.password
            }
            MmkvManager.encodeServerConfig(guid, replacement)
            MmkvManager.encodeServerRaw(guid, raw.orEmpty())
            syncCustomProxiesFromMmkv(context)
            return true
        } catch (_: Exception) {
            return false
        } finally {
            MmkvManager.removeServerViaSubid(staging)
        }
    }

    fun saveDnsOnlyNode(context: Context, name: String, resolver: String, existing: ProxyNode? = null) {
        val normalized = DnsOnlyProfile.normalize(resolver)
        val guid = existing?.let { findGuidForNode(it) }.orEmpty()
        val profile = com.v2ray.ang.dto.entities.ProfileItem.create(com.v2ray.ang.enums.EConfigType.SOCKS).apply {
            subscriptionId = SPICYVPN_CUSTOM_SUB_ID
            remarks = name.ifBlank { normalized }
            server = "127.0.0.1"; serverPort = "53"
            dnsOnlyResolver = normalized
        }
        MmkvManager.encodeServerConfig(guid, profile)
        syncCustomProxiesFromMmkv(context)
    }

    /** Add an OpenVPN or AmneziaWG / WireGuard config from raw text content. */
    fun addOvpnOrAwgFromText(
        context: Context,
        protocol: String,
        configText: String,
        name: String,
        username: String = "",
        password: String = "",
        saveCredentials: Boolean = true
    ): Boolean {
        if (configText.isBlank()) return false
        val rawText = configText.trim()
        val safeText = if (protocol in setOf("masterdns", "stormdns") && rawText.startsWith("{")) {
            "$protocol://" + java.util.Base64.getEncoder().encodeToString(rawText.toByteArray(Charsets.UTF_8))
        } else if (protocol == "aether") {
            runCatching { com.v2ray.ang.fmt.AetherFmt.normalize(rawText) }.getOrElse { return false }
        } else rawText
        val guidsBefore = MmkvManager.decodeServerList(SPICYVPN_CUSTOM_SUB_ID).toSet()
        val count = try {
            val (importedCount, _) = AngConfigManager.importBatchConfig(safeText, SPICYVPN_CUSTOM_SUB_ID, true)
            importedCount
        } catch (e: Exception) { 0 }

        val guidsAfter = MmkvManager.decodeServerList(SPICYVPN_CUSTOM_SUB_ID)
        val newGuids = guidsAfter.filter { it !in guidsBefore }
        if (count <= 0) return false
        val targetGuid = newGuids.firstOrNull()

        val safeName = name.ifBlank { "$protocol-${_customProxies.value.size + 1}" }

        if (targetGuid != null) {
            val config = MmkvManager.decodeServerConfig(targetGuid)
            if (config != null) {
                if (username.isNotBlank()) config.username = username
                if (password.isNotBlank()) config.password = password
                config.remarks = safeName
                MmkvManager.encodeServerConfig(targetGuid, config)
            }
            val nodeUrl = "mmkv://$targetGuid"
            val current = _customProxies.value.toMutableList()
            if (current.none { it.url == nodeUrl }) {
                current.add(0, ProxyNode(url = nodeUrl, ping = 0, name = safeName, country = ""))
            }
            _customProxies.value = current
            saveCustomProxies(context)
            return true
        }

        return false
    }

    /** Add an SSH config node with optional TLS/SNI (Stunnel). */
    fun addSshNode(
        context: Context,
        host: String,
        port: Int,
        user: String,
        pass: String,
        remarks: String,
        enableTls: Boolean = false,
        sni: String = "",
        privateKey: String = "",
        keyPassphrase: String = "",
        udpGatewayHost: String = "",
        udpGatewayPort: Int = 7300,
        upstreamProxy: String = ""
    ): Boolean {
        if (host.isBlank() || user.isBlank()) return false
        if (udpGatewayHost.isNotBlank() && udpGatewayPort !in 1..65535) return false
        val safePort = if (port > 0) port else 22
        val encodedUser = com.v2ray.ang.util.Utils.encodeURIComponent(user)
        val userAuth = if (pass.isNotBlank()) {
            "$encodedUser:${com.v2ray.ang.util.Utils.encodeURIComponent(pass)}"
        } else {
            encodedUser
        }
        val safeRemarks = remarks.ifBlank { "SSH-$host:$safePort" }
        val queryParams = mutableListOf<String>()
        if (upstreamProxy.isNotBlank()) queryParams.add("proxy=${com.v2ray.ang.util.Utils.encodeURIComponent(upstreamProxy)}")
        if (udpGatewayHost.isNotBlank()) {
            queryParams.add("udpgw_host=${com.v2ray.ang.util.Utils.encodeURIComponent(udpGatewayHost.trim())}")
            queryParams.add("udpgw_port=$udpGatewayPort")
        }
        if (enableTls) {
            queryParams.add("tls=true")
            queryParams.add("security=tls")
        }
        if (sni.isNotBlank()) queryParams.add("sni=${com.v2ray.ang.util.Utils.encodeURIComponent(sni.trim())}")
        if (privateKey.isNotBlank()) queryParams.add("private_key=${com.v2ray.ang.util.Utils.encodeURIComponent(privateKey)}")
        if (keyPassphrase.isNotBlank()) queryParams.add("key_passphrase=${com.v2ray.ang.util.Utils.encodeURIComponent(keyPassphrase)}")
        val queryString = if (queryParams.isNotEmpty()) "?" + queryParams.joinToString("&") else ""
        val sshUrl = "ssh://$userAuth@$host:$safePort$queryString#${com.v2ray.ang.util.Utils.encodeURIComponent(safeRemarks)}"
        val count = importCustomConfigs(context, sshUrl, defaultName = safeRemarks)
        return count > 0
    }

    /** Add a DNSTT config node. */
    fun addDnsttNode(
        context: Context,
        dnsServer: String,
        port: Int,
        publicKey: String,
        domain: String,
        remarks: String
    ): Boolean {
        if (dnsServer.isBlank() || publicKey.isBlank() || domain.isBlank()) return false
        val safePort = if (port > 0) port else 53
        val safeRemarks = remarks.ifBlank { "DNSTT-$dnsServer" }
        val queryParams = mutableListOf<String>()
        queryParams.add("publickey=${com.v2ray.ang.util.Utils.encodeURIComponent(publicKey)}")
        queryParams.add("domain=${com.v2ray.ang.util.Utils.encodeURIComponent(domain)}")
        val queryString = "?" + queryParams.joinToString("&")
        val dnsttUrl = "dnstt://$dnsServer:$safePort$queryString#${com.v2ray.ang.util.Utils.encodeURIComponent(safeRemarks)}"
        val count = importCustomConfigs(context, dnsttUrl, defaultName = safeRemarks)
        return count > 0
    }

    fun addCustomSshNode(context: Context, host: String, port: Int, user: String, pass: String?, remarks: String?): Boolean {
        val safePort = if (port > 0) port else 22
        val userAuth = if (pass.isNullOrEmpty()) user else "$user:$pass"
        val query = ""
        val frag = remarks?.takeIf { it.isNotBlank() } ?: "$host:$safePort"
        val sshUrl = "ssh://$userAuth@$host:$safePort$query#${com.v2ray.ang.util.Utils.encodeURIComponent(frag)}"

        val count = importCustomConfigs(context, sshUrl, defaultName = frag)
        return count > 0
    }

    fun addCustomManualNode(
        context: Context,
        protocol: String,
        host: String,
        portStr: String,
        idOrPass: String,
        remarks: String
    ): Boolean {
        if (host.isBlank()) return false
        val port = portStr.toIntOrNull() ?: when (protocol) {
            "vmess", "vless", "trojan", "hysteria2" -> 443
            "ssh" -> 22
            "dnstt" -> 53
            "socks", "http" -> 1080
            "wireguard", "amneziawg" -> 51820
            "openvpn" -> 1194
            else -> 443
        }
        val safeRemarks = remarks.ifBlank { "$protocol-$host" }
        val encodedRemarks = com.v2ray.ang.util.Utils.encodeURIComponent(safeRemarks)

        val configUrl = when (protocol.lowercase()) {
            "vmess" -> {
                val json = org.json.JSONObject().apply {
                    put("v", "2")
                    put("ps", safeRemarks)
                    put("add", host)
                    put("port", port.toString())
                    put("id", idOrPass)
                    put("aid", "0")
                    put("scy", "auto")
                    put("net", "tcp")
                    put("type", "none")
                    put("tls", "")
                }
                "vmess://" + android.util.Base64.encodeToString(json.toString().toByteArray(), android.util.Base64.NO_WRAP)
            }
            "vless" -> "vless://$idOrPass@$host:$port?encryption=none#$encodedRemarks"
            "shadowsocks" -> {
                val methodPass = if (idOrPass.contains(":")) idOrPass else "aes-256-gcm:$idOrPass"
                val b64 = android.util.Base64.encodeToString(methodPass.toByteArray(), android.util.Base64.NO_WRAP)
                "ss://$b64@$host:$port#$encodedRemarks"
            }
            "trojan" -> "trojan://$idOrPass@$host:$port#$encodedRemarks"
            "hysteria2" -> "hysteria2://$idOrPass@$host:$port#$encodedRemarks"
            "socks" -> {
                val userPass = if (idOrPass.isNotBlank()) "$idOrPass@" else ""
                "socks://$userPass$host:$port#$encodedRemarks"
            }
            "http" -> {
                val userPass = if (idOrPass.isNotBlank()) "$idOrPass@" else ""
                "http://$userPass$host:$port#$encodedRemarks"
            }
            "ssh" -> {
                val userPass = if (idOrPass.isNotBlank()) "$idOrPass@" else ""
                "ssh://$userPass$host:$port#$encodedRemarks"
            }
            "wireguard" -> "wireguard://$idOrPass@$host:$port?publickey=$idOrPass#$encodedRemarks"
            "amneziawg" -> "awg://$idOrPass@$host:$port?publickey=$idOrPass#$encodedRemarks"
            "dnstt" -> "dnstt://$idOrPass@$host:$port?domain=$host#$encodedRemarks"
            "openvpn" -> {
                val userPass = if (idOrPass.isNotBlank()) "$idOrPass@" else ""
                "openvpn://$userPass$host:$port?proto=udp#$encodedRemarks"
            }
            else -> null
        } ?: return false

        val count = importCustomConfigs(context, configUrl, defaultName = safeRemarks)
        return count > 0
    }

    fun removeCustomNode(context: Context, node: ProxyNode) {
        val guid = findGuidForNode(node)
        if (guid != null) {
            MmkvManager.removeServer(guid)
        }
        val current = _customProxies.value.toMutableList()
        current.removeAll { it.url == node.url || (guid != null && (it.url == "mmkv://$guid" || it.url.contains("mmkv-$guid"))) }
        _customProxies.value = current
        saveCustomProxies(context)
    }

    fun removeCustomDuplicates(context: Context): Int {
        val seen = mutableSetOf<String>()
        val duplicates = _customProxies.value.filter { node ->
            val guid = findGuidForNode(node)
            val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
            val key = if (profile == null) node.url.substringBefore('#') else {
                val canonical = profile.copy(subscriptionId = "", remarks = "", description = null, addedTime = 0)
                JsonUtil.toJson(canonical) + "|" + MmkvManager.decodeServerRaw(guid).orEmpty()
            }
            !seen.add(key)
        }
        duplicates.forEach { removeCustomNode(context, it) }
        return duplicates.size
    }

    fun removeCustomInvalid(context: Context): Int {
        val invalid = _customProxies.value.filter { it.ping == -1 }
        invalid.forEach { removeCustomNode(context, it) }
        return invalid.size
    }

    suspend fun refreshCustomSubscription(context: Context, subscriptionId: String): Boolean = withContext(Dispatchers.IO) {
        val sub = MmkvManager.decodeSubscriptions().find { it.guid == subscriptionId }
            ?: return@withContext false
        val result = AngConfigManager.updateConfigViaSub(sub)
        withContext(Dispatchers.Main) {
            _customProxies.value = _customProxies.value.filter { node ->
                !node.url.startsWith("mmkv://") || MmkvManager.decodeServerConfig(node.url.removePrefix("mmkv://")) != null
            }
            syncCustomProxiesFromSubscriptions(context)
            saveCustomProxies(context)
        }
        result.failureCount == 0
    }

    fun clearCustomProxies(context: Context) {
        _customProxies.value = emptyList()
        saveCustomProxies(context)
        MmkvManager.removeServerViaSubid(SPICYVPN_CUSTOM_SUB_ID)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ownedIds = prefs.getStringSet(KEY_CUSTOM_IMPORTED_SUBSCRIPTIONS, emptySet()).orEmpty()
        ownedIds.forEach {
            MmkvManager.removeSubscription(it)
        }
        prefs.edit()
            .remove(KEY_CUSTOM_IMPORTED_SUBSCRIPTIONS)
            .remove(KEY_CUSTOM_LINKED_SUBSCRIPTIONS)
            .apply()
    }

    /** Adds profiles owned by user-managed subscriptions to the Custom server list. */
    fun syncCustomProxiesFromSubscriptions(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val linkedIds = prefs
                .getStringSet(KEY_CUSTOM_LINKED_SUBSCRIPTIONS, emptySet()).orEmpty()
            val ownedIds = prefs
                .getStringSet(KEY_CUSTOM_IMPORTED_SUBSCRIPTIONS, emptySet()).orEmpty()
            val subscriptionIds = MmkvManager.decodeSubscriptions()
                .filter { it.guid in linkedIds || it.guid in ownedIds }
                .map { it.guid }
            val current = _customProxies.value.toMutableList()
            val currentUrls = current.map { it.url }.toMutableSet()
            var changed = false

            for (subscriptionId in subscriptionIds) {
                for (guid in MmkvManager.decodeServerList(subscriptionId)) {
                    val nodeUrl = "mmkv://$guid"
                    if (!currentUrls.add(nodeUrl)) continue
                    val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                    val name = profile.remarks.takeIf { it.isNotBlank() && it != "none" }
                        ?: "${profile.configType.name}-${current.size + 1}"
                    current.add(0, ProxyNode(url = nodeUrl, ping = 0, name = name, country = ""))
                    changed = true
                }
            }

            if (changed) {
                _customProxies.value = current
                saveCustomProxies(context)
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to sync subscription proxies", e)
        }
    }

    fun selectCustomNode(context: Context, node: ProxyNode) {
        cancelAutoSelection()
        viewModelScope.launch(Dispatchers.IO) {
            val guid = findGuidForNode(node) ?: importProxy(context, node.url, SPICYVPN_CUSTOM_SUB_ID) ?: return@launch
            MmkvManager.setSelectServer(guid)
            MmkvManager.encodeSettings("pref_selected_server_url", node.url)
            MmkvManager.encodeSettings("pref_connection_mode", "custom")
            withContext(Dispatchers.Main) {
                updateSelectedServerGuid()
            }
        }
    }

    fun connectToCustomNode(context: Context, node: ProxyNode, isRunning: Boolean): Boolean {
        cancelAutoSelection()
        val guid = findGuidForNode(node) ?: importProxy(context, node.url, SPICYVPN_CUSTOM_SUB_ID) ?: return false
        MmkvManager.setSelectServer(guid)
        MmkvManager.encodeSettings("pref_selected_server_url", node.url)
        MmkvManager.encodeSettings("pref_connection_mode", "custom")
        updateSelectedServerGuid()
        if (isRunning) {
            _pendingNodeGuid.value = guid
            CoreServiceManager.stopVService(context)
        } else {
            val activity = findActivity(context)
            if (activity != null) {
                activity.startVServiceSafely(guid)
            } else {
                CoreServiceManager.startVService(context, guid)
            }
        }
        return true
    }

    fun testCustomProxies(context: Context) {
        if (_isTestingCustom.value) return
        _isTestingCustom.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val currentNodes = _customProxies.value.toMutableList()
            if (currentNodes.isEmpty()) {
                _isTestingCustom.value = false
                return@launch
            }

            _customProxies.value = currentNodes.map { it.copy(ping = -2) }
            val testSubId = "spicyvpn-custom-test"
            if (MmkvManager.decodeSubscription(testSubId) == null) {
                MmkvManager.encodeSubscription(
                    testSubId,
                    SubscriptionItem(
                        remarks = "SpicyVPN Custom Test",
                        url = "",
                        enabled = true,
                        autoUpdate = false
                    )
                )
            }

            try {
                // For non-mmkv URLs, import them to the test sub
                val nonMmkvNodes = currentNodes.filter { !it.url.startsWith("mmkv://") }
                if (nonMmkvNodes.isNotEmpty()) {
                    val joinedUrls = nonMmkvNodes.joinToString("\n") { it.url }
                    AngConfigManager.importBatchConfig(joinedUrls, testSubId, false)
                }

                val testGuids = MmkvManager.decodeServerList(testSubId)
                val untestedUrls = currentNodes.map { it.url }.toMutableList()

                for (node in currentNodes) {
                    val guid = findGuidForNode(node) ?: run {
                        // Check if in test sub
                        val (host, port) = extractHostPort(node.url) ?: (null to null)
                        testGuids.firstOrNull { g ->
                            val c = MmkvManager.decodeServerConfig(g)
                            c != null && c.server == host && c.serverPort == port?.toString()
                        }
                    }

                    if (guid == null) {
                        continue
                    }

                    val config = MmkvManager.decodeServerConfig(guid) ?: continue
                    val host = config.server.orEmpty()
                    val port = config.serverPort?.toIntOrNull() ?: continue

                    untestedUrls.remove(node.url)

                    val tcpTime = com.v2ray.ang.handler.SpeedtestManager.socketConnectTime(host, port, 1500)
                    var finalPing = -1
                    if (tcpTime > -1L) {
                        val configResult = com.v2ray.ang.core.CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                        if (configResult.status) {
                            val delayUrl = com.v2ray.ang.handler.SettingsManager.getDelayTestUrl().takeIf { it.isNotEmpty() }
                                ?: "https://www.gstatic.com/generate_204"
                            val realDelay = com.v2ray.ang.core.CoreNativeManager.measureOutboundDelay(configResult.content, delayUrl)
                            if (realDelay > 0 && realDelay < Int.MAX_VALUE) {
                                finalPing = realDelay.toInt()
                            } else {
                                finalPing = tcpTime.toInt()
                            }
                        } else {
                            finalPing = tcpTime.toInt()
                        }
                    }

                    _customProxies.value = _customProxies.value.map {
                        if (it.url == node.url) it.copy(ping = finalPing) else it
                    }
                }

                if (untestedUrls.isNotEmpty()) {
                    _customProxies.value = _customProxies.value.map {
                        if (it.url in untestedUrls && it.ping == -2) it.copy(ping = -1) else it
                    }
                }
                saveCustomProxies(context)
            } finally {
                _isTestingCustom.value = false
            }
        }
    }

    fun connectToStaticServer(context: Context): Boolean {
        if (!isStaticEnabled(context)) return false
        val url = getStaticServerUrl(context) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            val alive = isProxyAlive(url)
            if (alive) {
                val guid = importProxy(context, url, ensureSubscription(context))
                if (guid != null) {
                    MmkvManager.setSelectServer(guid)
                    MmkvManager.encodeSettings("pref_selected_server_url", url)
                    MmkvManager.encodeSettings("pref_connection_mode", "manual")
                    CoreServiceManager.startVService(context, guid)
                    return@launch
                }
            }
            LogUtil.w(TAG, "Static Manual server is down; keeping the user's Manual selection")
        }
        return true
    }

    fun ensurePsiphonDummyProfile(context: Context): String {
        return MmkvManager.ensurePsiphonDummyProfile()
    }


    // --------------------------------------------------------------- background ping

    fun startBackgroundPing(context: Context) {
        if (pingJob?.isActive == true) return
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            // Do not let a health probe interrupt the initial connection handshake.
            kotlinx.coroutines.delay(INITIAL_HEALTH_CHECK_DELAY_MS)
            while (true) {
                try {
                    maintainReadyServers(context)
                } catch (e: Exception) {
                    LogUtil.e(TAG, "Background ping error", e)
                }
                kotlinx.coroutines.delay(PING_INTERVAL_MS)
            }
        }
    }

    fun stopBackgroundPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private suspend fun maintainReadyServers(context: Context) {
        val readyCandidates = _autoConnectProxies.value
        if (readyCandidates.isEmpty()) return

        val reserveProbe = nextAutoReserveProbe()
        val candidates = (readyCandidates + reserveProbe).distinctBy { it.url }
        val measured = measureCandidates(context, candidates, SPICYVPN_AUTO_SUB_ID, "SpicyVPN Auto Candidates")

        if (measured.isNotEmpty()) {
            val measuredByUrl = measured.associateBy { it.url }
            val activeUrl = MmkvManager.decodeSettingsString("pref_selected_server_url")
            val nextReady = readyCandidates.map { measuredByUrl[it.url] ?: it }.toMutableList()

            // A reserve node is promoted only after it has passed a real test on this
            // device. Keep the active node in place so a healthy connection is never cut.
            measured.filter { measuredNode -> measuredNode.ping > 0 && readyCandidates.none { it.url == measuredNode.url } }
                .sortedBy { it.ping }
                .forEach { promotion ->
                    val replaceable = nextReady
                        .filter { it.url != activeUrl }
                        .maxByOrNull { if (it.ping > 0) it.ping else Int.MAX_VALUE }
                    if (replaceable != null && (replaceable.ping <= 0 || promotion.ping < replaceable.ping)) {
                        nextReady.remove(replaceable)
                        nextReady.add(promotion)
                        autoReserveProxies = (autoReserveProxies + replaceable)
                            .filterNot { it.url == promotion.url }
                            .distinctBy { it.url }
                    }
                }

            _autoConnectProxies.value = nextReady
                .sortedWith(compareBy<ProxyNode> { if (it.ping > 0) 0 else 1 }.thenBy { it.ping })

            val connectionMode = MmkvManager.decodeSettingsString("pref_connection_mode") ?: "auto"
            if (connectionMode != "auto" ||
                System.currentTimeMillis() - autoConnectionStartedAtMs < INITIAL_HEALTH_CHECK_DELAY_MS) {
                maintainManualRanking(context)
                return
            }

            val runningGuid = MmkvManager.getSelectServer()
            if (runningGuid != null) {
                val runningConfig = MmkvManager.decodeServerConfig(runningGuid)
                if (runningConfig != null) {
                    val activeResult = measured.firstOrNull { it.url == activeUrl }
                    val best = measured.filter { it.ping > 0 }.minByOrNull { it.ping }
                    if (activeResult != null && activeResult.ping <= 0 && best != null) {
                        LogUtil.w(TAG, "Running server failed health check — failing over")
                        val guid = importProxy(context, best.url, ensureSubscription(context))
                        if (guid != null) {
                            MmkvManager.setSelectServer(guid)
                            MmkvManager.encodeSettings("pref_selected_server_url", best.url)
                            autoConnectionStartedAtMs = System.currentTimeMillis()
                            updateSelectedServerGuid()
                            _pendingNodeGuid.value = guid
                            CoreServiceManager.stopVService(context)
                        }
                    }
                }
            }
        }

        maintainManualRanking(context)
    }

    private suspend fun measureCandidates(
        context: Context,
        candidates: List<ProxyNode>,
        subId: String,
        remarks: String
    ): List<ProxyNode> {
        if (candidates.isEmpty()) return emptyList()
        if (MmkvManager.decodeSubscription(subId) == null) {
            MmkvManager.encodeSubscription(subId, SubscriptionItem(remarks = remarks, url = "", enabled = true, autoUpdate = false))
        }
        val delayUrl = com.v2ray.ang.handler.SettingsManager.getDelayTestUrl().takeIf { it.isNotEmpty() }
            ?: "https://www.gstatic.com/generate_204"

        return candidates.map { node ->
            AngConfigManager.importBatchConfig(node.url, subId, false)
            val guid = MmkvManager.decodeServerList(subId).firstOrNull()
                ?: return@map node.copy(ping = -1, localPing = true)
            val configResult = com.v2ray.ang.core.CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!configResult.status) return@map node.copy(ping = -1, localPing = true)
            val delay = com.v2ray.ang.core.CoreNativeManager.measureUsableOutbound(configResult.content, delayUrl)
            node.copy(ping = if (delay > 0 && delay < Int.MAX_VALUE) delay.toInt() else -1, localPing = true)
        }
    }

    private suspend fun maintainManualRanking(context: Context) {
        val now = System.currentTimeMillis()
        if (!hasManualPingTest || _isTestingManual.value || now - lastManualProbeAtMs < MANUAL_PROBE_INTERVAL_MS) return
        val visible = _manualProxies.value
        if (visible.isEmpty()) return
        lastManualProbeAtMs = now

        val visibleProbe = rotatingProbe(visible, MANUAL_VISIBLE_PROBE_COUNT, manualVisibleProbeCursor)
        manualVisibleProbeCursor = (manualVisibleProbeCursor + visibleProbe.size) % visible.size
        val reserveProbe = rotatingProbe(manualReserveProxies, MANUAL_RESERVE_PROBE_COUNT, manualReserveProbeCursor)
        if (manualReserveProxies.isNotEmpty()) {
            manualReserveProbeCursor = (manualReserveProbeCursor + reserveProbe.size) % manualReserveProxies.size
        }
        val measured = measureCandidates(
            context,
            visibleProbe + reserveProbe,
            "spicyvpn-manual-background-test",
            "SpicyVPN Manual Background Test"
        )
        if (measured.isEmpty()) return

        val updates = measured.associateBy { it.url }
        val updatedVisible = visible.map { updates[it.url] ?: it }
        val updatedReserve = manualReserveProxies.map { updates[it.url] ?: it }
        pinnedManualProxies = pinnedManualProxies.map { updates[it.url] ?: it }
        val promotedReserve = updatedReserve.filter { it.localPing && it.ping > 0 }
        val pinnedUrls = pinnedManualProxies.map { it.url }.toSet()
        val candidatePool = (updatedVisible + promotedReserve)
            .filterNot { it.url in pinnedUrls }
            .distinctBy { it.url }
        val starred = _starredServers.value
        val ordered = candidatePool.sortedWith(
            compareBy<ProxyNode> { if (it.url in starred) 0 else 1 }
                .thenBy { if (it.ping > 0) it.ping else Int.MAX_VALUE }
        )
        val remaining = (50 - pinnedManualProxies.size).coerceAtLeast(0)
        _manualProxies.value = labelManualNodes(pinnedManualProxies + ordered.take(remaining))
        sortManualProxies()
        val visibleUrls = _manualProxies.value.map { it.url }.toSet()
        manualReserveProxies = (updatedReserve + updatedVisible.filterNot { it.url in visibleUrls })
            .filterNot { it.url in visibleUrls }
            .distinctBy { it.url }
    }

    private fun nextAutoReserveProbe(): List<ProxyNode> {
        val probe = rotatingProbe(autoReserveProxies, AUTO_RESERVE_PROBE_COUNT, autoReserveProbeCursor)
        if (autoReserveProxies.isNotEmpty()) {
            autoReserveProbeCursor = (autoReserveProbeCursor + probe.size) % autoReserveProxies.size
        }
        return probe
    }

    private fun rotatingProbe(nodes: List<ProxyNode>, count: Int, start: Int): List<ProxyNode> {
        if (nodes.isEmpty()) return emptyList()
        return List(minOf(count, nodes.size)) { index -> nodes[(start + index) % nodes.size] }
    }

    private fun findImportedGuid(context: Context, url: String, subId: String): String? {
        val servers = MmkvManager.decodeServerList(subId)
        for (guid in servers) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val (host, port) = extractHostPort(url) ?: continue
            if (profile.server == host && profile.serverPort == port.toString()) {
                return guid
            }
        }
        return null
    }

    // --------------------------------------------------------------- ping helpers

    private suspend fun isProxyAlive(url: String): Boolean = withContext(Dispatchers.IO) {
        measureLatency(url) < Int.MAX_VALUE
    }

    private fun measureLatency(url: String): Int {
        val (host, port) = extractHostPort(url) ?: return Int.MAX_VALUE
        return try {
            val start = System.currentTimeMillis()
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 3000)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }

    /** Public wrapper to extract just the host from a URL (for UI use). */
    fun extractHostFromUrl(url: String): String = extractHostPort(url)?.first ?: ""

    private fun extractHostPort(url: String): Pair<String, Int>? {
        return try {
            if (url.startsWith("vmess://")) {
                val b64 = url.removePrefix("vmess://").substringBefore('#').trim()
                val paddedB64 = when (b64.length % 4) {
                    2 -> "$b64=="
                    3 -> "$b64="
                    else -> b64
                }
                val decodedBytes = try {
                    android.util.Base64.decode(paddedB64, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    try {
                        android.util.Base64.decode(paddedB64, android.util.Base64.URL_SAFE)
                    } catch (e2: Exception) {
                        null
                    }
                }
                if (decodedBytes != null) {
                    val json = String(decodedBytes)
                    val obj = JSONObject(json)
                    val port = obj.optInt("port", -1).takeIf { it > 0 }
                        ?: obj.optString("port").toIntOrNull()
                        ?: 0
                    return Pair(obj.getString("add"), port)
                }
            }
            
            val cleanUrl = url.substringAfter("://").substringBefore('#').trim()
            val authorityPart = cleanUrl.substringBefore('?')
            val hostPortPart = if (authorityPart.contains('@')) {
                authorityPart.substringAfterLast('@')
            } else {
                authorityPart
            }.substringBefore('/')
             .substringBefore('\n')
             .trim()
             
            val lastColon = hostPortPart.lastIndexOf(':')
            if (lastColon >= 0) {
                val host = hostPortPart.substring(0, lastColon).removePrefix("[").removeSuffix("]")
                val port = hostPortPart.substring(lastColon + 1).toIntOrNull()
                if (host.isNotEmpty() && port != null && port > 0) {
                    return Pair(host, port)
                }
            }
            null
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to extractHostPort from $url", e)
            null
        }
    }

    // --------------------------------------------------------------- helpers

    private fun extractServerName(url: String): String {
        return try {
            val fragment = url.substringAfter('#', "")
            if (fragment.isNotEmpty()) {
                java.net.URLDecoder.decode(fragment, "UTF-8").trim()
            } else {
                val (host, port) = extractHostPort(url) ?: return "Unknown"
                "$host:$port"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun findActivity(context: Context): MainActivity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is MainActivity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return ctx as? MainActivity
    }

    override fun onCleared() {
        cancelAutoSelection()
        super.onCleared()
        stopBackgroundPing()
    }
}

data class ProxyNode(
    val url: String,
    val ping: Int,
    val name: String,
    val country: String = "",
    val localPing: Boolean = false
)
