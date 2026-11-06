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
    private const val KEY_PAUSE_UNTIL = "pause_until"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_AUTOSTART_AT = "autostart_at"
    private const val KEY_AUTOSTART_RESULT = "autostart_result"
    private const val KEY_DNS_PROVIDER = "dns_provider"
    private const val KEY_DNS_CUSTOM = "dns_custom_servers"
    private const val KEY_DNS_ENCRYPT = "dns_encrypt"
    private const val KEY_BLOCK_RESPONSE_NULL = "block_response_null"
    private const val KEY_BLOCK_MODE = "block_mode"
    private const val KEY_TUNNEL_STRICT_IP = "tunnel_strict_ip"

    /**
     * Режим блокировки:
     *  - [MODE_DNS] — в туннель уходит только DNS (лёгкий, TCP напрямую);
     *  - [MODE_TUNNEL] — весь трафик идёт через userspace-стек, поэтому
     *    приложение-владелец соединения определяется точно.
     */
    const val MODE_DNS = "dns"
    const val MODE_TUNNEL = "tunnel"

    // Результаты последней попытки автозапуска. Нужны, чтобы в настройках
    // было видно, дошёл ли до приложения BOOT_COMPLETED и удалось ли поднять
    // сервис: на агрессивных прошивках (ColorOS, MIUI) это главный вопрос.
    const val AUTOSTART_REQUESTED = "requested"
    const val AUTOSTART_STARTED = "started"
    const val AUTOSTART_NO_CONSENT = "no_consent"
    const val AUTOSTART_ERROR = "error"
    const val AUTOSTART_DISABLED = "disabled"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun blockTrackers(): Boolean = prefs?.getBoolean(KEY_BLOCK_TRACKERS, true) ?: true

    fun blockAds(): Boolean = prefs?.getBoolean(KEY_BLOCK_ADS, true) ?: true

    /** Выбранный режим блокировки; по умолчанию — лёгкий DNS-режим. */
    fun blockMode(): String =
        prefs?.getString(KEY_BLOCK_MODE, MODE_DNS) ?: MODE_DNS

    fun setBlockMode(mode: String) {
        prefs?.edit()?.putString(KEY_BLOCK_MODE, mode)?.apply()
    }

    /**
     * В режиме полного туннеля блокировать IP, к которому привязано несколько
     * доменов, если хотя бы один из них трекер. Выключено по умолчанию:
     * общие IP (CDN, Google, Meta) иначе дают ложные блокировки.
     */
    fun tunnelStrictIp(): Boolean =
        prefs?.getBoolean(KEY_TUNNEL_STRICT_IP, false) ?: false

    fun setTunnelStrictIp(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_TUNNEL_STRICT_IP, enabled)?.apply()
    }

    fun setBlockTrackers(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_BLOCK_TRACKERS, enabled)?.apply()
    }

    fun setBlockAds(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_BLOCK_ADS, enabled)?.apply()
    }

    /** До какого момента блокировка приостановлена (0 — пауза не включена). */
    fun pauseUntil(): Long = prefs?.getLong(KEY_PAUSE_UNTIL, 0L) ?: 0L

    /** Приостановлена ли блокировка прямо сейчас. */
    fun isPaused(): Boolean = pauseUntil() > System.currentTimeMillis()

    fun pauseFor(durationMs: Long) {
        prefs?.edit()?.putLong(KEY_PAUSE_UNTIL, System.currentTimeMillis() + durationMs)?.apply()
    }

    fun clearPause() {
        prefs?.edit()?.putLong(KEY_PAUSE_UNTIL, 0L)?.apply()
    }

    /**
     * Пользователь включал защиту и не выключал её вручную. По этому флагу
     * сервис поднимается автоматически после перезагрузки устройства.
     */
    fun isProtectionEnabled(): Boolean =
        prefs?.getBoolean(KEY_PROTECTION_ENABLED, false) ?: false

    fun setProtectionEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PROTECTION_ENABLED, enabled)?.apply()
    }

    /** Когда последний раз приходил broadcast автозапуска (0 — ещё не приходил). */
    fun lastAutostartAt(): Long = prefs?.getLong(KEY_AUTOSTART_AT, 0L) ?: 0L

    /** Чем закончилась последняя попытка автозапуска, см. AUTOSTART_*. */
    fun lastAutostartResult(): String? = prefs?.getString(KEY_AUTOSTART_RESULT, null)

    fun setAutostartResult(result: String) {
        prefs?.edit()
            ?.putLong(KEY_AUTOSTART_AT, System.currentTimeMillis())
            ?.putString(KEY_AUTOSTART_RESULT, result)
            ?.apply()
    }

    // ————————————————————— DNS —————————————————————

    /** Выбранный DNS-провайдер; по умолчанию — системный DNS текущей сети. */
    fun dnsProviderId(): String =
        prefs?.getString(KEY_DNS_PROVIDER, DnsProviders.SYSTEM_ID) ?: DnsProviders.SYSTEM_ID

    fun setDnsProviderId(id: String) {
        prefs?.edit()?.putString(KEY_DNS_PROVIDER, id)?.apply()
    }

    /** Сырой ввод пользовательских DNS-серверов (как ввёл пользователь). */
    fun customDnsRaw(): String = prefs?.getString(KEY_DNS_CUSTOM, "") ?: ""

    fun setCustomDnsRaw(value: String) {
        prefs?.edit()?.putString(KEY_DNS_CUSTOM, value)?.apply()
    }

    /** Пользовательские DNS-серверы: список IP-адресов без пробелов и пустых строк. */
    fun customDnsServers(): List<String> =
        customDnsRaw()
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** DNS over TLS включён (если провайдер его поддерживает). */
    fun dnsEncryptionEnabled(): Boolean =
        prefs?.getBoolean(KEY_DNS_ENCRYPT, true) ?: true

    fun setDnsEncryptionEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_DNS_ENCRYPT, enabled)?.apply()
    }

    /** true — отвечать на блокировку 0.0.0.0/::, false — NXDOMAIN. */
    fun blockWithNullAddress(): Boolean =
        prefs?.getBoolean(KEY_BLOCK_RESPONSE_NULL, false) ?: false

    fun setBlockWithNullAddress(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_BLOCK_RESPONSE_NULL, enabled)?.apply()
    }
}
