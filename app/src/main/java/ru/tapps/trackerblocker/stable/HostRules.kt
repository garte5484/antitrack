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

    fun isBlocked(host: String): Boolean {
        val h = normalize(host)
        if (h in allowed) return false
        if (h in blocked) return true
        return false
    }

    fun isAllowed(host: String): Boolean {
        val h = normalize(host)
        return h in allowed
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
}
