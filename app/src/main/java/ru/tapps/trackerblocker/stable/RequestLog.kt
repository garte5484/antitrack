package ru.tapps.trackerblocker.stable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * Живой лог DNS-запросов.
 *
 * Заполняется из [BlockVpnService] только при включённом логе. Хранит последние
 * [MAX_ENTRIES] записей в памяти; состояние отдаётся в Compose, поэтому список
 * обновляется сразу после каждого запроса.
 */
object RequestLog {

    const val MAX_ENTRIES = 1000

    enum class Status { ALLOWED, TRACKER, ADS, THROTTLE, CUSTOM_BLOCKED }

    /**
     * Почему запись попала в «Другое» или почему приложение определено
     * приблизительно. Показывается в деталях лога, чтобы не гадать.
     */
    enum class Reason { UID_UNKNOWN, SYSTEM_RESOLVER, DB_MISATTRIBUTION }

    data class Entry(
        val id: Long,
        val timeMs: Long,
        val packageName: String,
        val host: String,
        val status: Status,
        val srcIp: String? = null,
        val dstIp: String? = null,
        val matchPattern: String? = null,
        val reason: Reason? = null
    )

    private const val PREF_NAME = "request_log"
    private const val KEY_ENABLED = "enabled"

    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private var nextId = 0L

    @Volatile
    var isEnabled: Boolean = false
        private set

    /** Новые записи в начале списка. */
    val state = mutableStateOf<List<Entry>>(emptyList())

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            isEnabled = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            isEnabled = enabled
            prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        }
    }

    fun record(
        packageName: String,
        host: String,
        status: Status,
        srcIp: String? = null,
        dstIp: String? = null,
        matchPattern: String? = null,
        reason: Reason? = null
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            if (!isEnabled) return
            val entry = Entry(
                id = ++nextId,
                timeMs = System.currentTimeMillis(),
                packageName = packageName,
                host = host,
                status = status,
                srcIp = srcIp,
                dstIp = dstIp,
                matchPattern = matchPattern,
                reason = reason
            )
            state.value = (listOf(entry) + state.value).take(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) {
            state.value = emptyList()
        }
    }
}
