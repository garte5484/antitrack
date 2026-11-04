package ru.tapps.trackerblocker.stable

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tapps.trackerblocker.stable.ui.theme.AntiTrackTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

/** Какая вкладка компаний открыта в диалоге приложения. */
private enum class CompanyTab { TRACKERS, ADS }

/** Фильтр логов по результату DNS-запроса. */
private enum class LogFilter { ALL, BLOCKED, ALLOWED }

/** Состояние проверки обновлений на главном экране. */
sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
}

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Пользователь вернулся из настроек «Установка неизвестных приложений».
        val file = pendingInstall
        pendingInstall = null
        if (file != null && file.exists()) {
            if (canInstallPackages()) installApk(file)
            else Toast.makeText(this, R.string.update_install_permission_hint, Toast.LENGTH_LONG).show()
        }
    }

    private var updateState by mutableStateOf<UpdateState>(UpdateState.None)
    private var downloadJob: Job? = null
    private var pendingInstall: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BlockStats.init(this)
        AppSettings.init(this)
        PerAppSettings.init(this)
        RequestLog.init(this)
        HostRules.init(this)
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BlocklistRepository.refreshIfStale(applicationContext)
            }
        }
        checkForUpdates()
        setContent {
            AntiTrackTheme {
                AntiTrackScreen(
                    onToggleVpn = ::toggleVpn,
                    updateState = updateState,
                    onDownloadUpdate = ::startUpdateDownload,
                    onCancelUpdate = ::cancelUpdateDownload,
                    onDismissUpdate = { updateState = UpdateState.None }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Страховка: пользователь вернулся из настроек «Установка неизвестных
        // приложений» без результата, но разрешение уже выдано — продолжаем.
        val file = pendingInstall
        if (file != null && file.exists() && canInstallPackages()) {
            pendingInstall = null
            installApk(file)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toggleVpn() {
        if (BlockVpnService.isWaitingForOtherVpn) {
            // Кнопка на главном экране в этом состоянии означает «Включить
            // сейчас»: пользователь явно просит вернуть защиту и вытеснить
            // чужой VPN.
            Log.i("MainActivity", "toggleVpn: вернуть защиту немедленно")
            startService(
                Intent(this, BlockVpnService::class.java)
                    .setAction(BlockVpnService.ACTION_RESUME_NOW)
            )
            return
        }
        if (BlockVpnService.isRunning) {
            Log.i("MainActivity", "toggleVpn: ВЫКЛ (isRunning=true)")
            val intent = Intent(this, BlockVpnService::class.java)
                .setAction(BlockVpnService.ACTION_STOP)
            startService(intent)
            return
        }
        if (!AppSettings.blockTrackers() && !AppSettings.blockAds()) {
            Log.i("MainActivity", "toggleVpn: отказ — и трекеры, и реклама отключены")
            Toast.makeText(this, R.string.start_guard_hint, Toast.LENGTH_SHORT).show()
            return
        }
        Log.i("MainActivity", "toggleVpn: ВКЛ")
        val prepared = VpnService.prepare(this)
        if (prepared != null) {
            Log.i("MainActivity", "нужно согласие, открываю диалог")
            vpnPermissionLauncher.launch(prepared)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        // Запоминаем намерение пользователя: после перезагрузки BootReceiver
        // поднимет защиту автоматически.
        AppSettings.setProtectionEnabled(true)
        val intent = Intent(this, BlockVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** Проверяет GitHub Releases при заходе в приложение. Ошибки сети не показываем. */
    private fun checkForUpdates() {
        lifecycleScope.launch {
            val info = UpdateChecker.check(BuildConfig.VERSION_NAME)
            if (info != null) {
                Log.i("MainActivity", "Доступно обновление ${info.version} (${info.apkName})")
                updateState = UpdateState.Available(info)
            }
        }
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        updateState = UpdateState.Downloading(info, 0)
        downloadJob = lifecycleScope.launch {
            try {
                val file = UpdateChecker.download(applicationContext, info) { percent ->
                    updateState = UpdateState.Downloading(info, percent)
                }
                updateState = UpdateState.None
                installApk(file)
            } catch (e: CancellationException) {
                updateState = UpdateState.None
                throw e
            } catch (e: Exception) {
                Log.w("MainActivity", "Не удалось скачать обновление", e)
                updateState = UpdateState.None
                Toast.makeText(this@MainActivity, R.string.update_download_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cancelUpdateDownload() {
        downloadJob?.cancel()
        downloadJob = null
        updateState = UpdateState.None
    }

    /** Разрешена ли установка APK из неизвестных источников (Android 8+). */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    /**
     * Открывает системный установщик с кнопками «Установить»/«Отмена».
     * Если установка из неизвестных источников запрещена — просим разрешение
     * в настройках, а после возврата продолжаем установку.
     */
    private fun installApk(file: File) {
        if (!canInstallPackages()) {
            pendingInstall = file
            Toast.makeText(this, R.string.update_install_permission_hint, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:$packageName"))
            try {
                installPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                Log.w("MainActivity", "Не удалось открыть настройки установки", e)
                pendingInstall = null
                Toast.makeText(this, R.string.update_install_permission_hint, Toast.LENGTH_LONG).show()
            }
            return
        }
        pendingInstall = null
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("MainActivity", "Не удалось открыть системный установщик", e)
            Toast.makeText(this, R.string.update_install_error, Toast.LENGTH_LONG).show()
        }
    }
}

/** Выдан ли приложению «Доступ к использованию приложений» (UsageStats). */
private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Системный экран «Постоянная VPN». Это самый надёжный автозапуск: после
 * перезагрузки VPN поднимает сама Android, без BOOT_COMPLETED и без
 * разрешения автозапуска производителя.
 */
private fun openVpnSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    } catch (_: Exception) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

/**
 * «Частный DNS» (Private DNS) в Android. Если он включён в режиме
 * «Имя хоста», DoT-запросы уходят мимо VPN-резолвера, и фильтрация
 * частично обходится — об этом стоит предупредить пользователя.
 */
private fun openPrivateDnsSettings(context: Context) {
    try {
        // Строка действия не входит в публичные константы Settings,
        // поэтому используем её напрямую.
        context.startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
    } catch (_: Exception) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

/**
 * Экран автозапуска у производителей. На ColorOS (Oppo, Realme, OnePlus),
 * MIUI и EMUI без этого разрешения broadcast BOOT_COMPLETED приложению
 * не доставляется, и защита после перезагрузки не поднимается.
 */
private fun openAutostartSettings(context: Context) {
    val candidates = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
    )
    for ((pkg, cls) in candidates) {
        try {
            context.startActivity(
                Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) {
            // на этой прошивке такого экрана нет — пробуем следующий
        }
    }
    // Фолбэк: карточка приложения, откуда настройки автозапуска тоже доступны.
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + context.packageName))
        )
    } catch (_: Exception) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

private enum class AppTab(val labelRes: Int, val iconRes: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    LOGS(R.string.tab_logs, R.drawable.ic_tab_logs),
    SETTINGS(R.string.tab_settings, R.drawable.ic_tab_settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiTrackScreen(
    onToggleVpn: () -> Unit,
    updateState: UpdateState = UpdateState.None,
    onDownloadUpdate: (UpdateInfo) -> Unit = {},
    onCancelUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {}
) {
    var isRunning by remember { mutableStateOf(BlockVpnService.isRunning) }
    var waitingForOtherVpn by remember { mutableStateOf(BlockVpnService.isWaitingForOtherVpn) }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var blockTrackers by remember { mutableStateOf(AppSettings.blockTrackers()) }
    var blockAds by remember { mutableStateOf(AppSettings.blockAds()) }
    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    var showRules by remember { mutableStateOf(false) }
    var showBlocklists by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(AppSettings.isPaused()) }
    val context = LocalContext.current
    var usageAccess by remember { mutableStateOf(hasUsageAccess(context)) }
    val stats by BlockStats.state
    var statsPeriod by rememberSaveable { mutableStateOf(BlockStats.Period.ALL) }
    val periodStats = remember(stats, statsPeriod) { BlockStats.statsFor(statsPeriod) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Настройки влияют на текст уведомления и состав исключённых из VPN приложений.
    val applyServiceSettings = {
        if (BlockVpnService.isRunning) {
            context.startService(
                Intent(context, BlockVpnService::class.java)
                    .setAction(BlockVpnService.ACTION_REFRESH)
            )
        }
    }

    BackHandler(enabled = showRules || showBlocklists) {
        if (showBlocklists) showBlocklists = false else showRules = false
    }

    val pausedUntilText = remember(paused) {
        if (paused) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(AppSettings.pauseUntil()))
        } else {
            ""
        }
    }
    val statusText = when {
        waitingForOtherVpn -> stringResource(R.string.status_waiting)
        !isRunning -> stringResource(R.string.status_off)
        paused -> stringResource(R.string.status_paused)
        blockTrackers && blockAds -> stringResource(R.string.status_on_all)
        blockTrackers -> stringResource(R.string.status_on_trackers)
        blockAds -> stringResource(R.string.status_on_ads)
        else -> stringResource(R.string.status_on)
    }
    val cannotStart = !isRunning && !blockTrackers && !blockAds
    val statusDescription = when {
        waitingForOtherVpn -> stringResource(R.string.status_description_waiting)
        !isRunning -> stringResource(R.string.status_description_off)
        paused -> stringResource(R.string.status_description_paused, pausedUntilText)
        blockTrackers && blockAds -> stringResource(R.string.status_description_all)
        blockTrackers -> stringResource(R.string.status_description_trackers)
        blockAds -> stringResource(R.string.status_description_ads)
        else -> stringResource(R.string.status_description_on)
    }

    val totalTrackers = periodStats.values.sumOf { pkg -> pkg.values.sumOf { it.trackers } }.toInt()
    val totalAds = periodStats.values.sumOf { pkg -> pkg.values.sumOf { it.ads } }.toInt()
    val totalBlocks = totalTrackers + totalAds
    val appCount = periodStats.size
    val summaryPrefix = stringResource(
        when (statsPeriod) {
            BlockStats.Period.TODAY -> R.string.stats_summary_today
            BlockStats.Period.WEEK -> R.string.stats_summary_week
            BlockStats.Period.ALL -> R.string.stats_summary_all
        }
    )
    val statsSummary = buildString {
        append(summaryPrefix)
        append(' ')
        append(totalBlocks)
        append(" (")
        append(totalTrackers)
        append(' ')
        append(russianPlural(totalTrackers, "трекер", "трекера", "трекеров"))
        append(" и ")
        append(totalAds)
        append(' ')
        append(russianPlural(totalAds, "реклама", "рекламы", "реклам"))
        append(") в ")
        append(appCount)
        append(' ')
        append(russianPlural(appCount, "приложении", "приложениях", "приложениях"))
    }

    LaunchedEffect(Unit) {
        while (true) {
            isRunning = BlockVpnService.isRunning
            waitingForOtherVpn = BlockVpnService.isWaitingForOtherVpn
            paused = AppSettings.isPaused()
            usageAccess = hasUsageAccess(context)
            delay(500)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!showRules && !showBlocklists) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = {
                                currentTab = tab
                                selectedApp = null
                                showRules = false
                                showBlocklists = false
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (showBlocklists) {
            BlocklistsScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { showBlocklists = false }
            )
        } else if (showRules) {
            RulesScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { showRules = false }
            )
        } else when (currentTab) {
            AppTab.HOME -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ShieldIcon(isActive = isRunning)

                Spacer(Modifier.height(24.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (isRunning) colorResource(R.color.active_color)
                    else MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = statusDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onToggleVpn,
                    enabled = !cannotStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            if (isRunning) R.string.toggle_stop else R.string.toggle_start
                        )
                    )
                }

                TextButton(
                    onClick = { currentTab = AppTab.SETTINGS },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.settings_button))
                }

                if (isRunning && paused) {
                    TextButton(onClick = {
                        context.startService(
                            Intent(context, BlockVpnService::class.java)
                                .setAction(BlockVpnService.ACTION_RESUME)
                        )
                        paused = false
                    }) {
                        Text(stringResource(R.string.notification_action_resume))
                    }
                }

                if (cannotStart) {
                    Text(
                        text = stringResource(R.string.start_guard_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = statsSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { BlockStats.clear() }) {
                    Text(stringResource(R.string.stats_reset))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = statsPeriod == BlockStats.Period.TODAY,
                    onClick = { statsPeriod = BlockStats.Period.TODAY },
                    label = { Text(stringResource(R.string.stats_period_today)) }
                )
                FilterChip(
                    selected = statsPeriod == BlockStats.Period.WEEK,
                    onClick = { statsPeriod = BlockStats.Period.WEEK },
                    label = { Text(stringResource(R.string.stats_period_week)) }
                )
                FilterChip(
                    selected = statsPeriod == BlockStats.Period.ALL,
                    onClick = { statsPeriod = BlockStats.Period.ALL },
                    label = { Text(stringResource(R.string.stats_period_all)) }
                )
            }

            if (!usageAccess) {
                Surface(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = stringResource(R.string.stats_usage_access_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (periodStats.isEmpty()) {
                Text(
                    text = stringResource(
                        if (statsPeriod == BlockStats.Period.ALL) R.string.stats_empty
                        else R.string.stats_empty_period
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                val rows = periodStats.entries.sortedByDescending { entry -> entry.value.values.sumOf { it.total } }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { (pkg, labels) ->
                        AppRow(
                            context = context,
                            packageName = pkg,
                            labels = labels,
                            blockTrackers = blockTrackers,
                            blockAds = blockAds,
                            onClick = { selectedApp = pkg }
                        )
                    }
                }
            }
        }
            AppTab.LOGS -> LogsScreen(Modifier.padding(innerPadding))
            AppTab.SETTINGS -> SettingsScreen(
                modifier = Modifier.padding(innerPadding),
                blockTrackers = blockTrackers,
                blockAds = blockAds,
                usageAccess = usageAccess,
                onBlockTrackersChange = {
                    blockTrackers = it
                    AppSettings.setBlockTrackers(it)
                    applyServiceSettings()
                },
                onBlockAdsChange = {
                    blockAds = it
                    AppSettings.setBlockAds(it)
                    applyServiceSettings()
                },
                onExclusionsChanged = applyServiceSettings,
                onOpenRules = { showRules = true },
                onOpenBlocklists = { showBlocklists = true },
                onSettingsImported = {
                    blockTrackers = AppSettings.blockTrackers()
                    blockAds = AppSettings.blockAds()
                    applyServiceSettings()
                }
            )
        }
    }

    val dialogApp = selectedApp
    if (dialogApp != null) {
        // В статистике лежит полный домен, но строка одна на бренд: хосты
        // app.appsflyer.com и events.appsflyer.com склеиваем в "AppsFlyer",
        // а неизвестные домены — по корневому лейблу. Для аватара оставляем
        // любой исходный домен группы: brandOf() опознаёт бренд по нему.
        // Вкладки «Трекеры»/«Реклама»: вкладка показывается только если
        // соответствующий тип блокировки включён в настройках И в ней есть
        // записи. Если осталась одна вкладка — панель вкладок не нужна.
        fun companyRows(
            hosts: Map<String, BlockStats.HostCounts>,
            trackersTab: Boolean
        ): List<Triple<String, Long, String>> =
            hosts.entries
                .filter { if (trackersTab) it.value.trackers > 0 else it.value.ads > 0 }
                .groupBy { BrandNames.displayName(it.key) }
                .map { (name, group) ->
                    Triple(
                        name,
                        if (trackersTab) group.sumOf { it.value.trackers }
                        else group.sumOf { it.value.ads },
                        group.first().key
                    )
                }
                .sortedByDescending { it.second }

        val trackerRows = remember(periodStats, dialogApp) {
            companyRows(periodStats[dialogApp].orEmpty(), trackersTab = true)
        }
        val adsRows = remember(periodStats, dialogApp) {
            companyRows(periodStats[dialogApp].orEmpty(), trackersTab = false)
        }

        val tabs = buildList {
            if (blockTrackers && trackerRows.isNotEmpty()) add(CompanyTab.TRACKERS)
            if (blockAds && adsRows.isNotEmpty()) add(CompanyTab.ADS)
        }
        var tabIndex by remember(dialogApp) { mutableStateOf(0) }
        val currentTab = if (tabIndex in tabs.indices) tabs[tabIndex] else CompanyTab.TRACKERS
        val rows = when (currentTab) {
            CompanyTab.TRACKERS -> trackerRows
            CompanyTab.ADS -> adsRows
        }

        // Проценты считаем внутри текущей вкладки: сравнивать трекеры с
        // рекламой некорректно, иначе все трекеры казались бы «малыми» на
        // фоне приложения с интенсивной рекламой.
        val rowsTotal = rows.sumOf { it.second }

        fun isMetaBrand(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("facebook") || lower.contains("instagram") || lower.contains("meta")
        }

        /** Мало блокировок: меньше 30 или меньше 15% от всех на вкладке. */
        fun isLowConfidence(count: Long): Boolean =
            count < 30L || count.toDouble() < rowsTotal * 0.15

        var blockTrackersForApp by remember(dialogApp) { mutableStateOf(PerAppSettings.isTrackerBlockingEnabled(dialogApp)) }
        ModalBottomSheet(
            onDismissRequest = { selectedApp = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = BlockStats.displayName(context, dialogApp),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(12.dp))
                if (dialogApp != BlockStats.SYSTEM && dialogApp != BlockStats.OTHER && !dialogApp.startsWith("uid:")) {
                    SettingsSwitch(
                        text = stringResource(R.string.settings_block_trackers_for_app),
                        checked = blockTrackersForApp,
                        onCheckedChange = { value ->
                            blockTrackersForApp = value
                            PerAppSettings.setTrackerBlockingEnabled(dialogApp, value)
                            applyServiceSettings()
                        }
                    )
                    Text(
                        text = stringResource(R.string.settings_app_excluded_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (tabs.size > 1) {
                    TabRow(selectedTabIndex = tabs.indexOf(currentTab).coerceAtLeast(0)) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = tab == currentTab,
                                onClick = { tabIndex = index },
                                text = {
                                    Text(
                                        text = stringResource(
                                            if (tab == CompanyTab.TRACKERS) R.string.tabs_trackers
                                            else R.string.tabs_ads
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        items(rows, key = { it.first }) { (name, count, host) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LabelAvatar(domain = host, size = 32.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = when {
                                        isMetaBrand(name) -> "$name**"
                                        dialogApp != BlockStats.OTHER && isLowConfidence(count) -> "$name*"
                                        else -> name
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(Modifier.width(8.dp))
                                Text(
                                    count.toString(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (dialogApp != BlockStats.OTHER &&
                    rows.any { !isMetaBrand(it.first) && isLowConfidence(it.second) }
                ) {
                    Text(
                        text = stringResource(R.string.stats_possible_misattribution),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }
                if (rows.any { isMetaBrand(it.first) }) {
                    Text(
                        text = stringResource(R.string.stats_meta_affiliation_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = { selectedApp = null },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.dialog_close))
                }
            }
        }
    }

    when (val update = updateState) {
        UpdateState.None -> Unit
        is UpdateState.Available -> {
            var notesExpanded by remember(update.info.version) { mutableStateOf(false) }
            val rawNotes = update.info.notes
            val cleanedNotes = rawNotes?.replace("#", "")?.replace("*", "")?.replace("_", " ")?.trim()
            AlertDialog(
                onDismissRequest = onDismissUpdate,
                title = { Text(stringResource(R.string.update_available_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.update_available_message,
                                update.info.version,
                                BuildConfig.VERSION_NAME
                            )
                        )
                        if (!cleanedNotes.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            val lines = cleanedNotes.lines().filter { it.isNotBlank() }
                            if (notesExpanded || lines.size <= 8) {
                                Text(
                                    text = cleanedNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = lines.take(8).joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!notesExpanded && lines.size > 8) {
                                TextButton(onClick = { notesExpanded = true }) {
                                    Text(stringResource(R.string.logs_show_more))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onDownloadUpdate(update.info) }) {
                        Text(stringResource(R.string.update_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissUpdate) {
                        Text(stringResource(R.string.update_cancel))
                    }
                }
            )
        }
        is UpdateState.Downloading -> AlertDialog(
            // Загрузку можно прервать только кнопкой «Отмена».
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.update_downloading_message, update.info.version))
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { update.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${update.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onCancelUpdate) {
                    Text(stringResource(R.string.update_cancel))
                }
            }
        )
    }
}

@Composable
private fun LogsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entries by RequestLog.state
    var enabled by remember { mutableStateOf(RequestLog.isEnabled) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    // Названия приложений запрашиваем у PackageManager только когда в логе
    // появляется новое приложение, а не на каждую новую запись.
    val packageNames = entries.asSequence()
        .map { it.packageName }
        .distinct()
        .sorted()
        .toList()
    val appNames = remember(packageNames) {
        packageNames.associateWith { BlockStats.displayName(context, it) }
    }
    var selectedEntry by remember { mutableStateOf<RequestLog.Entry?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LogFilter.ALL) }

    val filteredEntries = remember(entries, query, filter, appNames) {
        val needle = query.trim().lowercase()
        entries.filter { entry ->
            val statusMatches = when (filter) {
                LogFilter.ALL -> true
                LogFilter.BLOCKED -> entry.status != RequestLog.Status.ALLOWED
                LogFilter.ALLOWED -> entry.status == RequestLog.Status.ALLOWED
            }
            if (!statusMatches) return@filter false
            if (needle.isEmpty()) return@filter true
            entry.host.lowercase().contains(needle) ||
                (appNames[entry.packageName] ?: entry.packageName).lowercase().contains(needle)
        }
    }
    val isFiltering = query.isNotBlank() || filter != LogFilter.ALL

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.logs_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    enabled = !enabled
                    RequestLog.setEnabled(enabled)
                }
            ) {
                Text(stringResource(if (enabled) R.string.logs_disable else R.string.logs_enable))
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.logs_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        if (!enabled) {
            Text(
                text = stringResource(R.string.logs_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                textAlign = TextAlign.Center
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.logs_search_hint)) }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilter.entries.forEach { value ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = {
                            Text(
                                stringResource(
                                    when (value) {
                                        LogFilter.ALL -> R.string.logs_filter_all
                                        LogFilter.BLOCKED -> R.string.logs_filter_blocked
                                        LogFilter.ALLOWED -> R.string.logs_filter_allowed
                                    }
                                )
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isFiltering) {
                        stringResource(R.string.logs_filtered_count, filteredEntries.size, entries.size)
                    } else {
                        stringResource(R.string.logs_count, entries.size)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { RequestLog.clear() }) {
                        Text(stringResource(R.string.logs_clear))
                    }
                }
            }

            when {
                entries.isEmpty() -> Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    textAlign = TextAlign.Center
                )
                filteredEntries.isEmpty() -> Text(
                    text = stringResource(R.string.logs_nothing_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    textAlign = TextAlign.Center
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        LogRow(
                            entry = entry,
                            appName = appNames[entry.packageName] ?: entry.packageName,
                            timeFormat = timeFormat,
                            onClick = { selectedEntry = entry }
                        )
                    }
                }
            }
        }
    }

    val entry = selectedEntry
    if (entry != null) {
        val appName = appNames[entry.packageName] ?: entry.packageName
        val statusText = stringResource(
            when (entry.status) {
                RequestLog.Status.ALLOWED -> R.string.log_status_allowed
                RequestLog.Status.TRACKER -> R.string.log_status_tracker
                RequestLog.Status.ADS -> R.string.log_status_ads
                RequestLog.Status.THROTTLE -> R.string.log_status_throttle
                RequestLog.Status.CUSTOM_BLOCKED -> R.string.log_status_custom_blocked
            }
        )
        val patternText = when (entry.status) {
            RequestLog.Status.ALLOWED -> stringResource(R.string.logs_details_pattern_none)
            RequestLog.Status.TRACKER -> {
                val p = entry.matchPattern
                if (p.isNullOrBlank()) "TrackerBlocklist" else "TrackerBlocklist: $p"
            }
            RequestLog.Status.ADS -> {
                val p = entry.matchPattern
                if (p.isNullOrBlank()) "AdBlocklist" else "AdBlocklist: $p"
            }
            RequestLog.Status.THROTTLE -> stringResource(R.string.logs_pattern_throttle)
            RequestLog.Status.CUSTOM_BLOCKED -> stringResource(R.string.logs_pattern_custom, entry.host)
        }
        var showFullHost by remember(entry.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(stringResource(R.string.logs_details_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column {
                        Text(stringResource(R.string.logs_details_host), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = entry.host,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (showFullHost) Int.MAX_VALUE else 1,
                            overflow = if (showFullHost) TextOverflow.Visible else TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { showFullHost = !showFullHost }) {
                            Text(
                                stringResource(
                                    if (showFullHost) R.string.logs_show_less
                                    else R.string.logs_show_more
                                )
                            )
                        }
                    }
                    DetailRow(stringResource(R.string.logs_details_app), appName)
                    DetailRow(stringResource(R.string.logs_details_time), dateFormat.format(Date(entry.timeMs)))
                    DetailRow(
                        stringResource(R.string.logs_details_src_ip),
                        entry.srcIp ?: stringResource(R.string.logs_unknown_ip)
                    )
                    DetailRow(
                        stringResource(R.string.logs_details_dst_ip),
                        entry.dstIp ?: stringResource(R.string.logs_unknown_ip)
                    )
                    DetailRow(stringResource(R.string.logs_details_status), statusText)
                    DetailRow(stringResource(R.string.logs_details_pattern), patternText)
                }
            },
            confirmButton = {
                when (entry.status) {
                    RequestLog.Status.ALLOWED -> {
                        Button(onClick = {
                            HostRules.forceBlock(entry.host)
                            selectedEntry = null
                        }) {
                            Text(stringResource(R.string.logs_block_host, entry.host))
                        }
                    }
                    RequestLog.Status.TRACKER, RequestLog.Status.ADS, RequestLog.Status.THROTTLE, RequestLog.Status.CUSTOM_BLOCKED -> {
                        Button(onClick = {
                            HostRules.forceAllow(entry.host)
                            selectedEntry = null
                        }) {
                            Text(stringResource(R.string.logs_allow_host, entry.host))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEntry = null }) {
                    Text(stringResource(R.string.logs_close))
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogRow(
    entry: RequestLog.Entry,
    appName: String,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    val statusText = stringResource(
        when (entry.status) {
            RequestLog.Status.ALLOWED -> R.string.log_status_allowed
            RequestLog.Status.TRACKER -> R.string.log_status_tracker
            RequestLog.Status.ADS -> R.string.log_status_ads
            RequestLog.Status.THROTTLE -> R.string.log_status_throttle
            RequestLog.Status.CUSTOM_BLOCKED -> R.string.log_status_custom_blocked
        }
    )
    val statusColor = when (entry.status) {
        RequestLog.Status.ALLOWED -> colorResource(R.color.active_color)
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = entry.host,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "$appName · ${timeFormat.format(Date(entry.timeMs))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    blockTrackers: Boolean,
    blockAds: Boolean,
    usageAccess: Boolean,
    onBlockTrackersChange: (Boolean) -> Unit,
    onBlockAdsChange: (Boolean) -> Unit,
    onExclusionsChanged: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenBlocklists: () -> Unit,
    onSettingsImported: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableStateOf(BlocklistRepository.lastUpdated(context)) }
    var excluded by remember { mutableStateOf(PerAppSettings.disabledTrackerPackages()) }
    var dnsProviderId by remember { mutableStateOf(AppSettings.dnsProviderId()) }
    var customDns by remember { mutableStateOf(AppSettings.customDnsRaw()) }
    var dnsEncrypt by remember { mutableStateOf(AppSettings.dnsEncryptionEnabled()) }
    var blockNull by remember { mutableStateOf(AppSettings.blockWithNullAddress()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val saved = try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(SettingsBackup.export(context).toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            false
        }
        message = context.getString(
            if (saved) R.string.settings_backup_done else R.string.settings_backup_error
        )
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = null
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > 1_000_000) null
                        else bytes.toString(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) {
                message = context.getString(R.string.settings_backup_error)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                SettingsBackup.import(context, text)
            }
            if (result.success) {
                // Импорт меняет состояние настроек напрямую в SharedPreferences,
                // поэтому перечитываем локальные значения и сообщаем сервису.
                dnsProviderId = AppSettings.dnsProviderId()
                customDns = AppSettings.customDnsRaw()
                dnsEncrypt = AppSettings.dnsEncryptionEnabled()
                blockNull = AppSettings.blockWithNullAddress()
                excluded = PerAppSettings.disabledTrackerPackages()
                lastUpdated = BlocklistRepository.lastUpdated(context)
                onSettingsImported()
                message = context.getString(
                    R.string.settings_backup_imported,
                    result.rules,
                    result.apps
                )
            } else {
                message = context.getString(R.string.settings_backup_invalid)
            }
        }
    }
    val selectedProvider = DnsProviders.byId(dnsProviderId)
    // «Частный DNS» в режиме «Имя хоста» уводит DoT мимо VPN-резолвера.
    val privateDnsForced = remember {
        runCatching {
            Settings.Global.getString(context.contentResolver, "private_dns_mode")
        }.getOrNull() == "hostname"
    }
    val autostartAt = remember { AppSettings.lastAutostartAt() }
    val autostartResult = remember { AppSettings.lastAutostartResult() }
    val autostartStatus = if (autostartAt == 0L) {
        stringResource(R.string.settings_autostart_never)
    } else {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(autostartAt))
        val resultText = stringResource(
            when (autostartResult) {
                AppSettings.AUTOSTART_STARTED -> R.string.settings_autostart_started
                AppSettings.AUTOSTART_REQUESTED -> R.string.settings_autostart_requested
                AppSettings.AUTOSTART_NO_CONSENT -> R.string.settings_autostart_no_consent
                AppSettings.AUTOSTART_ERROR -> R.string.settings_autostart_error
                else -> R.string.settings_autostart_disabled
            }
        )
        stringResource(R.string.settings_autostart_last, time, resultText)
    }
    val updatedAt = remember(lastUpdated) {
        if (lastUpdated == 0L) null
        else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(lastUpdated))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_protection))
        SettingsSwitch(
            text = stringResource(R.string.settings_block_trackers),
            checked = blockTrackers,
            onCheckedChange = onBlockTrackersChange
        )
        SettingsSwitch(
            text = stringResource(R.string.settings_block_ads),
            checked = blockAds,
            onCheckedChange = onBlockAdsChange
        )

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_dns))
        Text(
            text = stringResource(R.string.settings_dns_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        DnsProviders.all.forEach { provider ->
            SettingsRadioRow(
                text = stringResource(provider.nameRes),
                selected = dnsProviderId == provider.id,
                onClick = {
                    dnsProviderId = provider.id
                    AppSettings.setDnsProviderId(provider.id)
                }
            )
        }
        if (selectedProvider.custom) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = customDns,
                onValueChange = {
                    customDns = it
                    AppSettings.setCustomDnsRaw(it)
                },
                label = { Text(stringResource(R.string.settings_dns_custom_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.settings_dns_custom_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (!selectedProvider.worksInRussia) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_dns_russia_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (selectedProvider.dotHost != null) {
            Spacer(Modifier.height(8.dp))
            SettingsSwitch(
                text = stringResource(R.string.settings_dns_encrypt),
                checked = dnsEncrypt,
                onCheckedChange = {
                    dnsEncrypt = it
                    AppSettings.setDnsEncryptionEnabled(it)
                }
            )
            Text(
                text = stringResource(R.string.settings_dns_encrypt_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (privateDnsForced) R.string.settings_dns_private_enabled_warning
                else R.string.settings_dns_private_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (privateDnsForced) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { openPrivateDnsSettings(context) }) {
            Text(stringResource(R.string.settings_dns_private_open))
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_block_response))
        SettingsRadioRow(
            text = stringResource(R.string.settings_block_response_nxdomain),
            selected = !blockNull,
            onClick = {
                blockNull = false
                AppSettings.setBlockWithNullAddress(false)
            }
        )
        SettingsRadioRow(
            text = stringResource(R.string.settings_block_response_null),
            selected = blockNull,
            onClick = {
                blockNull = true
                AppSettings.setBlockWithNullAddress(true)
            }
        )
        Text(
            text = stringResource(R.string.settings_block_response_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_autostart))
        Text(
            text = stringResource(R.string.settings_autostart_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = autostartStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { openAutostartSettings(context) }) {
            Text(stringResource(R.string.settings_autostart_open))
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { openVpnSettings(context) }) {
            Text(stringResource(R.string.settings_always_on_vpn))
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_excluded))
        Text(
            text = stringResource(R.string.settings_excluded_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        if (excluded.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_excluded_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            excluded.sortedBy { BlockStats.displayName(context, it).lowercase() }.forEach { pkg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = BlockStats.displayName(context, pkg),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = pkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            PerAppSettings.setTrackerBlockingEnabled(pkg, true)
                            excluded = PerAppSettings.disabledTrackerPackages()
                            onExclusionsChanged()
                        }
                    ) {
                        Text(stringResource(R.string.settings_excluded_restore))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_blocklists))
        Text(
            text = stringResource(
                R.string.settings_list_size,
                BlocklistRepository.trackerCount,
                BlocklistRepository.adCount
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = updatedAt?.let { stringResource(R.string.settings_blocklists_updated, it) }
                ?: stringResource(R.string.settings_blocklists_never),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenBlocklists) {
            Text(stringResource(R.string.settings_open_blocklists))
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_rules))
        Text(
            text = stringResource(
                R.string.settings_rules_summary,
                HostRules.blockedHosts().size,
                HostRules.allowedHosts().size
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenRules) {
            Text(stringResource(R.string.settings_open_rules))
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_backup))
        Text(
            text = stringResource(R.string.settings_backup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val name = "antitrack-backup-" +
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
                    exportLauncher.launch(name)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.settings_backup_export))
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.settings_backup_import))
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_access))
        Surface(
            onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = stringResource(
                    if (usageAccess) R.string.settings_usage_access_granted
                    else R.string.settings_usage_access_missing
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (usageAccess) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_about))
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsRadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun BlocklistsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoAll by remember { mutableStateOf(BlocklistRepository.isAutoUpdateEnabled(context)) }
    var states by remember {
        mutableStateOf(BlocklistRepository.states(context))
    }
    var updatingAll by remember { mutableStateOf(false) }
    var updatingKind by remember { mutableStateOf<BlocklistRepository.Kind?>(null) }
    var selected by remember { mutableStateOf<BlocklistRepository.Kind?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun reload() {
        states = BlocklistRepository.states(context)
    }

    val selectedKind = selected
    if (selectedKind != null) {
        BackHandler { selected = null }
        ListContentsScreen(
            modifier = modifier,
            kind = selectedKind,
            onBack = { selected = null }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.blocklists_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.blocklists_back))
            }
        }

        SettingsSwitch(
            text = stringResource(R.string.blocklists_autoupdate_all),
            checked = autoAll,
            onCheckedChange = {
                autoAll = it
                BlocklistRepository.setAutoUpdateEnabled(context, it)
            }
        )
        Text(
            text = stringResource(R.string.blocklists_autoupdate_all_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        for (state in states) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(kindTitle(state.kind)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (updatingKind == state.kind) {
                            LinearProgressIndicator(modifier = Modifier.width(48.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.blocklists_state,
                            state.count,
                            Formatter.formatShortFileSize(context, state.bytes),
                            state.lastUpdated.takeIf { it > 0L }?.let {
                                stringResource(
                                    R.string.blocklists_updated_at,
                                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                        .format(Date(it))
                                )
                            } ?: stringResource(R.string.blocklists_never)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.blocklists_source, state.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsSwitch(
                        text = stringResource(R.string.blocklists_autoupdate),
                        checked = state.autoUpdate,
                        enabled = autoAll,
                        onCheckedChange = {
                            BlocklistRepository.setListAutoUpdateEnabled(context, state.kind, it)
                            reload()
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (updatingKind != null) return@Button
                                updatingKind = state.kind
                                message = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        BlocklistRepository.forceRefresh(context, state.kind)
                                    }
                                    updatingKind = null
                                    reload()
                                    message = context.getString(
                                        when (result) {
                                            BlocklistRepository.RefreshResult.SUCCESS ->
                                                R.string.blocklists_update_done
                                            BlocklistRepository.RefreshResult.NO_INTERNET ->
                                                R.string.settings_update_blocklists_no_internet
                                            BlocklistRepository.RefreshResult.FAILED ->
                                                R.string.blocklists_update_failed
                                        }
                                    )
                                }
                            },
                            enabled = !updatingAll && updatingKind == null
                        ) {
                            Text(stringResource(R.string.blocklists_update))
                        }
                        OutlinedButton(
                            onClick = { selected = state.kind },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.blocklists_open))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (updatingAll || updatingKind != null) return@Button
                updatingAll = true
                message = null
                scope.launch {
                    val failed = withContext(Dispatchers.IO) {
                        BlocklistRepository.Kind.entries.count {
                            BlocklistRepository.forceRefresh(context, it) !=
                                BlocklistRepository.RefreshResult.SUCCESS
                        }
                    }
                    updatingAll = false
                    reload()
                    message = context.getString(
                        if (failed == 0) R.string.settings_update_blocklists_done
                        else R.string.blocklists_update_failed
                    )
                }
            },
            enabled = !updatingAll && updatingKind == null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.blocklists_update_all))
        }
        if (updatingAll) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun kindTitle(kind: BlocklistRepository.Kind): Int = when (kind) {
    BlocklistRepository.Kind.TRACKERS -> R.string.blocklists_trackers
    BlocklistRepository.Kind.ADS -> R.string.blocklists_ads
}

@Composable
private fun ListContentsScreen(
    modifier: Modifier = Modifier,
    kind: BlocklistRepository.Kind,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var entries by remember(kind) { mutableStateOf<List<String>?>(null) }
    var query by remember(kind) { mutableStateOf("") }

    LaunchedEffect(kind) {
        entries = withContext(Dispatchers.IO) {
            BlocklistRepository.entries(context, kind)
        }
    }

    val filtered = remember(entries, query) {
        val all = entries ?: return@remember emptyList()
        val needle = query.trim()
        if (needle.isEmpty()) all else all.filter { it.contains(needle, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(kindTitle(kind)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.blocklists_back))
            }
        }
        Text(
            text = stringResource(R.string.blocklists_entries, entries?.size ?: 0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.blocklists_search)) }
        )
        Spacer(Modifier.height(8.dp))
        when {
            entries == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            filtered.isEmpty() -> Text(
                text = stringResource(
                    if (query.isBlank()) R.string.blocklists_empty
                    else R.string.blocklists_not_found
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered) { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RulesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    var blocked by remember { mutableStateOf(HostRules.blockedHosts()) }
    var allowed by remember { mutableStateOf(HostRules.allowedHosts()) }
    var input by remember { mutableStateOf("") }
    val normalized = input.trim().lowercase().trimEnd('.')

    fun reload() {
        blocked = HostRules.blockedHosts()
        allowed = HostRules.allowedHosts()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.rules_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.rules_back))
            }
        }
        Text(
            text = stringResource(R.string.rules_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.rules_add_hint)) }
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    HostRules.forceBlock(normalized)
                    input = ""
                    reload()
                },
                enabled = normalized.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.rules_add_block))
            }
            OutlinedButton(
                onClick = {
                    HostRules.forceAllow(normalized)
                    input = ""
                    reload()
                },
                enabled = normalized.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.rules_add_allow))
            }
        }

        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                RuleSectionTitle(stringResource(R.string.rules_blocked_section, blocked.size))
            }
            if (blocked.isEmpty()) {
                item { RuleEmpty(stringResource(R.string.rules_empty_blocked)) }
            } else {
                items(blocked.sorted()) { host ->
                    RuleRow(host) {
                        HostRules.removeBlocked(host)
                        reload()
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                RuleSectionTitle(stringResource(R.string.rules_allowed_section, allowed.size))
            }
            if (allowed.isEmpty()) {
                item { RuleEmpty(stringResource(R.string.rules_empty_allowed)) }
            } else {
                items(allowed.sorted()) { host ->
                    RuleRow(host) {
                        HostRules.removeAllowed(host)
                        reload()
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun RuleEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun RuleRow(host: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = host,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.rules_remove))
        }
    }
}

@Composable
private fun AppRow(
    context: Context,
    packageName: String,
    labels: Map<String, BlockStats.HostCounts>,
    blockTrackers: Boolean,
    blockAds: Boolean,
    onClick: () -> Unit
) {
    val appName = remember(packageName) { BlockStats.displayName(context, packageName) }
    val icon: Bitmap? = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrNull()
            ?.toBitmap(48, 48)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(appName.take(1), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    val trackers = labels.values.sumOf { it.trackers }.toInt()
                    val ads = labels.values.sumOf { it.ads }.toInt()
                    val parts = mutableListOf<String>()
                    if (blockTrackers && trackers > 0) {
                        parts += "$trackers ${russianPlural(trackers, "трекер", "трекера", "трекеров")}"
                    }
                    if (blockAds && ads > 0) {
                        parts += "$ads ${russianPlural(ads, "реклама", "рекламы", "реклам")}"
                    }
                    if (parts.isNotEmpty()) {
                        val verb = if (trackers > 0) {
                            russianPlural(trackers, "Заблокирован", "Заблокировано", "Заблокировано")
                        } else {
                            russianPlural(ads, "Заблокирована", "Заблокировано", "Заблокировано")
                        }
                        append(verb).append(' ').append(parts.joinToString(" и "))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = labels.values.sumOf { it.total }.toString(),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun LabelAvatar(domain: String, size: Dp) {
    val brand = BrandNames.brandOf(domain)
    val iconRes = brand?.iconRes
    if (iconRes != null) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
        return
    }
    val background = brand?.color ?: BrandNames.avatarColor(domain)
    val glyph = brand?.glyph ?: domain.firstOrNull()?.uppercase() ?: "?"
    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = glyph,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SettingsSwitch(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ShieldIcon(isActive: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) colorResource(R.color.active_color)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.size(80.dp)
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_stat_shield),
            contentDescription = null,
            tint = if (isActive) androidx.compose.ui.graphics.Color.White
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AntiTrackScreenPreview() {
    AntiTrackTheme {
        AntiTrackScreen(onToggleVpn = {})
    }
}