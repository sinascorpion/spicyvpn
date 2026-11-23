package com.example.spicyvpn.ui.main

import android.widget.Toast
import com.v2ray.ang.R
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    isDarkMode: Boolean,
    onThemeToggle: () -> Unit,
    onItemClick: (String) -> Unit,
    isRunning: Boolean,
    serverDelay: String,
    onConnectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val pendingSharedImport by viewModel.pendingSharedImport.collectAsState()
    LaunchedEffect(pendingSharedImport) { if (pendingSharedImport != null) pagerState.scrollToPage(2) }
    val context = LocalContext.current
    val subscriptionManagerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.syncCustomProxiesFromMmkv(context)
        viewModel.syncCustomProxiesFromSubscriptions(context)
    }
    val selectedServerGuid by viewModel.selectedServerGuid.collectAsState()
    val realExitIp by viewModel.realExitIp
    val realExitCountryCode by viewModel.realExitCountryCode
    
    val showAnnouncementDialog by viewModel.showAnnouncementDialog
    val announcementTitle by viewModel.announcementTitle
    val announcementMessage by viewModel.announcementMessage
    val announcementActionUrl by viewModel.announcementActionUrl

    var showSSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showCustomMenu by remember { mutableStateOf(false) }
    var customDeleteAction by remember { mutableStateOf<String?>(null) }
    var sSettingsActive by remember { mutableStateOf(isSSettingsActive()) }

    if (showSSettings) {
        SSettingsDialog(onDismiss = {
            showSSettings = false
            sSettingsActive = isSSettingsActive()
        })
    }

    if (customDeleteAction != null) {
        AlertDialog(
            onDismissRequest = { customDeleteAction = null },
            title = { Text(stringResource(R.string.custom_delete_confirm)) },
            text = { Text(stringResource(if (customDeleteAction == "invalid") R.string.custom_delete_invalid_hint else R.string.custom_delete_confirm_hint)) },
            confirmButton = { TextButton(onClick = {
                when (customDeleteAction) {
                    "all" -> viewModel.clearCustomProxies(context)
                    "duplicates" -> viewModel.removeCustomDuplicates(context)
                    "invalid" -> viewModel.removeCustomInvalid(context)
                }
                customDeleteAction = null
            }) { Text(stringResource(R.string.custom_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { customDeleteAction = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    if (showAnnouncementDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissAnnouncement(context)
            },
            title = {
                Text(
                    text = announcementTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Text(
                    text = announcementMessage,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                if (announcementActionUrl.isNotEmpty()) {
                    Button(
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(announcementActionUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Ignore
                            }
                            viewModel.dismissAnnouncement(context)
                        }
                    ) {
                        Text("Open Link")
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.dismissAnnouncement(context)
                        }
                    ) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                if (announcementActionUrl.isNotEmpty()) {
                    TextButton(onClick = { viewModel.dismissAnnouncement(context) }) {
                        Text("Close")
                    }
                }
            }
        )
    }


    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(id = R.string.spicyvpn_menu),
                    modifier = Modifier.padding(16.dp),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                val context = LocalContext.current
                NavigationDrawerItem(
                    icon = { Text("🔄") },
                    label = { Text(stringResource(id = R.string.title_sub_setting)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        subscriptionManagerLauncher.launch(android.content.Intent(context, com.v2ray.ang.ui.SubSettingActivity::class.java))
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("🛠") },
                    label = { Text(stringResource(id = R.string.split_tunneling)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        context.startActivity(
                            android.content.Intent(context, com.v2ray.ang.ui.PerAppProxyActivity::class.java)
                        )
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("🌍") },
                    label = { Text(stringResource(id = R.string.country_routing)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onItemClick("country_routing")
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("🛡") },
                    label = { Text(stringResource(id = R.string.dns_settings)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onItemClick("dns_settings")
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("🔗") },
                    label = { Text(stringResource(id = R.string.vpn_tethering)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onItemClick("vpn_tethering")
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("⛓️") },
                    label = { Text(stringResource(id = R.string.proxy_chain)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewModel.syncCustomProxiesFromMmkv(context)
                        val intent = android.content.Intent(context, com.v2ray.ang.ui.ServerProxyChainActivity::class.java)
                            .putExtra("subscriptionId", "spicyvpn-custom-user")
                        context.startActivity(intent)
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("🌐") },
                    label = { Text(stringResource(id = R.string.change_language)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onItemClick("language_selection")
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("📋") },
                    label = { Text("View Logs") },
                    selected = false,
                    onClick = {
                        val intent = android.content.Intent(context, com.v2ray.ang.ui.LogcatActivity::class.java)
                        context.startActivity(intent)
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("📧") },
                    label = { Text(stringResource(id = R.string.contact_support)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:spicyprx@gmail.com")
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "SpicyVPN Support Request")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "No email client found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                NavigationDrawerItem(
                    icon = { Text("ℹ️") },
                    label = { Text(stringResource(id = R.string.about)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showAbout = true
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SpicyVPN", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", fontSize = 24.sp)
                        }
                    },
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showSSettings = true }
                            ) {
                                Text(
                                    text = "S",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = if (sSettingsActive) Color(0xFF00C853) else MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            IconButton(onClick = onThemeToggle) {
                                Text(
                                    text = if (isDarkMode) "☀" else "☾",
                                    fontSize = 24.sp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            if (pagerState.currentPage == 2) Box {
                                IconButton(onClick = { showCustomMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.custom_actions))
                                }
                                DropdownMenu(expanded = showCustomMenu, onDismissRequest = { showCustomMenu = false }) {
                                    listOf("all" to R.string.delete_all, "duplicates" to R.string.custom_delete_duplicates, "invalid" to R.string.custom_delete_invalid).forEach { (action, label) ->
                                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                            showCustomMenu = false
                                            customDeleteAction = action
                                        })
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isRunning) {
                        val warpBeforeState = MmkvManager.decodeSettingsBool("pref_warp_before", false)
                        val warpAfterState = MmkvManager.decodeSettingsBool("pref_warp_after", false)

                        if (realExitIp.isNotBlank() && pagerState.currentPage != 0) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                ConnectionAddress(realExitIp, realExitCountryCode)
                            }
                        }

                        val bannerText = when {
                            false -> {
                                val countryCode = MmkvManager.decodeSettingsString("pref_psiphon_country") ?: "Auto"
                                val countryTriple = psiphonCountries.find { it.first == countryCode } ?: Triple("Auto", "Auto", "🌐")
                                "Connected to Psiphon: ${countryTriple.third} ${countryTriple.first}"
                            }
                            false -> {
                                val countryCode = MmkvManager.decodeSettingsString("pref_psiphon_country") ?: "Auto"
                                val countryTriple = psiphonCountries.find { it.first == countryCode } ?: Triple("Auto", "Auto", "🌐")
                                "Connected to SpicyVPN + Psiphon (${countryTriple.third} ${countryTriple.first})"
                            }
                            warpBeforeState || warpAfterState -> {
                                "Connected to Warp"
                            }
                            else -> null
                        }

                        if (bannerText != null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                color = Color(0xFF1B5E20), // Dark green background like premium VPN
                                contentColor = Color.White
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = bannerText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (com.example.spicyvpn.ads.AdTrafficPolicy.canRequest(
                                isRunning, MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_DNS_ONLY_MODE))) {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { context ->
                                try {
                                    AdView(context).apply {
                                        val displayMetrics = context.resources.displayMetrics
                                        val adWidthPixels = displayMetrics.widthPixels
                                        val density = displayMetrics.density
                                        val adWidth = (adWidthPixels / density).toInt()
                                        setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth))
                                        
                                        adUnitId = if (com.v2ray.ang.BuildConfig.DEBUG) {
                                            "ca-app-pub-3940256099942544/6300978111"
                                        } else {
                                            "ca-app-pub-5779811685096109/4957778780"
                                        }
                                        layoutParams = android.view.ViewGroup.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                        )
                                        adListener = object : com.google.android.gms.ads.AdListener() {
                                            override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                                                com.v2ray.ang.util.LogUtil.w("SpicyVPN_Ads", "Banner load failed (${error.code}, ${error.domain}): ${error.message}")
                                            }
                                            override fun onAdLoaded() {
                                                com.v2ray.ang.util.LogUtil.i("SpicyVPN_Ads", "Banner loaded successfully")
                                            }
                                        }
                                        loadAd(AdRequest.Builder().build())
                                    }
                                } catch (e: Exception) {
                                    com.v2ray.ang.util.LogUtil.e("SpicyVPN_Ads", "Failed to create AdView", e)
                                    android.view.View(context)
                                }
                            },
                            onRelease = { view -> (view as? AdView)?.destroy() }
                        )
                        }
                    }
                }
            }
        ) { paddingValues ->
            val tabIndex = pagerState.currentPage

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text(stringResource(id = R.string.auto_connect)) }
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text(stringResource(id = R.string.manual_connect)) }
                    )
                    Tab(
                        selected = tabIndex == 2,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                        text = { Text(stringResource(id = R.string.custom_connect)) }
                    )
                }

                if (isRunning && tabIndex != 0) TrafficSpeedRow(viewModel)

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    when (page) {
                        0 -> AutoConnectScreen(
                            isRunning = isRunning,
                            serverDelay = serverDelay,
                            onConnectClick = { onConnectClick(0) },
                            viewModel = viewModel
                        )
                        1 -> ManualConnectScreen(
                            isRunning = isRunning,
                            onConnectClick = { onConnectClick(1) },
                            viewModel = viewModel
                        )
                        2 -> CustomConnectScreen(
                            isRunning = isRunning,
                            onConnectClick = { onConnectClick(2) },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

private fun localizedConnectionGuideTitle(): String = when (Locale.getDefault().language) {
    "fa" -> "اتصال به SpicyVPN"
    "ru" -> "Подключение к SpicyVPN"
    "zh" -> "连接 SpicyVPN"
    else -> "Connect to SpicyVPN"
}

private fun localizedConnectionGuideMessage(): String = when (Locale.getDefault().language) {
    "fa" -> "در بخش اتصال خودکار، دکمه Connect را بزنید تا برنامه بهترین سرور آماده را انتخاب کند. در انتخاب دستی، یک سرور را انتخاب کنید و دکمه پخش آن را بزنید."
    "ru" -> "В разделе автоподключения нажмите Connect, чтобы выбрать готовый сервер. В ручном режиме выберите сервер и нажмите кнопку воспроизведения."
    "zh" -> "在自动连接页面点击 Connect，应用会选择可用服务器。在手动选择页面，选择服务器后点击播放按钮即可连接。"
    else -> "On Auto Connect, tap Connect to use the best ready server. On Manual Selection, choose a server and tap its play button to connect."
}

private fun localizedConnectionGuideAction(): String = when (Locale.getDefault().language) {
    "fa" -> "متوجه شدم"
    "ru" -> "Понятно"
    "zh" -> "知道了"
    else -> "Got it"
}

@Composable
fun AutoConnectScreen(
    isRunning: Boolean,
    serverDelay: String,
    onConnectClick: () -> Unit,
    viewModel: MainScreenViewModel
) {
    var connectionState by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(key1 = Unit) {
        viewModel.initContext(context)
    }

    LaunchedEffect(isRunning) {
        connectionState = if (isRunning) 2 else 0
    }

    val autoProxies by viewModel.autoConnectProxies.collectAsState()
    val isFindingAuto by viewModel.isFindingAuto.collectAsState()
    val visibleConnectionState = if (isFindingAuto && !isRunning) 1 else connectionState

    val statusDisconnected = stringResource(id = R.string.status_disconnected)
    val statusConnecting = stringResource(id = R.string.status_connecting)
    val statusConnected = stringResource(id = R.string.status_connected)

    val statusText = when (visibleConnectionState) {
        0 -> statusDisconnected
        1 -> statusConnecting
        2 -> statusConnected
        else -> ""
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        ConnectButton(visibleConnectionState, onClick = onConnectClick)
        Spacer(modifier = Modifier.height(16.dp))
        Text(statusText, style = MaterialTheme.typography.titleMedium)

        if (connectionState == 2) {
            TrafficSpeedRow(viewModel)
            ConnectedServerCard(viewModel = viewModel, nodes = autoProxies)
        }

    }
}


@Composable
fun ConnectedServerCard(
    viewModel: MainScreenViewModel,
    nodes: List<ProxyNode>
) {
    val selectedServerGuid by viewModel.selectedServerGuid.collectAsState()
    val activeNode = remember(nodes, selectedServerGuid) { getActiveNode(viewModel, nodes) }
    val vpnDelay by viewModel.vpnDelay
    val realExitIp by viewModel.realExitIp
    val realExitCountryCode by viewModel.realExitCountryCode

    // Automatically measure delay once when connected
    LaunchedEffect(Unit) {
        if (vpnDelay.isEmpty() || vpnDelay == "Fail" || vpnDelay == "Testing...") {
            viewModel.testCurrentVpnDelay()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                val exitCountry = realExitCountryCode.takeIf { it.length == 2 }
                if (exitCountry != null) {
                    Text(countryCodeToFlagEmoji(exitCountry), fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(getCountryName(exitCountry), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                } else if (false) {
                    val countryCode = MmkvManager.decodeSettingsString("pref_psiphon_country") ?: "Auto"
                    val countryTriple = psiphonCountries.find { it.first == countryCode } ?: Triple("Auto", "Auto", "🌐")
                    Text(countryTriple.third, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Psiphon: ${countryTriple.first}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                } else {
                    Text(stringResource(R.string.custom_connected_server), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = activeNode?.name?.ifBlank { "SpicyVPN" } ?: "SpicyVPN",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (realExitIp.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                ConnectionAddress(realExitIp, "")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .clickable { viewModel.testCurrentVpnDelay() }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ping (HTTP): ", fontSize = 14.sp)
                Text(
                    text = if (vpnDelay.isEmpty()) "Tap to test" else vpnDelay,
                    fontWeight = FontWeight.Bold,
                    color = if (vpnDelay == "Fail") Color.Red else Color.Green,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Ping",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectionAddress(ip: String, countryCode: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = ip,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (countryCode.length == 2 && countryCode.all { it.isLetter() }) {
            Text(
                text = "${countryCodeToFlagEmoji(countryCode)}  ${getCountryName(countryCode)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

fun getActiveServerIp(): String {
    val selectGuid = MmkvManager.getSelectServer() ?: return ""
    val config = MmkvManager.decodeServerConfig(selectGuid) ?: return ""
    return config.server ?: ""
}

fun getActiveNode(viewModel: MainScreenViewModel, nodes: List<ProxyNode>): ProxyNode? {
    val selectUrl = MmkvManager.decodeSettingsString("pref_selected_server_url")
    if (!selectUrl.isNullOrEmpty()) {
        val found = nodes.find { it.url == selectUrl }
        if (found != null) return found
    }

    val selectGuid = MmkvManager.getSelectServer() ?: return null
    val config = MmkvManager.decodeServerConfig(selectGuid) ?: return null
    val found = nodes.find { node ->
        node.url.contains(config.server ?: "") || node.name == config.remarks
    }
    if (found != null) return found

    return ProxyNode(
        url = config.server.orEmpty(),
        ping = 0,
        name = config.remarks ?: "",
        country = ""
    )
}

@Composable
fun ConnectButton(connectionState: Int, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition()
    val blinkingColor by infiniteTransition.animateColor(
        initialValue = Color.Red,
        targetValue = Color.Transparent,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing), repeatMode = RepeatMode.Reverse
        )
    )

    val textColor = when (connectionState) {
        0 -> MaterialTheme.colorScheme.primary
        1 -> blinkingColor
        2 -> Color.Green
        else -> Color.Black
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .border(2.dp, if (connectionState == 2) Color.Green else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                .clickable { onClick() }
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Connect Logo Button",
                modifier = Modifier.fillMaxSize(),
                colorFilter = if (connectionState == 2) null else ColorFilter.tint(MaterialTheme.colorScheme.outline)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        val buttonText = when (connectionState) {
            0 -> stringResource(id = R.string.connect)
            1 -> stringResource(id = R.string.status_connecting)
            2 -> stringResource(id = R.string.disconnect)
            else -> ""
        }
        Text(
            buttonText,
            color = textColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// --------------------------------------------------------------- helpers

/** Convert a 2-letter country code (e.g. "US") to a flag emoji (e.g. 🇺🇸). */
fun countryCodeToFlagEmoji(code: String): String {
    if (code.length != 2) return "🏳️"
    val upper = code.uppercase(Locale.US)
    if (!upper.all { it in 'A'..'Z' }) return "🏳️"
    // Regional indicator symbols: each letter offset from 'A' by the magic number 0x1F1E6
    val first = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

/** Map ISO country codes to display names. Returns the code itself if unknown. */
fun getCountryName(code: String): String {
    return try {
        val locale = java.util.Locale.Builder().setLanguage("en").setRegion(code).build()
        locale.displayCountry
    } catch (e: Exception) {
        code
    }
}

@Composable
fun ManualConnectScreen(
    isRunning: Boolean,
    onConnectClick: () -> Unit,
    viewModel: MainScreenViewModel
) {
    val context = LocalContext.current
    val manualNodes by viewModel.manualProxies.collectAsState()
    val isTesting by viewModel.isTestingManual.collectAsState()
    val starredServers by viewModel.starredServers.collectAsState()
    val selectedServerGuid by viewModel.selectedServerGuid.collectAsState()

    val activeNode = remember(manualNodes, selectedServerGuid, MmkvManager.decodeSettingsBool("pref_psiphon_only", false)) { getActiveNode(viewModel, manualNodes) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.refresh(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(id = R.string.get_config))
                }

                Button(
                    onClick = { viewModel.testManualProxies(context) },
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(if (isTesting) stringResource(id = R.string.connection_test_testing) else stringResource(id = R.string.test_ping))
                }

                Button(
                    onClick = { viewModel.sortManualProxies() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text(stringResource(id = R.string.sort_ping))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Flat Server List
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(manualNodes.size) { index ->
                    val node = manualNodes[index]
                    val isSelected = activeNode?.url == node.url
                    val isStarred = starredServers.contains(node.url)
                    val hasCountry = node.country.matches(Regex("[A-Za-z]{2}")) && node.country.uppercase() != "ZZ"
                    val flag = if (hasCountry) countryCodeToFlagEmoji(node.country.uppercase()) else getCountryFlagEmoji("")

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                viewModel.selectNode(context, node)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                                             else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = flag, fontSize = 24.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = com.v2ray.ang.handler.NotificationManager.cleanRemarksAndGetDisplay(node.name),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (hasCountry) {
                                        Text(text = getCountryName(node.country.uppercase()), fontSize = 12.sp)
                                    }
                                    val pingText = when (node.ping) {
                                        -2 -> stringResource(id = R.string.connection_test_testing)
                                        -1 -> "Timeout"
                                        0 -> "Tap to test"
                                        else -> "${node.ping}ms"
                                    }
                                    val pingColor = when (node.ping) {
                                        -2 -> Color(0xFFFFA500) // Orange
                                        -1 -> Color.Red
                                        0 -> Color.Gray
                                        else -> Color(0xFF4CAF50) // Green
                                    }
                                    Text(text = pingText, color = pingColor, fontSize = 13.sp)
                                }
                            }
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Star/Fav Button
                                IconButton(
                                    onClick = { viewModel.toggleStar(context, node.url) }
                                ) {
                                    Text(
                                        text = if (isStarred) "⭐" else "☆",
                                        fontSize = 20.sp
                                    )
                                }
                                
                                // Play/Stop Connection Button
                                IconButton(
                                    onClick = {
                                        if (isSelected && isRunning) {
                                            com.v2ray.ang.core.CoreServiceManager.stopVService(context)
                                        } else {
                                            viewModel.connectToNode(context, node, isRunning)
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (isSelected && isRunning) "⏸" else "▶",
                                        fontSize = 20.sp,
                                        color = if (isSelected && isRunning) Color.Red else Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Small Logo Connect Button at bottom right above the banner
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 2.dp,
                    color = if (isRunning) Color.Green else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .clickable { onConnectClick() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Float Connect Logo Button",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape),
                colorFilter = if (isRunning) null else ColorFilter.tint(MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CustomConnectScreen(
    isRunning: Boolean,
    onConnectClick: () -> Unit,
    viewModel: MainScreenViewModel
) {
    val context = LocalContext.current
    val customNodes by viewModel.customProxies.collectAsState()
    val groupPrefs = remember(context) { context.getSharedPreferences("spicyvpn_custom_groups", android.content.Context.MODE_PRIVATE) }
    var expandedSubscriptions by remember { mutableStateOf(groupPrefs.getStringSet("expanded_custom_subscriptions", emptySet()).orEmpty().toSet()) }
    var refreshingSubscriptions by remember { mutableStateOf(emptySet<String>()) }
    val subscriptionScope = rememberCoroutineScope()
    var organization by remember { mutableStateOf(
        com.v2ray.ang.util.JsonUtil.fromJsonSafe(groupPrefs.getString("organization", "{}").orEmpty(), CustomOrganization::class.java) ?: CustomOrganization()
    ) }
    fun saveOrganization(next: CustomOrganization) {
        organization = next
        groupPrefs.edit().putString("organization", com.v2ray.ang.util.JsonUtil.toJson(next)).apply()
    }
    val latestOrganization by rememberUpdatedState(organization)
    val customListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var sharing by remember { mutableStateOf<Pair<SharedFolder, String?>?>(null) }
    var incomingLink by remember { mutableStateOf<String?>(null) }
    var incomingFolder by remember { mutableStateOf<SharedFolder?>(null) }
    var unlockedFolders by remember { mutableStateOf(setOf<String>()) }
    var unlockingFolder by remember { mutableStateOf<CustomFolder?>(null) }
    var editPassword by remember { mutableStateOf("") }
    var editPasswordError by remember { mutableStateOf(false) }
    unlockingFolder?.let { folder ->
        AlertDialog(onDismissRequest = { unlockingFolder = null }, title = { Text("Unlock editing") },
            text = { OutlinedTextField(value = editPassword, onValueChange = { editPassword = it; editPasswordError = false },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), isError = editPasswordError) },
            confirmButton = { TextButton(onClick = {
                if (SpicyShareCodec.unlockEdit(folder.editLock.orEmpty(), editPassword)) {
                    unlockedFolders = unlockedFolders + folder.id; unlockingFolder = null; editPassword = ""
                } else editPasswordError = true
            }) { Text("Unlock") } }, dismissButton = { TextButton(onClick = { unlockingFolder = null; editPassword = "" }) { Text(stringResource(R.string.cancel)) } })
    }
    var importPassword by remember { mutableStateOf("") }
    var shareImportError by remember { mutableStateOf<String?>(null) }
    var decodingBundle by remember { mutableStateOf(false) }
    sharing?.let { (folder, guid) -> CustomShareDialog(folder, guid) { sharing = null } }
    if (incomingLink != null) AlertDialog(onDismissRequest = { incomingLink = null },
        title = { Text("Import shared folder") }, text = { Column {
            OutlinedTextField(importPassword, { importPassword = it; shareImportError = null }, label = { Text("Password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true)
            shareImportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !decodingBundle, onClick = {
            val link = incomingLink ?: return@TextButton
            decodingBundle = true
            subscriptionScope.launch {
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SpicyShareCodec.decode(link, importPassword) } }
                    .onSuccess { incomingFolder = it; incomingLink = null; importPassword = "" }
                    .onFailure { shareImportError = "Incorrect password or invalid shared folder" }
                decodingBundle = false
            }
        }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { incomingLink = null }) { Text(stringResource(R.string.cancel)) } })
    incomingFolder?.let { folder -> AlertDialog(onDismissRequest = { incomingFolder = null }, title = { Text(folder.name) },
        text = { Column {
            Text("${folder.profiles.size} configurations")
            if (folder.message.isNotEmpty()) AndroidView(factory = { android.widget.TextView(it).apply {
                autoLinkMask = android.text.util.Linkify.WEB_URLS
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setTextColor(android.graphics.Color.GRAY)
            } }, update = { it.text = folder.message }, modifier = Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(onClick = {
            val urls = viewModel.importSharedFolder(context, folder)
            val id = java.util.UUID.randomUUID().toString()
            saveOrganization(organization.copy(folders = organization.folders + CustomFolder(id, folder.name, urls, editLock = folder.editLock)))
            expandedSubscriptions = expandedSubscriptions + "folder:$id"
            groupPrefs.edit().putStringSet("expanded_custom_subscriptions", expandedSubscriptions).apply()
            incomingFolder = null
        }) { Text("Import") } }, dismissButton = { TextButton(onClick = { incomingFolder = null }) { Text(stringResource(R.string.cancel)) } }) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var organizingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var showIkev2Editor by remember { mutableStateOf(false) }
    var ikev2EditingNode by remember { mutableStateOf<ProxyNode?>(null) }
    if (showIkev2Editor) Ikev2EditorDialog(
        profile = ikev2EditingNode?.let(viewModel::findGuidForNode)?.let(MmkvManager::decodeServerConfig),
        onSave = { name, link ->
            ikev2EditingNode?.let { viewModel.editCustomRawConfig(context, it, name, link) }
                ?: (viewModel.importCustomConfigs(context, link, name) > 0)
        }, onDismiss = { showIkev2Editor = false; ikev2EditingNode = null })
    var showDnsOnlyDialog by remember { mutableStateOf(false) }
    var dnsOnlyName by remember { mutableStateOf("") }
    var dnsOnlyAddress by remember { mutableStateOf("") }
    var dnsOnlyEditing by remember { mutableStateOf<ProxyNode?>(null) }
    var dnsOnlyError by remember { mutableStateOf(false) }
    if (showDnsOnlyDialog) AlertDialog(onDismissRequest = { showDnsOnlyDialog = false },
        title = { Text(stringResource(R.string.custom_dns_only)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = dnsOnlyName, onValueChange = { dnsOnlyName = it }, label = { Text(stringResource(R.string.config_name)) }, singleLine = true)
            OutlinedTextField(value = dnsOnlyAddress, onValueChange = { dnsOnlyAddress = it; dnsOnlyError = false }, label = { Text(stringResource(R.string.custom_dns_address)) },
                placeholder = { Text("1.1.1.1 / udp://1.1.1.1:53 / tcp://1.1.1.1 / tls://dns.google / https://dns.google/dns-query") }, isError = dnsOnlyError)
        } }, confirmButton = { TextButton(onClick = {
            runCatching { viewModel.saveDnsOnlyNode(context, dnsOnlyName, dnsOnlyAddress, dnsOnlyEditing) }
                .onSuccess { showDnsOnlyDialog = false }.onFailure { dnsOnlyError = true }
        }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { showDnsOnlyDialog = false }) { Text(stringResource(R.string.cancel)) } })
    if (showCreateFolder) {
        AlertDialog(onDismissRequest = { showCreateFolder = false },
            title = { Text(stringResource(R.string.custom_create_folder)) },
            text = { OutlinedTextField(value = folderName, onValueChange = { folderName = it }, singleLine = true,
                label = { Text(stringResource(R.string.custom_folder_name)) }) },
            confirmButton = { TextButton(enabled = folderName.isNotBlank(), onClick = {
                saveOrganization(organization.copy(folders = organization.folders + CustomFolder(java.util.UUID.randomUUID().toString(), folderName.trim())))
                folderName = ""; showCreateFolder = false
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text(stringResource(R.string.cancel)) } })
    }
    organizingNode?.let { node ->
        AlertDialog(onDismissRequest = { organizingNode = null },
            title = { Text(node.name, maxLines = 2) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { saveOrganization(organization.pin(node.url)); organizingNode = null }) {
                    Icon(painterResource(android.R.drawable.btn_star_big_on), contentDescription = null)
                    Text(stringResource(if (node.url in organization.pinnedUrls) R.string.custom_unpin else R.string.custom_pin))
                }
                TextButton(onClick = { saveOrganization(organization.moveToFolder(node.url, null)); organizingNode = null }) { Text(stringResource(R.string.custom_remove_folder)) }
                organization.folders.forEach { folder ->
                    TextButton(onClick = { saveOrganization(organization.moveToFolder(node.url, folder.id)); organizingNode = null }) {
                        Icon(painterResource(android.R.drawable.ic_menu_agenda), contentDescription = null); Text(folder.name)
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { organizingNode = null }) { Text(stringResource(R.string.cancel)) } })
    }
    val isTesting by viewModel.isTestingCustom.collectAsState()
    val isImporting by viewModel.isImportingCustom.collectAsState()
    val selectedServerGuid by viewModel.selectedServerGuid.collectAsState()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    fun importCustomText(content: String, defaultName: String? = null) {
        if (content.isBlank() || isImporting) return
        if (content.trim().startsWith(SpicyShareCodec.PREFIX)) {
            val link = content.trim()
            if (SpicyShareCodec.encrypted(link)) { incomingLink = link; importPassword = ""; shareImportError = null }
            else runCatching { SpicyShareCodec.decode(link) }.onSuccess { incomingFolder = it }
                .onFailure { Toast.makeText(context, "Invalid shared folder", Toast.LENGTH_LONG).show() }
            return
        }
        coroutineScope.launch {
            val count = viewModel.importCustomConfigsAsync(context, content, defaultName)
            Toast.makeText(
                context,
                when {
                    count > 0 -> context.getString(R.string.custom_config_added) + " ($count)"
                    count < 0 -> "Subscription saved, but its server list could not be downloaded. Check the URL or network and update it from Subscription settings."
                    else -> context.getString(R.string.custom_config_invalid)
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val pendingBundle by viewModel.pendingSharedImport.collectAsState()
    LaunchedEffect(pendingBundle) {
        pendingBundle?.let { importCustomText(it); viewModel.pendingSharedImport.value = null }
    }

    val activeNode = remember(customNodes, selectedServerGuid) {
        val selectUrl = MmkvManager.decodeSettingsString("pref_selected_server_url")
        if (!selectUrl.isNullOrEmpty()) {
            customNodes.find { it.url == selectUrl }
        } else {
            val selectGuid = MmkvManager.getSelectServer()
            val activeConfig = selectGuid?.let { MmkvManager.decodeServerConfig(it) }
            customNodes.find { it.url.contains(activeConfig?.server ?: "") || it.name == activeConfig?.remarks }
        }
    }

    // File picker for import
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                var fileName: String? = null
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore and fallback
                }
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }.orEmpty()
                if (content.isNotBlank()) {
                    importCustomText(content, fileName)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Error reading file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ServerActivity launcher — sync proxies on return
    val serverActivityLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.syncCustomProxiesFromMmkv(context)
        viewModel.syncCustomProxiesFromSubscriptions(context)
    }

    // QR Scanner launcher
    val qrScannerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanResult = result.data?.getStringExtra("SCAN_RESULT")
                ?: result.data?.getStringExtra("result")
            if (!scanResult.isNullOrBlank()) {
                importCustomText(scanResult)
            }
        }
    }

    // Camera permission request launcher
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                val intent = android.content.Intent(context, com.v2ray.ang.ui.ScannerActivity::class.java)
                qrScannerLauncher.launch(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Error opening scanner: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.toast_permission_denied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // --- Dialog state ---
    var showAddConfigMenu by remember { mutableStateOf(false) }
    var showManualProtocolDialog by remember { mutableStateOf(false) }
    // SSH dialog
    var showSshDialog by remember { mutableStateOf(false) }
    var newSshTls by remember { mutableStateOf(false) }
    var manualNativeProtocol by remember { mutableStateOf<String?>(null) }
    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
    // DNSTT dialog
    var showDnsttDialog by remember { mutableStateOf(false) }
    // OpenVPN / AmneziaWG text editor
    var showTextEditorDialog by remember { mutableStateOf(false) }
    var textEditorProtocol by remember { mutableStateOf("") }
    var textEditingNode by remember { mutableStateOf<ProxyNode?>(null) }
    // Rename dialog (for editing name of a node)
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameNode by remember { mutableStateOf<ProxyNode?>(null) }
    // OpenVPN Auth Prompt Dialog (when connecting or editing)
    var showOvpnAuthDialog by remember { mutableStateOf(false) }
    var ovpnAuthNode by remember { mutableStateOf<ProxyNode?>(null) }
    var ovpnAuthIsConnecting by remember { mutableStateOf(false) }
    // Clear all custom configs confirm dialog
    var showClearAllConfirmDialog by remember { mutableStateOf(false) }

    // ===================== DIALOGS =====================

    // ---- Clear All Custom Nodes Confirmation Dialog ----
    if (showClearAllConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmDialog = false },
            title = { Text(stringResource(R.string.clear_custom_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.clear_custom_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearCustomProxies(context)
                        showClearAllConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_all), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // State for OpenVPN/AmneziaWG/WireGuard import source choice
    var showSourceChoiceDialog by remember { mutableStateOf(false) }
    var chosenSourceProtocol by remember { mutableStateOf("") }

    // ---- Source Choice Dialog (File vs Text) ----
    if (showSourceChoiceDialog) {
        val proto = chosenSourceProtocol
        AlertDialog(
            onDismissRequest = { showSourceChoiceDialog = false },
            title = { Text(stringResource(R.string.import_choose_source) + " - ${proto.uppercase()}", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showSourceChoiceDialog = false
                            try { filePickerLauncher.launch("*/*") } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Cannot open file picker", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.import_from_file_opt), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showSourceChoiceDialog = false
                            textEditorProtocol = proto
                            showTextEditorDialog = true
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.import_from_text_opt), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSourceChoiceDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // ---- SSH Dialog (with Stunnel / TLS & SNI) ----
    if (showSshDialog) {
        val existing = editingNode
        val existingConfig = existing?.let { node ->
            viewModel.findGuidForNode(node)?.let { guid -> MmkvManager.decodeServerConfig(guid) }
        }
        val initialHost = existingConfig?.server
            ?: existing?.let { viewModel.extractHostFromUrl(it.url) }
            ?: ""
        val initialPort = existingConfig?.serverPort?.takeIf { it.isNotBlank() }
            ?: "22"
        val initialUser = existingConfig?.username.orEmpty().ifEmpty {
            val url = existing?.url.orEmpty()
            if (url.startsWith("ssh://") && url.contains("@")) {
                val auth = url.substringAfter("ssh://").substringBefore("@")
                auth.substringBefore(":")
            } else ""
        }
        val initialPass = existingConfig?.password.orEmpty().ifEmpty {
            val url = existing?.url.orEmpty()
            if (url.startsWith("ssh://") && url.contains("@")) {
                val auth = url.substringAfter("ssh://").substringBefore("@")
                if (auth.contains(":")) auth.substringAfter(":") else ""
            } else ""
        }
        val initialPrivateKey = existingConfig?.sshPrivateKey.orEmpty()
        val initialKeyPassphrase = existingConfig?.sshKeyPassphrase.orEmpty()
        val initialRemarks = existingConfig?.remarks
            ?: existing?.name
            ?: ""
        val initialEnableTls = (existing == null && newSshTls) || existingConfig?.security == "tls" || existing?.url?.contains("tls=true") == true || existing?.url?.contains("tls=1") == true
        val initialSni = existingConfig?.sni.orEmpty().ifEmpty {
            val url = existing?.url.orEmpty()
            if (url.contains("sni=")) url.substringAfter("sni=").substringBefore("&").substringBefore("#") else ""
        }

        var sshHost by remember(existing) { mutableStateOf(initialHost) }
        var sshPort by remember(existing) { mutableStateOf(initialPort) }
        var sshUser by remember(existing) { mutableStateOf(initialUser) }
        var sshPass by remember(existing) { mutableStateOf(initialPass) }
        var sshPrivateKey by remember(existing) { mutableStateOf(initialPrivateKey) }
        var sshKeyPassphrase by remember(existing) { mutableStateOf(initialKeyPassphrase) }
        var enableUdpGateway by remember(existing) { mutableStateOf(!existingConfig?.sshUdpGatewayHost.isNullOrBlank()) }
        var udpGatewayHost by remember(existing) { mutableStateOf(existingConfig?.sshUdpGatewayHost ?: "127.0.0.1") }
        var udpGatewayPort by remember(existing) { mutableStateOf((existingConfig?.sshUdpGatewayPort ?: 7300).toString()) }
        var sshRemarks by remember(existing) { mutableStateOf(initialRemarks) }
        var enableTls by remember(existing) { mutableStateOf(initialEnableTls) }
        var sni by remember(existing) { mutableStateOf(initialSni) }
        val initialProxy = existingConfig?.sshUpstreamProxy?.let { runCatching { java.net.URI(it) }.getOrNull() }
        var proxyEnabled by remember(existing) { mutableStateOf(initialProxy != null) }
        var proxyType by remember(existing) { mutableStateOf(initialProxy?.scheme ?: "socks5") }
        var proxyHost by remember(existing) { mutableStateOf(initialProxy?.host.orEmpty()) }
        var proxyPort by remember(existing) { mutableStateOf(initialProxy?.port?.toString() ?: "1080") }
        val proxyAuth = initialProxy?.rawUserInfo?.split(":", limit = 2).orEmpty().map(com.v2ray.ang.util.Utils::decodeURIComponent)
        var proxyUser by remember(existing) { mutableStateOf(proxyAuth.getOrNull(0).orEmpty()) }
        var proxyPass by remember(existing) { mutableStateOf(proxyAuth.getOrNull(1).orEmpty()) }

        AlertDialog(
            onDismissRequest = { showSshDialog = false; editingNode = null },
            title = { Text("SSH+TLS", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sshHost,
                        onValueChange = { input ->
                            val trimmed = input.trim()
                            if (trimmed.contains(":") && !trimmed.startsWith("[")) {
                                val parts = trimmed.split(":", limit = 2)
                                sshHost = parts[0]
                                if (parts[1].isNotBlank()) sshPort = parts[1]
                            } else {
                                sshHost = input
                            }
                        },
                        label = { Text(stringResource(R.string.ssh_host)) },
                        placeholder = { Text("e.g. 192.168.1.1 or server.example.com", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { sshPort = it },
                        label = { Text(stringResource(R.string.server_port)) },
                        placeholder = { Text("22", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        label = { Text(stringResource(R.string.ssh_username)) },
                        placeholder = { Text("e.g. root", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sshPass,
                        onValueChange = { sshPass = it },
                        label = { Text(stringResource(R.string.password_optional)) },
                        placeholder = { Text("Enter password (or leave empty)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sshPrivateKey,
                        onValueChange = { sshPrivateKey = it },
                        label = { Text("SSH private key (optional)") },
                        placeholder = { Text("Paste PEM/OpenSSH private key", fontSize = 12.sp) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (sshPrivateKey.isNotBlank()) {
                        OutlinedTextField(
                            value = sshKeyPassphrase,
                            onValueChange = { sshKeyPassphrase = it },
                            label = { Text("Key passphrase (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = sshRemarks,
                        onValueChange = { sshRemarks = it },
                        label = { Text(stringResource(R.string.server_remarks)) },
                        placeholder = { Text("e.g. SSH-MyServer", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                        Text(stringResource(R.string.ssh_upstream_proxy))
                    }
                    if (proxyEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = proxyType == "socks5", onClick = { proxyType = "socks5" })
                            Text("SOCKS5")
                            RadioButton(selected = proxyType == "http", onClick = { proxyType = "http" })
                            Text("HTTP")
                        }
                        OutlinedTextField(value = proxyHost, onValueChange = { proxyHost = it }, label = { Text(stringResource(R.string.ssh_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = proxyPort, onValueChange = { proxyPort = it }, label = { Text(stringResource(R.string.server_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = proxyUser, onValueChange = { proxyUser = it }, label = { Text(stringResource(R.string.username_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = proxyPass, onValueChange = { proxyPass = it }, label = { Text(stringResource(R.string.password_optional)) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enableUdpGateway, onCheckedChange = { enableUdpGateway = it })
                        Text("UDPGW", fontSize = 14.sp)
                    }
                    if (enableUdpGateway) {
                        OutlinedTextField(value = udpGatewayHost, onValueChange = { udpGatewayHost = it },
                            label = { Text("UDPGW host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = udpGatewayPort, onValueChange = { udpGatewayPort = it },
                            label = { Text("UDPGW port") }, singleLine = true,
                            isError = udpGatewayPort.toIntOrNull() !in 1..65535, modifier = Modifier.fillMaxWidth())
                    }

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enableTls, onCheckedChange = {
                            enableTls = it
                            if (it && sshPort == "22") sshPort = "443"
                            if (!it && sshPort == "443") sshPort = "22"
                        })
                        Text(stringResource(R.string.ssh_enable_tls), fontSize = 14.sp)
                    }
                    if (enableTls) {
                        OutlinedTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text(stringResource(R.string.ssh_sni)) },
                            placeholder = { Text("e.g. cloudflare.com or your domain", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanHost = if (sshHost.contains(":") && !sshHost.startsWith("[")) {
                            val parts = sshHost.split(":", limit = 2)
                            if (sshPort == "22" && parts[1].isNotBlank()) sshPort = parts[1]
                            parts[0].trim()
                        } else {
                            sshHost.trim()
                        }
                        val success = viewModel.addSshNode(
                            context,
                            cleanHost,
                            sshPort.trim().toIntOrNull() ?: 22,
                            sshUser.trim(),
                            sshPass.trim(),
                            sshRemarks.trim(),
                            enableTls = enableTls,
                            sni = sni.trim(),
                            privateKey = sshPrivateKey,
                            keyPassphrase = sshKeyPassphrase,
                            udpGatewayHost = if (enableUdpGateway) udpGatewayHost.trim() else "",
                            udpGatewayPort = udpGatewayPort.toIntOrNull() ?: 0,
                            upstreamProxy = if (proxyEnabled) {
                                val authority = proxyHost.trim().let { if (it.contains(':')) "[$it]" else it }
                                val auth = if (proxyUser.isNotBlank()) com.v2ray.ang.util.Utils.encodeURIComponent(proxyUser) + ":" + com.v2ray.ang.util.Utils.encodeURIComponent(proxyPass) + "@" else ""
                                "$proxyType://$auth$authority:${proxyPort.toInt()}"
                            } else ""
                        )
                        if (success) {
                            android.widget.Toast.makeText(context, context.getString(R.string.custom_config_added), android.widget.Toast.LENGTH_SHORT).show()
                            showSshDialog = false; editingNode = null
                        } else {
                            android.widget.Toast.makeText(context, "Host and username are required", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = sshHost.isNotBlank() && sshUser.isNotBlank() &&
                        (!proxyEnabled || (proxyHost.isNotBlank() && proxyPort.toIntOrNull() in 1..65535)) &&
                        (!enableUdpGateway || (udpGatewayHost.isNotBlank() && udpGatewayPort.toIntOrNull() in 1..65535))
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showSshDialog = false; editingNode = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // ---- DNSTT Dialog ----
    if (showDnsttDialog) {
        val existing = editingNode
        val existingConfig = existing?.let { node ->
            viewModel.findGuidForNode(node)?.let { guid -> MmkvManager.decodeServerConfig(guid) }
        }

        val initialDns = existingConfig?.server.orEmpty()
        val initialPort = existingConfig?.serverPort?.takeIf { it.isNotBlank() } ?: "53"
        val initialPubKey = existingConfig?.publicKey.orEmpty()
        val initialDomain = existingConfig?.host.orEmpty()
        val initialRemarks = existingConfig?.remarks ?: existing?.name ?: ""
        var dnsServer by remember(existing) { mutableStateOf(initialDns) }
        var dnsPort by remember(existing) { mutableStateOf(initialPort) }
        var dnsPublicKey by remember(existing) { mutableStateOf(initialPubKey) }
        var dnsDomain by remember(existing) { mutableStateOf(initialDomain) }
        var dnsRemarks by remember(existing) { mutableStateOf(initialRemarks) }

        AlertDialog(
            onDismissRequest = { showDnsttDialog = false; editingNode = null },
            title = { Text("DNSTT - DNS Tunnel", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = dnsServer,
                        onValueChange = { input ->
                            val trimmed = input.trim()
                            if (trimmed.contains(":") && !trimmed.startsWith("[")) {
                                val parts = trimmed.split(":", limit = 2)
                                dnsServer = parts[0]
                                if (parts[1].isNotBlank()) dnsPort = parts[1]
                            } else {
                                dnsServer = input
                            }
                        },
                        label = { Text(stringResource(R.string.dnstt_dns_server)) },
                        placeholder = { Text("e.g. 8.8.8.8 or 1.1.1.1", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dnsPort,
                        onValueChange = { dnsPort = it },
                        label = { Text(stringResource(R.string.server_port)) },
                        placeholder = { Text("53", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dnsPublicKey,
                        onValueChange = { dnsPublicKey = it },
                        label = { Text(stringResource(R.string.dnstt_public_key)) },
                        placeholder = { Text("e.g. 0123456789abcdef... (hex public key)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dnsDomain,
                        onValueChange = { dnsDomain = it },
                        label = { Text(stringResource(R.string.dnstt_domain)) },
                        placeholder = { Text("e.g. t.yourdomain.com", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = dnsRemarks,
                        onValueChange = { dnsRemarks = it },
                        label = { Text(stringResource(R.string.server_remarks)) },
                        placeholder = { Text("e.g. DNSTT-Server-1", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanServer = if (dnsServer.contains(":") && !dnsServer.startsWith("[")) {
                            val parts = dnsServer.split(":", limit = 2)
                            if (dnsPort == "53" && parts[1].isNotBlank()) dnsPort = parts[1]
                            parts[0].trim()
                        } else {
                            dnsServer.trim()
                        }
                        val success = viewModel.addDnsttNode(
                            context,
                            cleanServer,
                            dnsPort.trim().toIntOrNull() ?: 53,
                            dnsPublicKey.trim(),
                            dnsDomain.trim(),
                            dnsRemarks.trim()
                        )
                        android.widget.Toast.makeText(context, if (success) context.getString(R.string.custom_config_added) else context.getString(R.string.custom_config_invalid), android.widget.Toast.LENGTH_SHORT).show()
                        if (success) {
                            showDnsttDialog = false
                            editingNode = null
                        }
                    },
                    enabled = dnsServer.isNotBlank() && dnsPublicKey.isNotBlank() && dnsDomain.isNotBlank()
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDnsttDialog = false; editingNode = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ---- OpenVPN / AmneziaWG / WireGuard Text Editor Dialog (with Username/Password support) ----
    if (showTextEditorDialog) {
        val proto = textEditorProtocol
        val hint = when (proto) {
            "openvpn" -> stringResource(R.string.openvpn_config_hint)
            "wireguard" -> stringResource(R.string.wireguard_config_hint)
            "masterdns", "stormdns" -> "${proto}://... or JSON with config and resolvers"
            "openconnect" -> "openconnect://username:password@host:443"
            "custom" -> "{\"outbounds\": [...] }"
            "mihomo" -> "proxies:\n  - name: ..."
            "aether" -> "aether://?protocol=gool&h2=1"
            else -> stringResource(R.string.awg_config_hint)
        }
        var configText by remember { mutableStateOf(textEditingNode?.let { node ->
            val guid = viewModel.findGuidForNode(node)
            guid?.let { MmkvManager.decodeServerRaw(it)?.takeIf { raw -> raw.isNotBlank() }
                ?: MmkvManager.decodeServerConfig(it)?.nativeConfig?.takeIf { raw -> raw.isNotBlank() } } ?: node.url
        }.orEmpty()) }
        var configName by remember { mutableStateOf(textEditingNode?.name.orEmpty()) }
        var ovpnUsername by remember { mutableStateOf("") }
        var ovpnPassword by remember { mutableStateOf("") }
        var saveCredentials by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showTextEditorDialog = false },
            title = { Text("${proto.uppercase()} Config", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = configName, onValueChange = { configName = it },
                        label = { Text(stringResource(R.string.config_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    if (proto == "openvpn") {
                        OutlinedTextField(value = ovpnUsername, onValueChange = { ovpnUsername = it },
                            label = { Text(stringResource(R.string.username_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = ovpnPassword, onValueChange = { ovpnPassword = it },
                            label = { Text(stringResource(R.string.password_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = saveCredentials, onCheckedChange = { saveCredentials = it })
                            Text(stringResource(R.string.save_credentials), fontSize = 13.sp)
                        }
                    }

                    OutlinedTextField(
                        value = configText, onValueChange = { configText = it },
                        label = { Text(stringResource(R.string.paste_config_text)) },
                        placeholder = { Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        maxLines = 20
                    )
                    TextButton(onClick = { configText = clipboardManager.getText()?.text.orEmpty() }) {
                        Text(stringResource(R.string.import_clipboard))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = if (textEditingNode != null) viewModel.editCustomRawConfig(context, textEditingNode!!, configName.trim(), configText.trim()) else viewModel.addOvpnOrAwgFromText(
                            context,
                            proto,
                            configText.trim(),
                            configName.trim(),
                            username = ovpnUsername.trim(),
                            password = ovpnPassword.trim(),
                            saveCredentials = saveCredentials
                        )
                        android.widget.Toast.makeText(context, if (success) context.getString(R.string.custom_config_added) else context.getString(R.string.custom_config_invalid), android.widget.Toast.LENGTH_SHORT).show()
                        if (success) { showTextEditorDialog = false; textEditingNode = null }
                    },
                    enabled = configText.isNotBlank()
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showTextEditorDialog = false; textEditingNode = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // ---- Rename Dialog ----
    if (showRenameDialog && renameNode != null) {
        var newName by remember { mutableStateOf(renameNode!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false; renameNode = null },
            title = { Text(stringResource(R.string.edit_config), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.config_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    renameNode?.let { viewModel.updateCustomNode(context, it, newName.trim()) }
                    showRenameDialog = false; renameNode = null
                }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false; renameNode = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // ---- OpenVPN Authentication & Edit Dialog ----
    if (showOvpnAuthDialog && ovpnAuthNode != null) {
        val targetNode = ovpnAuthNode!!
        val guid = viewModel.findGuidForNode(targetNode)
        val config = guid?.let { MmkvManager.decodeServerConfig(it) }

        var ovpnName by remember(targetNode) { mutableStateOf(targetNode.name) }
        var ovpnUser by remember(targetNode) { mutableStateOf(config?.username.orEmpty()) }
        var ovpnPass by remember(targetNode) { mutableStateOf(config?.password.orEmpty()) }
        var saveCreds by remember(targetNode) { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showOvpnAuthDialog = false; ovpnAuthNode = null },
            title = {
                Text(
                    text = if (ovpnAuthIsConnecting) stringResource(R.string.openvpn_login_required) else stringResource(R.string.edit_config) + " - OpenVPN",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (ovpnAuthIsConnecting) {
                        Text(stringResource(R.string.openvpn_login_prompt), fontSize = 13.sp)
                    } else {
                        OutlinedTextField(
                            value = ovpnName,
                            onValueChange = { ovpnName = it },
                            label = { Text(stringResource(R.string.config_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = ovpnUser,
                        onValueChange = { ovpnUser = it },
                        label = { Text(stringResource(R.string.username_optional)) },
                        placeholder = { Text("e.g. vpnuser", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = ovpnPass,
                        onValueChange = { ovpnPass = it },
                        label = { Text(stringResource(R.string.password_optional)) },
                        placeholder = { Text("Enter password", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = saveCreds, onCheckedChange = { saveCreds = it })
                        Text(stringResource(R.string.save_credentials), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cleanName = ovpnName.trim().ifBlank { targetNode.name }
                    val finalUser = ovpnUser.trim()
                    val finalPass = ovpnPass.trim()

                    if (saveCreds) {
                        viewModel.updateCustomNode(context, targetNode, cleanName, finalUser, finalPass)
                    } else if (guid != null) {
                        com.v2ray.ang.core.CoreServiceManager.setSessionCredentials(guid, finalUser, finalPass)
                    }

                    val shouldConnect = ovpnAuthIsConnecting
                    showOvpnAuthDialog = false
                    ovpnAuthNode = null

                    if (shouldConnect) {
                        viewModel.connectToCustomNode(context, targetNode, false)
                    }
                }) {
                    Text(if (ovpnAuthIsConnecting) stringResource(R.string.connect) else stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOvpnAuthDialog = false; ovpnAuthNode = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    manualNativeProtocol?.let { protocol ->
        NativeProfileDialog(protocol, onDismiss = { manualNativeProtocol = null },
            onImport = { content, name -> viewModel.addOvpnOrAwgFromText(context, protocol, content, name) },
            onFile = {
                manualNativeProtocol = null
                chosenSourceProtocol = protocol
                showSourceChoiceDialog = true
            })
    }

    // Protocol Chooser Dialog
    if (showManualProtocolDialog) {
        // Protocols that launch ServerActivity (full v2rayNG editor)
        val serverActivityProtocols = setOf("vmess", "vless", "shadowsocks", "trojan", "hysteria2", "socks", "http")
        val protocols = listOf(
            "vmess" to stringResource(id = R.string.protocol_vmess),
            "vless" to stringResource(id = R.string.protocol_vless),
            "shadowsocks" to stringResource(id = R.string.protocol_shadowsocks),
            "trojan" to stringResource(id = R.string.protocol_trojan),
            "hysteria2" to stringResource(id = R.string.protocol_hysteria2),
            "wireguard" to stringResource(id = R.string.protocol_wireguard),
            "socks" to stringResource(id = R.string.protocol_socks),
            "http" to stringResource(id = R.string.protocol_http),
            "amneziawg" to stringResource(id = R.string.protocol_amneziawg),
            "aether" to "Aether",
            "openvpn" to "OpenVPN",
            "ikev2" to "IKEv2",
            "mihomo" to "Mihomo",
            "ssh-tls" to "SSH+TLS",
            "dnstt" to "DNSTT",
            "masterdns" to "MasterDNS",
            "stormdns" to "StormDNS",
            "openconnect" to "OpenConnect",
            "custom" to "Xray JSON",
            "dns-only" to stringResource(R.string.custom_dns_only)
        )
        AlertDialog(
            onDismissRequest = { showManualProtocolDialog = false },
            title = { Text(stringResource(id = R.string.add_manually_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    protocols.forEach { (protoKey, protoName) ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showManualProtocolDialog = false
                                when {
                                    protoKey in serverActivityProtocols -> {
                                        // Launch ServerActivity with full editor
                                        val configType = when (protoKey) {
                                            "vmess" -> com.v2ray.ang.enums.EConfigType.VMESS
                                            "vless" -> com.v2ray.ang.enums.EConfigType.VLESS
                                            "shadowsocks" -> com.v2ray.ang.enums.EConfigType.SHADOWSOCKS
                                            "trojan" -> com.v2ray.ang.enums.EConfigType.TROJAN
                                            "hysteria2" -> com.v2ray.ang.enums.EConfigType.HYSTERIA2
                                            "socks" -> com.v2ray.ang.enums.EConfigType.SOCKS
                                            "http" -> com.v2ray.ang.enums.EConfigType.HTTP
                                            else -> com.v2ray.ang.enums.EConfigType.VMESS
                                        }
                                        val intent = android.content.Intent(context, com.v2ray.ang.ui.ServerActivity::class.java)
                                            .putExtra("createConfigType", configType.value)
                                            .putExtra("subscriptionId", "spicyvpn-custom-user")
                                        serverActivityLauncher.launch(intent)
                                    }
                                    protoKey in setOf("aether", "masterdns", "stormdns", "openconnect") -> {
                                        manualNativeProtocol = protoKey
                                    }
                                    protoKey in setOf("mihomo", "custom") -> {
                                        textEditorProtocol = protoKey; textEditingNode = null; showTextEditorDialog = true
                                    }
                                    protoKey in setOf("amneziawg", "wireguard", "openvpn") -> {
                                        chosenSourceProtocol = protoKey
                                        showSourceChoiceDialog = true
                                    }
                                    protoKey == "dns-only" -> {
                                        dnsOnlyEditing = null; dnsOnlyName = ""; dnsOnlyAddress = ""; dnsOnlyError = false; showDnsOnlyDialog = true
                                    }
                                    protoKey == "ikev2" -> {
                                        ikev2EditingNode = null; showIkev2Editor = true
                                    }
                                    protoKey == "ssh" || protoKey == "ssh-tls" -> {
                                        editingNode = null
                                        newSshTls = protoKey == "ssh-tls"
                                        showSshDialog = true
                                    }
                                    protoKey == "dnstt" -> {
                                        editingNode = null
                                        showDnsttDialog = true
                                    }
                                }
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = protoName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                if (protoKey in serverActivityProtocols) {
                                    Text(text = "Full Editor →", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showManualProtocolDialog = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Action Header Row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Add (+) Button with Dropdown Menu
                Box {
                    Button(
                        onClick = { showAddConfigMenu = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.add_custom_config), fontWeight = FontWeight.Bold)
                    }

                    DropdownMenu(expanded = showAddConfigMenu, onDismissRequest = { showAddConfigMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isImporting) "⏳ Importing..."
                                    else "📋 " + stringResource(id = R.string.import_clipboard)
                                )
                            },
                            enabled = !isImporting,
                            onClick = {
                                showAddConfigMenu = false
                                val clip = clipboardManager.getText()?.text.orEmpty()
                                importCustomText(clip)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📁 " + stringResource(id = R.string.import_file)) },
                            onClick = {
                                showAddConfigMenu = false
                                try { filePickerLauncher.launch("*/*") } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Cannot open file picker", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📷 " + stringResource(id = R.string.scan_qr_code)) },
                            onClick = {
                                showAddConfigMenu = false
                                val hasCameraPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.CAMERA
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (hasCameraPerm) {
                                    try {
                                        val intent = android.content.Intent(context, com.v2ray.ang.ui.ScannerActivity::class.java)
                                        qrScannerLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Error opening scanner: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("✍️ " + stringResource(id = R.string.add_manually_title) + "...") },
                            onClick = { showAddConfigMenu = false; showManualProtocolDialog = true }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { showCreateFolder = true }) {
                        Icon(painterResource(android.R.drawable.ic_menu_agenda), contentDescription = stringResource(R.string.custom_create_folder))
                    }
                    Button(
                        onClick = { viewModel.testCustomProxies(context) },
                        enabled = !isTesting && customNodes.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(
                            text = if (isTesting) stringResource(id = R.string.connection_test_testing) else stringResource(id = R.string.test_ping),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                    }
                    if (customNodes.isNotEmpty()) {
                        IconButton(onClick = { showClearAllConfirmDialog = true }) {
                            Text("🗑", fontSize = 20.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (customNodes.isEmpty() && organization.folders.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(id = R.string.no_custom_configs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(state = customListState, modifier = Modifier.fillMaxSize()) {
                    val subscriptions = MmkvManager.decodeSubscriptions().filter { it.guid != "spicyvpn-custom-user" }
                    val groupedUrls = organization.folders.flatMap { it.urls }.toMutableSet()
                    val rows = mutableListOf<Pair<String?, ProxyNode?>>()
                    val nodesByUrl = customNodes.associateBy { it.url }
                    organization.pinnedUrls.mapNotNull { nodesByUrl[it] }.forEach { rows.add(null to it) }
                    groupedUrls.addAll(organization.pinnedUrls)
                    organization.folders.sortedByDescending { it.pinned }.forEach { folder ->
                        rows.add("folder:${folder.id}" to null)
                        if (expandedSubscriptions.contains("folder:${folder.id}")) {
                            organization.ordered(folder.urls.filterNot { it in organization.pinnedUrls }.mapNotNull { nodesByUrl[it] }) { it.url }.forEach { rows.add(null to it) }
                        }
                    }
                    subscriptions.forEach { sub ->
                        val urls = MmkvManager.decodeServerList(sub.guid).map { "mmkv://$it" }.toSet()
                        val membersByUrl = customNodes.associateBy { it.url }
                        val members = urls.filterNot { it in groupedUrls }.mapNotNull { membersByUrl[it] }
                        if (members.isNotEmpty()) {
                            groupedUrls.addAll(members.map { it.url })
                            rows.add(sub.guid to null)
                            if (expandedSubscriptions.contains(sub.guid)) organization.ordered(members) { it.url }.forEach { rows.add(null to it) }
                        }
                    }
                    organization.ordered(customNodes.filter { it.url !in groupedUrls }) { it.url }.forEach { rows.add(null to it) }
                    items(rows.size, key = { rows[it].first ?: requireNotNull(rows[it].second).url }) { index ->
                        val (groupId, rowNode) = rows[index]
                        if (groupId != null) {
                            val folder = organization.folders.find { "folder:${it.id}" == groupId }
                            val folderLocked = folder?.editLock != null && folder.id !in unlockedFolders
                            val sub = subscriptions.find { it.guid == groupId }
                            Row(Modifier.fillMaxWidth().clickable {
                                expandedSubscriptions = if (groupId in expandedSubscriptions) expandedSubscriptions - groupId else expandedSubscriptions + groupId
                                groupPrefs.edit().putStringSet("expanded_custom_subscriptions", expandedSubscriptions).apply()
                            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (groupId in expandedSubscriptions) "\u25bc" else "\u25b6")
                                Spacer(Modifier.width(12.dp))
                                Text(folder?.name ?: sub?.subscription?.remarks?.ifBlank { sub.subscription.url }.orEmpty(), modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.Bold)
                                Text((folder?.urls?.size ?: MmkvManager.decodeServerList(groupId).size).toString())
                                if (folder?.editLock != null) IconButton(onClick = {
                                    if (folder.id in unlockedFolders) unlockedFolders = unlockedFolders - folder.id
                                    else { editPassword = ""; editPasswordError = false; unlockingFolder = folder }
                                }) { Text(if (folderLocked) "Unlock" else "Lock") }
                                if (!folderLocked && (folder != null || sub != null)) IconButton(onClick = {
                                    val urls = folder?.urls ?: MmkvManager.decodeServerList(groupId).map { "mmkv://$it" }
                                    val name = folder?.name ?: sub?.subscription?.remarks?.ifBlank { "Subscription" }.orEmpty()
                                    runCatching { SharedFolder(name = name, profiles = viewModel.sharedProfiles(urls.mapNotNull { nodesByUrl[it] })) }
                                        .onSuccess { sharing = it to null }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }) { Icon(painterResource(android.R.drawable.ic_menu_share), contentDescription = "Share folder") }
                                if (sub != null) IconButton(enabled = groupId !in refreshingSubscriptions, onClick = {
                                    refreshingSubscriptions = refreshingSubscriptions + groupId
                                    subscriptionScope.launch {
                                        try {
                                            val success = runCatching { viewModel.refreshCustomSubscription(context, groupId) }.getOrDefault(false)
                                            if (!success) Toast.makeText(context, R.string.custom_subscription_update_failed, Toast.LENGTH_LONG).show()
                                        } finally { refreshingSubscriptions = refreshingSubscriptions - groupId }
                                    }
                                }) {
                                    if (groupId in refreshingSubscriptions) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.custom_update_subscription))
                                }
                                if (folder != null) IconButton(onClick = {
                                    saveOrganization(organization.copy(folders = organization.folders.map { if (it.id == folder.id) it.copy(pinned = !it.pinned) else it }))
                                }) { Icon(painterResource(android.R.drawable.btn_star_big_on), contentDescription = stringResource(if (folder.pinned) R.string.custom_unpin else R.string.custom_pin), tint = if (folder.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (folder != null && !folderLocked) IconButton(onClick = {
                                    saveOrganization(organization.copy(folders = organization.folders.filterNot { it.id == folder.id }))
                                    expandedSubscriptions = expandedSubscriptions - folder.id
                                    groupPrefs.edit().putStringSet("expanded_custom_subscriptions", expandedSubscriptions).apply()
                                }) { Icon(painterResource(android.R.drawable.ic_menu_delete), contentDescription = stringResource(R.string.custom_delete_folder)) }
                            }
                        } else {
                        val node = requireNotNull(rowNode)
                        val nodeLocked = organization.folders.any { node.url in it.urls && it.editLock != null && it.id !in unlockedFolders }
                        val isSelected = activeNode?.url == node.url
                        val latestRows by rememberUpdatedState(rows.toList())
                        var dragOffset by remember(node.url) { mutableStateOf(0f) }
                        var dragging by remember(node.url) { mutableStateOf(false) }
                        var rowHeight by remember(node.url) { mutableStateOf(1) }

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .onSizeChanged { rowHeight = it.height }
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = dragOffset }
                                .pointerInput(node.url) {
                                var drag = 0f
                                var pendingOrganization = latestOrganization
                                detectDragGesturesAfterLongPress(onDragStart = { drag = 0f; dragging = true; pendingOrganization = latestOrganization },
                                    onDragEnd = { dragOffset = 0f; dragging = false; saveOrganization(pendingOrganization) },
                                    onDragCancel = { dragOffset = 0f; dragging = false; saveOrganization(pendingOrganization) }, onDrag = { change, amount ->
                                    run {
                                        change.consume(); drag += amount.y
                                        dragOffset = drag
                                        val item = customListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == node.url }
                                        item?.let {
                                            val center = it.offset + it.size / 2f + drag
                                            val layout = customListState.layoutInfo
                                            val edge = 48.dp.toPx()
                                            val scroll = when {
                                                center < layout.viewportStartOffset + edge -> -12.dp.toPx()
                                                center > layout.viewportEndOffset - edge -> 12.dp.toPx()
                                                else -> 0f
                                            }
                                            if (scroll != 0f) coroutineScope.launch { customListState.scrollBy(scroll) }
                                        }
                                        if (kotlin.math.abs(drag) > rowHeight / 2f) {
                                            val currentIndex = latestRows.indexOfFirst { it.second?.url == node.url }
                                            val segmentStart = latestRows.take(currentIndex).indexOfLast { it.first != null } + 1
                                            val segment = latestRows.drop(segmentStart).takeWhile { it.first == null }.mapNotNull { it.second?.url }
                                            val next = pendingOrganization.move(node.url, if (drag > 0) 1 else -1, segment)
                                            if (next != pendingOrganization) {
                                                pendingOrganization = next
                                                organization = next
                                                drag -= if (drag > 0) rowHeight.toFloat() else -rowHeight.toFloat()
                                                dragOffset = drag
                                            }
                                        }
                                    }
                                })
                            }.clickable {
                                viewModel.selectCustomNode(context, node)
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Column {
                                            Text(
                                                text = node.name.ifBlank { "Custom" },
                                                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = getCustomProtocolBadge(node, viewModel),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        val pingText = when (node.ping) {
                                            -2 -> stringResource(id = R.string.connection_test_testing)
                                            -1 -> stringResource(R.string.custom_timeout)
                                            0 -> stringResource(R.string.custom_tap_test)
                                            else -> "${node.ping}ms"
                                        }
                                        val pingColor = when (node.ping) {
                                            -2 -> Color(0xFFFFA500); -1 -> Color.Red; 0 -> Color.Gray
                                            else -> Color(0xFF4CAF50)
                                        }
                                        Text(text = pingText, color = pingColor, fontSize = 13.sp)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!nodeLocked) IconButton(onClick = { organizingNode = node }) {
                                        Icon(painterResource(if (node.url in organization.pinnedUrls) android.R.drawable.btn_star_big_on else android.R.drawable.ic_menu_agenda),
                                            contentDescription = stringResource(R.string.custom_organize))
                                    }
                                    // Edit (pencil) button
                                    if (!nodeLocked) IconButton(onClick = {
                                        val guid = viewModel.findGuidForNode(node)
                                        val config = guid?.let { MmkvManager.decodeServerConfig(it) }
                                        val isSsh = node.url.startsWith("ssh://") || config?.configType == com.v2ray.ang.enums.EConfigType.SSH
                                        val isDnstt = node.url.startsWith("dnstt://") || config?.configType == com.v2ray.ang.enums.EConfigType.DNSTT
                                        val isCustomJson = config?.configType == com.v2ray.ang.enums.EConfigType.CUSTOM

                                        when {
                                            config?.configType == com.v2ray.ang.enums.EConfigType.IKEV2 -> {
                                                ikev2EditingNode = node; showIkev2Editor = true
                                            }
                                            !config?.dnsOnlyResolver.isNullOrBlank() -> {
                                                dnsOnlyEditing = node; dnsOnlyName = node.name; dnsOnlyAddress = config?.dnsOnlyResolver.orEmpty(); dnsOnlyError = false; showDnsOnlyDialog = true
                                            }
                                            config != null && (!config.nativeConfig.isNullOrBlank() ||
                                                !guid?.let { MmkvManager.decodeServerRaw(it) }.isNullOrBlank()) -> {
                                                textEditingNode = node
                                                textEditorProtocol = config.configType.name.lowercase()
                                                showTextEditorDialog = true
                                            }
                                            isCustomJson && guid != null -> {
                                                val intent = android.content.Intent(context, com.v2ray.ang.ui.ServerCustomConfigActivity::class.java)
                                                    .putExtra("guid", guid)
                                                serverActivityLauncher.launch(intent)
                                            }
                                            config?.configType == com.v2ray.ang.enums.EConfigType.PROXYCHAIN && guid != null -> {
                                                val intent = android.content.Intent(context, com.v2ray.ang.ui.ServerProxyChainActivity::class.java)
                                                    .putExtra("guid", guid)
                                                    .putExtra("subscriptionId", config.subscriptionId)
                                                serverActivityLauncher.launch(intent)
                                            }
                                            guid != null && config != null && config.configType != com.v2ray.ang.enums.EConfigType.AMNEZIAWG -> {
                                                // Launch ServerActivity in edit mode for supported protocols
                                                val intent = android.content.Intent(context, com.v2ray.ang.ui.ServerActivity::class.java)
                                                    .putExtra("guid", guid)
                                                    .putExtra("subscriptionId", config.subscriptionId)
                                                serverActivityLauncher.launch(intent)
                                            }
                                            else -> {
                                                // Rename dialog for AmneziaWG or non-ServerActivity configs
                                                renameNode = node
                                                showRenameDialog = true
                                            }
                                        }
                                    }) {
                                        Text("✏️", fontSize = 16.sp)
                                    }

                                    // Delete button
                                    IconButton(onClick = { viewModel.removeCustomNode(context, node) }) {
                                        Text("🗑", fontSize = 18.sp)
                                    }

                                    // Connection uses the shared Custom connect button.
                                    if (!nodeLocked) IconButton(onClick = {
                                        runCatching { SharedFolder(name = node.name, profiles = viewModel.sharedProfiles(listOf(node))) }
                                            .onSuccess { sharing = it to viewModel.findGuidForNode(node) }
                                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                    }) {
                                        Icon(painterResource(android.R.drawable.ic_menu_share), contentDescription = "Share configuration")
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }

        // Floating Small Logo Connect Button at bottom right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 2.dp,
                    color = if (isRunning) Color.Green else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .clickable {
                    if (isRunning) {
                        com.v2ray.ang.core.CoreServiceManager.stopVService(context)
                    } else if (MmkvManager.decodeSettingsString("pref_s_standalone") in listOf("tor", "psiphon")) {
                        com.v2ray.ang.core.CoreServiceManager.startVService(context)
                    } else {
                        val target = activeNode ?: customNodes.firstOrNull { it.ping > 0 } ?: customNodes.firstOrNull()
                        if (target != null) {
                            val config = viewModel.findGuidForNode(target)?.let { MmkvManager.decodeServerConfig(it) }
                            if ((target.url.startsWith("openvpn://") || config?.configType == com.v2ray.ang.enums.EConfigType.OPENVPN) &&
                                (config?.username.isNullOrBlank() || config?.password.isNullOrBlank())) {
                                ovpnAuthNode = target
                                ovpnAuthIsConnecting = true
                                showOvpnAuthDialog = true
                            } else viewModel.connectToCustomNode(context, target, false)
                        } else {
                            android.widget.Toast.makeText(context, context.getString(R.string.no_custom_configs), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Float Connect Logo Button",
                modifier = Modifier.fillMaxSize().padding(4.dp).clip(CircleShape),
                colorFilter = if (isRunning) null else ColorFilter.tint(MaterialTheme.colorScheme.outline)
            )
        }
    }
}


@Composable
fun SSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Load current values from MMKV
    var fragmentEnabled by remember { mutableStateOf(MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_FRAGMENT_ENABLED, false)) }
    var fragmentAuto by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_fragment_auto", false)) }
    var fragmentPackets by remember { mutableStateOf(MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_FRAGMENT_PACKETS) ?: "tlshello") }
    var fragmentLength by remember { mutableStateOf(MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_FRAGMENT_LENGTH) ?: "10-20") }
    var fragmentInterval by remember { mutableStateOf(MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_FRAGMENT_INTERVAL) ?: "10-20") }
    var fragmentMaxSplit by remember { mutableStateOf(MmkvManager.decodeSettingsString(com.v2ray.ang.AppConfig.PREF_FRAGMENT_MAXSPLIT) ?: "10") }

    var warpBefore by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_warp_before", false)) }
    var torBefore by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_tor_before", false)) }
    var afterMode by remember { mutableStateOf(when {
        MmkvManager.decodeSettingsBool("pref_psiphon_after", false) -> "psiphon"
        MmkvManager.decodeSettingsBool("pref_tor_after", false) -> "tor"
        MmkvManager.decodeSettingsBool("pref_aether_after", false) -> "warp-masque"
        else -> ""
    }) }
    var standaloneMode by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_s_standalone").orEmpty()) }
    var warpAfter by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_warp_after", false)) }
    var warpEndpoint by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_warp_endpoint") ?: "engage.cloudflareclient.com:2408") }

    var muxEnabled by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_mux_enabled", false)) }
    var muxConcurrency by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_mux_concurrency") ?: "8") }

    var fakeHostEnabled by remember { mutableStateOf(MmkvManager.decodeSettingsBool("pref_fakehost_enabled", false)) }
    var fakeHostDomain by remember { mutableStateOf(MmkvManager.decodeSettingsString("pref_fakehost_domain") ?: "cloudflare.com") }

    var expandedPackets by remember { mutableStateOf(false) }
    val packetOptions = listOf("tlshello", "1-1", "1-2", "1-3", "1-5")

    var showWarpSettings by remember { mutableStateOf(false) }
    var showDnsScanner by remember { mutableStateOf(false) }
    var showPsiphonSettings by remember { mutableStateOf(false) }
    var showStandaloneIpScanner by remember { mutableStateOf(false) }
    var showTorSettings by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Advanced Settings (S)", fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    TextButton(onClick = { showDnsScanner = true }) { Text("DNS Scanner") }
                    TextButton(onClick = { showStandaloneIpScanner = true }) { Text("IP Scanner") }
                    TextButton(onClick = { showPsiphonSettings = true }) { Text("Psiphon settings") }
                    TextButton(onClick = { showTorSettings = true }) { Text("Tor bridges") }
                    listOf("psiphon" to "Psiphon Only", "tor" to "Tor Only").forEach { (mode, label) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label)
                            Switch(checked = standaloneMode == mode, onCheckedChange = {
                                standaloneMode = if (it) mode else ""
                                if (it) { afterMode = ""; torBefore = false; warpBefore = false; warpAfter = false }
                            })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.tor_before_config))
                        Switch(checked = torBefore, onCheckedChange = {
                            torBefore = it
                            if (it) { afterMode = ""; standaloneMode = ""; warpBefore = false; warpAfter = false }
                        })
                    }
                    listOf("psiphon" to "Psiphon After", "tor" to "Tor After",
                        "warp-masque" to "WARP After (MASQUE HTTP/2)").forEach { (mode, label) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label)
                            Switch(checked = afterMode == mode, onCheckedChange = {
                                afterMode = if (it) mode else ""
                                if (it) { standaloneMode = ""; torBefore = false; warpBefore = false; warpAfter = false }
                            })
                        }
                    }
                }
                // --- Fragment Settings ---
                item {
                    Text(stringResource(R.string.title_fragment_settings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Fragment")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = fragmentEnabled, onCheckedChange = { fragmentEnabled = it })
                            Spacer(modifier = Modifier.width(16.dp))
                            Checkbox(checked = fragmentAuto, onCheckedChange = { fragmentAuto = it ?: false })
                            Text("Auto")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // --- Warp settings ---
                item {
                    Text("Warp Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("WARP before config")
                        Switch(
                            checked = warpBefore,
                            onCheckedChange = { enabled ->
                                val hasWarpKey = MmkvManager.decodeSettingsString("pref_warp_private_key").orEmpty().isNotBlank() &&
                                        MmkvManager.decodeSettingsString("pref_warp_public_key").orEmpty().isNotBlank()
                                if (enabled && !hasWarpKey) {
                                    showWarpSettings = true
                                    Toast.makeText(context, "Create or import a WARP key first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    warpBefore = enabled
                                    if (!enabled) MmkvManager.encodeSettings("pref_warp_before", false)
                                }
                                if (enabled && hasWarpKey) {
                                    standaloneMode = ""
                                    torBefore = false
                                    afterMode = ""
                                    warpAfter = false
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("WARP after config")
                        Switch(
                            checked = warpAfter,
                            onCheckedChange = { enabled ->
                                val hasWarpKey = MmkvManager.decodeSettingsString("pref_warp_private_key").orEmpty().isNotBlank() &&
                                        MmkvManager.decodeSettingsString("pref_warp_public_key").orEmpty().isNotBlank()
                                if (enabled && !hasWarpKey) {
                                    showWarpSettings = true
                                    Toast.makeText(context, "Create or import a WARP key first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    warpAfter = enabled
                                    if (!enabled) MmkvManager.encodeSettings("pref_warp_after", false)
                                }
                                if (enabled && hasWarpKey) {
                                    standaloneMode = ""
                                    torBefore = false
                                    afterMode = ""
                                    warpBefore = false
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showWarpSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("WARP SETTING")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // --- Mux settings ---
                item {
                    Text(stringResource(R.string.title_mux_settings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.title_pref_mux_enabled))
                            Checkbox(checked = muxEnabled, onCheckedChange = { muxEnabled = it ?: false })
                        }
                        if (muxEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = muxConcurrency,
                                onValueChange = { muxConcurrency = it },
                                label = { Text("Concurrency") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // --- Fakehost settings ---
                item {
                    Text("Fakehost Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Enable Fakehost")
                            Checkbox(checked = fakeHostEnabled, onCheckedChange = { fakeHostEnabled = it ?: false })
                        }
                        if (fakeHostEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = fakeHostDomain,
                                onValueChange = { fakeHostDomain = it },
                                label = { Text("Domain") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // --- Fragment Parameters ---
                item {
                    Text("Fragment Parameters", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box {
                        OutlinedButton(onClick = { expandedPackets = true }) {
                            Text("Packets: $fragmentPackets")
                        }
                        DropdownMenu(expanded = expandedPackets, onDismissRequest = { expandedPackets = false }) {
                            packetOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        fragmentPackets = opt
                                        expandedPackets = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fragmentLength,
                        onValueChange = { fragmentLength = it },
                        label = { Text("Length (byte)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fragmentInterval,
                        onValueChange = { fragmentInterval = it },
                        label = { Text("Interval (ms)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fragmentMaxSplit,
                        onValueChange = { fragmentMaxSplit = it },
                        label = { Text("Max Split") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Save to MMKV
                    MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_FRAGMENT_ENABLED, fragmentEnabled)
                    MmkvManager.encodeSettings("pref_fragment_auto", fragmentAuto)
                    MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_FRAGMENT_PACKETS, fragmentPackets)
                    MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_FRAGMENT_LENGTH, fragmentLength)
                    MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_FRAGMENT_INTERVAL, fragmentInterval)
                    MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_FRAGMENT_MAXSPLIT, fragmentMaxSplit)

                    MmkvManager.encodeSettings("pref_psiphon_only", standaloneMode == "psiphon")
                    MmkvManager.encodeSettings("pref_psiphon_after", afterMode == "psiphon")
                    MmkvManager.encodeSettings("pref_tor_after", afterMode == "tor")
                    MmkvManager.encodeSettings("pref_aether_after", afterMode == "warp-masque")

                    MmkvManager.encodeSettings("pref_warp_before", warpBefore)
                    MmkvManager.encodeSettings("pref_tor_before", torBefore)
                    MmkvManager.encodeSettings("pref_s_standalone", standaloneMode)
                    MmkvManager.encodeSettings("pref_warp_after", warpAfter)
                    MmkvManager.encodeSettings("pref_warp_endpoint", warpEndpoint)

                    MmkvManager.encodeSettings("pref_mux_enabled", muxEnabled)
                    MmkvManager.encodeSettings("pref_mux_concurrency", muxConcurrency)

                    MmkvManager.encodeSettings("pref_fakehost_enabled", fakeHostEnabled)
                    MmkvManager.encodeSettings("pref_fakehost_domain", fakeHostDomain)

                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showWarpSettings) {
        WarpSettingsDialog(onDismiss = { showWarpSettings = false })
    }
    if (showDnsScanner) DnsScannerDialog { showDnsScanner = false }
    if (showPsiphonSettings) PsiphonSettingsDialog { showPsiphonSettings = false }
    if (showStandaloneIpScanner) IpScannerDialog(onDismiss = { showStandaloneIpScanner = false }, onApply = {
        MmkvManager.encodeSettings("pref_psiphon_akamai_cdn_ip", it)
        showStandaloneIpScanner = false
    })
    if (showTorSettings) TorBridgeSettingsDialog { showTorSettings = false }
}

@Composable
fun AboutDialog(
    onDismiss: () -> Unit
) {
    val xrayVersion = remember { com.v2ray.ang.core.CoreNativeManager.getLibVersion() }
    val context = LocalContext.current
    val nativeVersions = remember {
        runCatching {
            val json = org.json.JSONObject(context.assets.open("core_versions.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().filter { it != "Xray" }.map { name ->
                "$name: ${json.getJSONObject(name).getString("version")}" 
            }.toList()
        }.getOrDefault(emptyList()) + listOf(
            "SSH / SSH+TLS: ${com.jcraft.jsch.JSch.VERSION}",
            "OpenVPN: ${runCatching { com.v2ray.ang.tunnel.OpenVpnNative.version() }.getOrDefault("Unavailable")}",
            "Aether: ${com.example.spicyvpn.aether.NativeAetherBridge.versionOrNull() ?: "Unavailable"}"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.about_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "${stringResource(id = R.string.app_version)}: SpicyVPN v${com.v2ray.ang.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${stringResource(id = R.string.xray_version)}: $xrayVersion",
                    style = MaterialTheme.typography.bodyLarge
                )
                nativeVersions.forEach { version ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(version, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

fun getCountryFlagEmoji(remark: String): String {
    val upper = remark.uppercase(Locale.US)
    val countryCodes = setOf(
        "US", "DE", "FR", "GB", "NL", "CA", "FI", "SG", "JP", "TR", 
        "AE", "IR", "RU", "UA", "PL", "SE", "CH", "HK", "KR", "IN", 
        "AU", "NZ", "BR", "IT", "ES", "PT", "RO", "BG"
    )
    val words = upper.split(Regex("[^A-Z]"))
    for (word in words) {
        if (countryCodes.contains(word)) {
            return countryCodeToFlagEmoji(word)
        }
    }
    return "🏳️"
}

fun isSSettingsActive(): Boolean {
    return !MmkvManager.decodeSettingsString("pref_s_standalone").isNullOrBlank() ||
            MmkvManager.decodeSettingsBool("pref_tor_before", false) ||
            MmkvManager.decodeSettingsBool("pref_tor_after", false) ||
            MmkvManager.decodeSettingsBool("pref_psiphon_after", false) ||
            MmkvManager.decodeSettingsBool("pref_aether_after", false) ||
            MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_FRAGMENT_ENABLED, false) ||
            MmkvManager.decodeSettingsBool("pref_fragment_auto", false) ||
            MmkvManager.decodeSettingsBool("pref_warp_before", false) ||
            MmkvManager.decodeSettingsBool("pref_warp_after", false) ||
            MmkvManager.decodeSettingsBool("pref_mux_enabled", false) ||
            MmkvManager.decodeSettingsBool("pref_fakehost_enabled", false)
}

fun getCustomProtocolBadge(node: ProxyNode, viewModel: MainScreenViewModel): String {
    val url = node.url
    val guid = viewModel.findGuidForNode(node)
    if (guid != null) {
        val config = MmkvManager.decodeServerConfig(guid)
        if (config != null) {
            if (!config.dnsOnlyResolver.isNullOrBlank()) return "DNS-only"
            return when (config.configType) {
                com.v2ray.ang.enums.EConfigType.VMESS -> "VMess"
                com.v2ray.ang.enums.EConfigType.VLESS -> "VLESS"
                com.v2ray.ang.enums.EConfigType.SHADOWSOCKS -> "Shadowsocks"
                com.v2ray.ang.enums.EConfigType.TROJAN -> "Trojan"
                com.v2ray.ang.enums.EConfigType.HYSTERIA2 -> "Hysteria2"
                com.v2ray.ang.enums.EConfigType.HYSTERIA -> "Hysteria"
                com.v2ray.ang.enums.EConfigType.WIREGUARD -> "WireGuard"
                com.v2ray.ang.enums.EConfigType.AMNEZIAWG -> "AmneziaWG"
                com.v2ray.ang.enums.EConfigType.MIHOMO -> "Mihomo"
                com.v2ray.ang.enums.EConfigType.MASTERDNS -> "MasterDNS"
                com.v2ray.ang.enums.EConfigType.STORMDNS -> "StormDNS"
                com.v2ray.ang.enums.EConfigType.OPENVPN -> "OpenVPN"
                com.v2ray.ang.enums.EConfigType.OPENCONNECT -> "OpenConnect"
                com.v2ray.ang.enums.EConfigType.IKEV2 -> "IKEv2"
                com.v2ray.ang.enums.EConfigType.SSH -> "SSH"
                com.v2ray.ang.enums.EConfigType.DNSTT -> "DNSTT"
                com.v2ray.ang.enums.EConfigType.AETHER -> "Aether"
                com.v2ray.ang.enums.EConfigType.SOCKS -> "SOCKS"
                com.v2ray.ang.enums.EConfigType.HTTP -> "HTTP"
                com.v2ray.ang.enums.EConfigType.CUSTOM -> "Custom JSON"
                else -> config.configType.name
            }
        }
    }
    return when {
        url.startsWith("vmess://") -> "VMess"
        url.startsWith("vless://") -> "VLESS"
        url.startsWith("ss://") -> "Shadowsocks"
        url.startsWith("trojan://") -> "Trojan"
        url.startsWith("hysteria2://") || url.startsWith("hy2://") -> "Hysteria2"
        url.startsWith("hysteria://") -> "Hysteria"
        url.startsWith("wireguard://") || url.startsWith("wg://") -> "WireGuard"
        url.startsWith("awg://") || url.contains("awg-config") -> "AmneziaWG"
        url.startsWith("ovpn://") || url.contains("ovpn-config") -> "OpenVPN"
        url.startsWith("ssh://") -> "SSH"
        url.startsWith("dnstt://") -> "DNSTT"
        url.startsWith("socks://") || url.startsWith("socks5://") -> "SOCKS"
        url.startsWith("http://") || url.startsWith("https://") -> "HTTP"
        else -> "Proxy"
    }
}

