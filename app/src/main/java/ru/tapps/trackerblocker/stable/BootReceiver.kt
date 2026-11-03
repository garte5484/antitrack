package ru.tapps.trackerblocker.stable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

/**
 * Поднимает защиту после перезагрузки устройства или обновления приложения,
 * если пользователь оставил её включённой.
 *
 * Согласие на VPN система хранит между перезагрузками, поэтому повторный
 * диалог не нужен. Если согласие всё-таки отозвали, сервис не запускаем
 * и сбрасываем флаг, чтобы не пытаться при каждом следующем старте.
 *
 * Система разрешает запуск foreground-сервиса из BOOT_COMPLETED и
 * MY_PACKAGE_REPLACED, несмотря на ограничения фонового запуска.
 *
 * На ColorOS (Oppo, Realme, OnePlus), MIUI и EMUI broadcast приходит только
 * если приложению разрешён автозапуск. Результат каждой попытки пишется в
 * настройки, чтобы это можно было проверить без logcat.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT -> Unit
            else -> return
        }

        AppSettings.init(context)
        if (!AppSettings.isProtectionEnabled()) {
            AppSettings.setAutostartResult(AppSettings.AUTOSTART_DISABLED)
            return
        }
        if (!AppSettings.blockTrackers() && !AppSettings.blockAds()) {
            AppSettings.setAutostartResult(AppSettings.AUTOSTART_DISABLED)
            return
        }

        val consentNeeded = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось проверить согласие на VPN — автозапуск отменён", e)
            AppSettings.setAutostartResult(AppSettings.AUTOSTART_ERROR)
            return
        }
        if (consentNeeded != null) {
            Log.w(TAG, "Согласие на VPN отозвано — автозапуск отменён")
            AppSettings.setProtectionEnabled(false)
            AppSettings.setAutostartResult(AppSettings.AUTOSTART_NO_CONSENT)
            return
        }

        Log.i(TAG, "Автозапуск защиты (${intent.action})")
        val service = Intent(context, BlockVpnService::class.java)
            .putExtra(BlockVpnService.EXTRA_AUTOSTART, true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
            // Сервис заменит результат на AUTOSTART_STARTED, когда туннель
            // действительно поднимется. Если осталось "requested" — значит,
            // система не дала запустить foreground-сервис.
            AppSettings.setAutostartResult(AppSettings.AUTOSTART_REQUESTED)
        } catch (e: Exception) {
            // Например, запрет автозапуска на некоторых прошивках.
            Log.w(TAG, "Не удалось запустить защиту после перезагрузки", e)
            AppSettings.setAutostartResult(AppSettings.AUTOSTART_ERROR)
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"

        /** Нестандартный broadcast, который шлют некоторые прошивки. */
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
