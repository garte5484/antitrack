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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private enum class AppTab(val labelRes: Int, val iconRes: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    LOGS(R.string.tab_logs, R.drawable.ic_tab_logs),
    SETTINGS(R.string.tab_settings, R.drawable.ic_tab_settings)
}

@Composable
fun AntiTrackScreen(
    onToggleVpn: () -> Unit,
    updateState: UpdateState = UpdateState.None,
    onDownloadUpdate: (UpdateInfo) -> Unit = {},
    onCancelUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {}
) {
    var isRunning by remember { mutableStateOf(BlockVpnService.isRunning) }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var blockTrackers by remember { mutableStateOf(AppSettings.blockTrackers()) }
    var blockAds by remember { mutableStateOf(AppSettings.blockAds()) }
    var showSettings by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    val context = LocalContext.current
    var usageAccess by remember { mutableStateOf(hasUsageAccess(context)) }
    val stats by BlockStats.state

    val statusText = when {
        !isRunning -> stringResource(R.string.status_off)
        blockTrackers && blockAds -> stringResource(R.string.status_on_all)
        blockTrackers -> stringResource(R.string.status_on_trackers)
        blockAds -> stringResource(R.string.status_on_ads)
        else -> stringResource(R.string.status_on)
    }
    val cannotStart = !isRunning && !blockTrackers && !blockAds
    val statusDescription = when {
        !isRunning -> stringResource(R.string.status_description_off)
        blockTrackers && blockAds -> stringResource(R.string.status_description_all)
        blockTrackers -> stringResource(R.string.status_description_trackers)
        blockAds -> stringResource(R.string.status_description_ads)
        else -> stringResource(R.string.status_description_on)
    }

    val totalTrackers = stats.values.sumOf { pkg -> pkg.values.sumOf { it.trackers } }.toInt()
    val totalAds = stats.values.sumOf { pkg -> pkg.values.sumOf { it.ads } }.toInt()
    val totalBlocks = totalTrackers + totalAds
    val appCount = stats.size
    val statsSummary = buildString {
        append("Всего заблокировано ")
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
            usageAccess = hasUsageAccess(context)
            delay(500)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = {
                            currentTab = tab
                            selectedApp = null
                            showSettings = false
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
    ) { innerPadding ->
        when (currentTab) {
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
                    onClick = { showSettings = true },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.settings_button))
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

            if (stats.isEmpty()) {
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
                val rows = stats.entries.sortedByDescending { entry -> entry.value.values.sumOf { it.total } }
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
            AppTab.SETTINGS -> SettingsPlaceholder(Modifier.padding(innerPadding))
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

        val trackerRows = remember(stats, dialogApp) {
            companyRows(stats[dialogApp].orEmpty(), trackersTab = true)
        }
        val adsRows = remember(stats, dialogApp) {
            companyRows(stats[dialogApp].orEmpty(), trackersTab = false)
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
        var blockTrackersForApp by remember(dialogApp) { mutableStateOf(PerAppSettings.isTrackerBlockingEnabled(dialogApp)) }
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text(BlockStats.displayName(context, dialogApp)) },
            text = {
                Column {
                    if (dialogApp != BlockStats.SYSTEM && dialogApp != BlockStats.OTHER && !dialogApp.startsWith("uid:")) {
                        SettingsSwitch(
                            text = "Блокировать трекеры для этого приложения",
                            checked = blockTrackersForApp,
                            onCheckedChange = { value ->
                                blockTrackersForApp = value
                                PerAppSettings.setTrackerBlockingEnabled(dialogApp, value)
                            }
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
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
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
                                        text = name,
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
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedApp = null }) {
                    Text(stringResource(R.string.dialog_close))
                }
            }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.settings_title)) },
            text = {
                Column {
                    SettingsSwitch(
                        text = stringResource(R.string.settings_block_trackers),
                        checked = blockTrackers,
                        onCheckedChange = { value ->
                            blockTrackers = value
                            AppSettings.setBlockTrackers(value)
                        }
                    )
                    SettingsSwitch(
                        text = stringResource(R.string.settings_block_ads),
                        checked = blockAds,
                        onCheckedChange = { value ->
                            blockAds = value
                            AppSettings.setBlockAds(value)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_list_size,
                            BlocklistRepository.trackerCount,
                            BlocklistRepository.adCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text(stringResource(R.string.dialog_close))
                }
            }
        )
    }

    when (val update = updateState) {
        UpdateState.None -> Unit
        is UpdateState.Available -> AlertDialog(
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
                    val notes = update.info.notes
                    if (!notes.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.logs_count, entries.size),
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

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { entry ->
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
            RequestLog.Status.TRACKER -> "TrackerBlocklist (домен/поддомены/ключевое слово)"
            RequestLog.Status.ADS -> "AdBlocklist (домен/поддомены/ключевое слово)"
            RequestLog.Status.THROTTLE -> "Трекер-троттлинг (временный)"
            RequestLog.Status.CUSTOM_BLOCKED -> "Пользовательское правило (точный хост)"
        }
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(stringResource(R.string.logs_details_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow(stringResource(R.string.logs_details_host), entry.host)
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

@Composable
private fun SettingsPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
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
private fun SettingsSwitch(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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