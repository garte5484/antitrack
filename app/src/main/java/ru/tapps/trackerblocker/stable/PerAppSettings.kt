package ru.tapps.trackerblocker.stable

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки блокировки по отдельным приложениям.
 *
 * По умолчанию для всех приложений блокировка трекеров включена.
 */
object PerAppSettings {

    private const val PREF_NAME = "per_app_settings"
    private const val KEY_BLOCK_TRACKERS_PREFIX = "block_trackers_"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isTrackerBlockingEnabled(packageName: String): Boolean {
        val p = prefs ?: return true
        return p.getBoolean(KEY_BLOCK_TRACKERS_PREFIX + packageName, true)
    }

    fun setTrackerBlockingEnabled(packageName: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_BLOCK_TRACKERS_PREFIX + packageName, enabled)?.apply()
    }

    /**
     * Пакеты, для которых блокировка трекеров выключена. Они полностью
     * исключаются из VPN, чтобы их DNS-трафик шёл напрямую.
     */
    fun disabledTrackerPackages(): Set<String> {
        val all = prefs?.all ?: return emptySet()
        return all.asSequence()
            .filter { (key, value) -> key.startsWith(KEY_BLOCK_TRACKERS_PREFIX) && value == false }
            .map { (key, _) -> key.removePrefix(KEY_BLOCK_TRACKERS_PREFIX) }
            .toSet()
    }

    /** Полностью заменяет список исключённых приложений (импорт резервной копии). */
    fun replaceDisabledPackages(packages: Set<String>) {
        val p = prefs ?: return
        val editor = p.edit()
        for (key in p.all.keys) {
            if (key.startsWith(KEY_BLOCK_TRACKERS_PREFIX)) editor.remove(key)
        }
        for (pkg in packages) {
            if (pkg.isNotBlank()) editor.putBoolean(KEY_BLOCK_TRACKERS_PREFIX + pkg, false)
        }
        editor.apply()
    }
}
