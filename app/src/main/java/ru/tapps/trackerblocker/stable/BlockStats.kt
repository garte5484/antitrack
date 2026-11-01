package ru.tapps.trackerblocker.stable

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

/**
 * Статистика заблокированных запросов по приложениям.
 *
 * Хранит агрегат: приложение → ПОЛНЫЙ домен → счётчики отдельно для трекеров
 * и рекламы ([HostCounts]). Полный домен, а не корневой лейбл: по лейблу не
 * отличить mi.com от miui.com, а my.com (myTarget) от любого другого "my".
 * Название бренда в списке получается на лету через [BrandNames.displayName],
 * поэтому длинные хосты пользователю не показываются.
 *
 * Данные персистентные (SharedPreferences, JSON) и переживают перезапуски.
 * Состояние выставляется как Compose State, чтобы UI обновлялся сразу после
 * каждой блокировки. Формат старой версии (домен → одно число, тракер) при
 * загрузке распознаётся и считается записями о трекерах.
 */
object BlockStats {

    const val SYSTEM = "system"
    const val OTHER = "other"

    /** Причиной какой блокировки стал запрос — трекер или реклама. */
    enum class Kind { TRACKER, ADS }

    /** Счётчики по одному домену: отдельно трекеры и реклама. */
    data class HostCounts(val trackers: Long = 0, val ads: Long = 0) {
        val total: Long get() = trackers + ads
    }

    private const val PREF_NAME = "block_stats"
    private const val KEY_JSON = "data"

    /**
     * Запись статистики отложена: при активном фоне счётчики меняются на
     * каждый DNS-запрос, а сериализация всего JSON на каждую блокировку
     * заметно нагружает поток VPN. Копим изменения и пишем раз в секунду.
     */
    private const val PERSIST_DELAY_MS = 1_000L

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private val persistThread = HandlerThread("BlockStats-persist").apply { start() }
    private val persistHandler = Handler(persistThread.looper)
    private val persistRunnable = Runnable {
        synchronized(lock) { persist() }
    }

    // приложение -> домен -> счётчики
    private val data = LinkedHashMap<String, MutableMap<String, HostCounts>>()

    val state = mutableStateOf<Map<String, Map<String, HostCounts>>>(emptyMap())

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            load()
        }
    }

    fun record(packageName: String, host: String, kind: Kind) {
        val domain = host.lowercase().trim().trimEnd('.')
        if (domain.isEmpty()) return
        synchronized(lock) {
            val byHost = data.getOrPut(packageName) { LinkedHashMap() }
            val prev = byHost[domain] ?: HostCounts()
            byHost[domain] = when (kind) {
                Kind.TRACKER -> HostCounts(prev.trackers + 1, prev.ads)
                Kind.ADS -> HostCounts(prev.trackers, prev.ads + 1)
            }
            publish()
        }
        schedulePersist()
    }

    fun clear() {
        persistHandler.removeCallbacks(persistRunnable)
        synchronized(lock) {
            data.clear()
            prefs?.edit()?.remove(KEY_JSON)?.apply()
            publish()
        }
    }

    /** Немедленно записывает накопленные счётчики (например, при остановке VPN). */
    fun flush() {
        persistHandler.removeCallbacks(persistRunnable)
        synchronized(lock) { persist() }
    }

    private fun schedulePersist() {
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, PERSIST_DELAY_MS)
    }

    fun displayName(context: Context, packageName: String): String {
        if (packageName == SYSTEM) return "Система"
        if (packageName == OTHER) return "Другое"
        val pm = context.applicationContext.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { packageName }
    }

    private fun load() {
        val shared = prefs ?: return
        val raw = shared.getString(KEY_JSON, null) ?: return
        try {
            val root = JSONObject(raw)
            root.keys().forEach { pkg ->
                val hostsObject = JSONObject(root.getString(pkg))
                val map = LinkedHashMap<String, HostCounts>()
                hostsObject.keys().forEach { host ->
                    val value = hostsObject.get(host)
                    map[host] = if (value is JSONObject) {
                        HostCounts(
                            trackers = value.optLong("t", 0),
                            ads = value.optLong("a", 0)
                        )
                    } else {
                        // старая запись «домен → одно число» — считаем трекерами
                        HostCounts(trackers = value.toString().toLongOrNull() ?: 0L)
                    }
                }
                data[pkg] = map
            }
            publish()
        } catch (e: Exception) {
            shared.edit().remove(KEY_JSON).apply()
        }
    }

    private fun persist() {
        val shared = prefs ?: return
        try {
            val root = JSONObject()
            data.forEach { (pkg, hosts) ->
                val hostsObject = JSONObject()
                hosts.forEach { (host, counts) ->
                    val value = JSONObject()
                    value.put("t", counts.trackers)
                    value.put("a", counts.ads)
                    hostsObject.put(host, value)
                }
                root.put(pkg, hostsObject)
            }
            shared.edit().putString(KEY_JSON, root.toString()).apply()
        } catch (_: Exception) {
            // статистика критична — ошибку записи просто пропускаем
        }
    }

    private fun publish() {
        val snapshot = LinkedHashMap<String, Map<String, HostCounts>>()
        data.forEach { (pkg, hosts) -> snapshot[pkg] = LinkedHashMap(hosts) }
        state.value = snapshot
    }
}