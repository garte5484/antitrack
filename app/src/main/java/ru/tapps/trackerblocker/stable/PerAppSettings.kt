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
}
