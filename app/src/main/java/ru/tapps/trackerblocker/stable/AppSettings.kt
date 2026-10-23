package ru.tapps.trackerblocker.stable

import android.content.Context
import android.content.SharedPreferences

/**
 * Пользовательские настройки блокировки.
 *
 * Оба переключателя по умолчанию включены: отдельного «выключенного» состояния
 * нет, поскольку изначально приложение блокировало всё без настроек.
 * Значения читаются через пред-копию SharedPreferences (быстрый доступ в память),
 * поэтому VPN-сервис может проверять их на каждом DNS-запросе.
 */
object AppSettings {

    private const val PREF_NAME = "antitrack_settings"
    const val KEY_BLOCK_TRACKERS = "block_trackers"
    const val KEY_BLOCK_ADS = "block_ads"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun blockTrackers(): Boolean = prefs?.getBoolean(KEY_BLOCK_TRACKERS, true) ?: true

    fun blockAds(): Boolean = prefs?.getBoolean(KEY_BLOCK_ADS, true) ?: true

    fun setBlockTrackers(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_BLOCK_TRACKERS, enabled)?.apply()
    }

    fun setBlockAds(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_BLOCK_ADS, enabled)?.apply()
    }
}