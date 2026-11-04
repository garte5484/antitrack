package ru.tapps.trackerblocker.stable

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object HostRules {

    private const val PREF_NAME = "host_rules"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_ALLOWED = "allowed"

    private var prefs: SharedPreferences? = null
    private val lock = Any()

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var allowed: Set<String> = emptySet()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            blocked = loadSet(KEY_BLOCKED)
            allowed = loadSet(KEY_ALLOWED)
        }
    }

    private fun loadSet(key: String): Set<String> {
        val raw = prefs?.getString(key, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    add(normalize(arr.optString(i)))
                }
            }.filter { it.isNotEmpty() }.toSet()
        }.getOrElse { emptySet() }
    }

    private fun saveSet(key: String, set: Set<String>) {
        val arr = JSONArray()
        set.sorted().forEach { arr.put(it) }
        prefs?.edit()?.putString(key, arr.toString())?.apply()
    }

    private fun normalize(host: String): String = host.lowercase().trim().trimEnd('.')

    fun isBlocked(host: String): Boolean = decide(host, blocked, allowed)

    fun isAllowed(host: String): Boolean = matchesRule(host, allowed)

    /**
     * Проверяет, есть ли в наборе правило для хоста или любого его родительского
     * домена: правило example.com подходит и для ads.example.com.
     */
    internal fun matchesRule(host: String, rules: Set<String>): Boolean {
        var suffix = normalize(host)
        if (suffix.isEmpty()) return false
        while (true) {
            if (suffix in rules) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) return false
            suffix = suffix.substring(dot + 1)
        }
    }

    /**
     * Итоговое решение по ручным правилам: идём от самого точного суффикса к
     * самому общему, поэтому правило для ads.example.com важнее правила для
     * example.com. Блокировка домена действует на все его поддомены, но не на
     * соседние домены.
     */
    internal fun decide(
        host: String,
        blockedRules: Set<String>,
        allowedRules: Set<String>
    ): Boolean {
        var suffix = normalize(host)
        if (suffix.isEmpty()) return false
        while (true) {
            if (suffix in allowedRules) return false
            if (suffix in blockedRules) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) return false
            suffix = suffix.substring(dot + 1)
        }
    }

    fun forceBlock(host: String) {
        val h = normalize(host)
        if (h.isEmpty()) return
        synchronized(lock) {
            blocked = blocked + h
            allowed = allowed - h
            saveSet(KEY_BLOCKED, blocked)
            saveSet(KEY_ALLOWED, allowed)
        }
    }

    fun forceAllow(host: String) {
        val h = normalize(host)
        if (h.isEmpty()) return
        synchronized(lock) {
            allowed = allowed + h
            blocked = blocked - h
            saveSet(KEY_ALLOWED, allowed)
            saveSet(KEY_BLOCKED, blocked)
        }
    }

    fun blockedHosts(): Set<String> = blocked

    fun allowedHosts(): Set<String> = allowed

    /**
     * Полностью заменяет правила (импорт резервной копии). Значения
     * нормализуются, пустые строки отбрасываются.
     */
    fun replaceAll(blockedHosts: Set<String>, allowedHosts: Set<String>) {
        val b = blockedHosts.mapNotNull { normalize(it).takeIf(String::isNotEmpty) }.toSet()
        val a = allowedHosts.mapNotNull { normalize(it).takeIf(String::isNotEmpty) }.toSet()
        synchronized(lock) {
            blocked = b
            allowed = a
            saveSet(KEY_BLOCKED, blocked)
            saveSet(KEY_ALLOWED, allowed)
        }
    }

    /** Убирает правило ручной блокировки. */
    fun removeBlocked(host: String) {
        val h = normalize(host)
        if (h.isEmpty()) return
        synchronized(lock) {
            blocked = blocked - h
            saveSet(KEY_BLOCKED, blocked)
        }
    }

    /** Убирает правило ручного разрешения — хост снова фильтруется списками. */
    fun removeAllowed(host: String) {
        val h = normalize(host)
        if (h.isEmpty()) return
        synchronized(lock) {
            allowed = allowed - h
            saveSet(KEY_ALLOWED, allowed)
        }
    }
}
