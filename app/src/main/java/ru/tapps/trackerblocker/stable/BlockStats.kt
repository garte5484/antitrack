package ru.tapps.trackerblocker.stable

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Статистика заблокированных запросов по приложениям.
 *
 * Хранит два среза:
 *  - агрегат за всё время: приложение → ПОЛНЫЙ домен → счётчики отдельно для
 *    трекеров и рекламы ([HostCounts]);
 *  - разбивку по дням (до [DAILY_RETENTION_DAYS] дней), из которой считается
 *    статистика за сегодня и за последние 7 дней.
 *
 * Полный домен, а не корневой лейбл: по лейблу не отличить mi.com от miui.com,
 * а my.com (myTarget) от любого другого "my". Название бренда в списке
 * получается на лету через [BrandNames.displayName], поэтому длинные хосты
 * пользователю не показываются.
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

    /** Период, за который показывается статистика. */
    enum class Period { TODAY, WEEK, ALL }

    /** Счётчики по одному домену: отдельно трекеры и реклама. */
    data class HostCounts(val trackers: Long = 0, val ads: Long = 0) {
        val total: Long get() = trackers + ads
    }

    private const val PREF_NAME = "block_stats"
    private const val KEY_JSON = "data"
    private const val KEY_DAILY = "daily"

    /** Сколько дней хранить разбивку по дням (нужны как минимум 7). */
    private const val DAILY_RETENTION_DAYS = 31

    /**
     * Запись статистики отложена: при активном фоне счётчики меняются на
     * каждый DNS-запрос, а сериализация всего JSON на каждую блокировку
     * заметно нагружает поток VPN. Копим изменения и пишем раз в секунду.
     */
    private const val PERSIST_DELAY_MS = 1_000L

    /**
     * Дневная разбивка заметно больше агрегата за всё время (до 31 корзины),
     * поэтому её сериализация выполняется реже; при остановке VPN
     * [flush] принудительно сохраняет и её.
     */
    private const val DAILY_PERSIST_INTERVAL_MS = 15_000L

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private val persistThread = HandlerThread("BlockStats-persist").apply { start() }
    private val persistHandler = Handler(persistThread.looper)
    private val persistRunnable = Runnable {
        synchronized(lock) { persist() }
    }

    // приложение -> домен -> счётчики (за всё время)
    private val data = LinkedHashMap<String, MutableMap<String, HostCounts>>()

    // дата (yyyy-MM-dd) -> приложение -> домен -> счётчики
    private val daily = LinkedHashMap<String, MutableMap<String, MutableMap<String, HostCounts>>>()

    /** Ключи дат в формате yyyy-MM-dd сравниваются лексикографически. */
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    /** Когда дневная разбивка последний раз записана на диск, мс. */
    private var dailyPersistedAt = 0L

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
            addCount(data.getOrPut(packageName) { LinkedHashMap() }, domain, kind)

            val today = dayFormat.format(Date())
            val byApp = daily.getOrPut(today) { LinkedHashMap() }
            addCount(byApp.getOrPut(packageName) { LinkedHashMap() }, domain, kind)

            pruneDailyLocked()
            publish()
        }
        schedulePersist()
    }

    /**
     * Статистика за период: приложение → домен → счётчики.
     * Для [Period.ALL] отдаётся готовый агрегат, для остальных — сумма дневных корзин.
     * Возвращается неизменяемый снимок, его можно читать без блокировки.
     */
    fun statsFor(period: Period): Map<String, Map<String, HostCounts>> {
        synchronized(lock) {
            if (period == Period.ALL) return state.value
            val keys = periodKeysLocked(period)
            val result = LinkedHashMap<String, MutableMap<String, HostCounts>>()
            for ((date, apps) in daily) {
                if (date !in keys) continue
                for ((pkg, hosts) in apps) {
                    val target = result.getOrPut(pkg) { LinkedHashMap() }
                    for ((host, counts) in hosts) {
                        val prev = target[host]
                        target[host] = if (prev == null) {
                            counts
                        } else {
                            HostCounts(
                                prev.trackers + counts.trackers,
                                prev.ads + counts.ads
                            )
                        }
                    }
                }
            }
            val snapshot = LinkedHashMap<String, Map<String, HostCounts>>()
            result.forEach { (pkg, hosts) -> snapshot[pkg] = LinkedHashMap(hosts) }
            return snapshot
        }
    }

    fun clear() {
        persistHandler.removeCallbacks(persistRunnable)
        synchronized(lock) {
            data.clear()
            daily.clear()
            dailyPersistedAt = 0L
            prefs?.edit()?.remove(KEY_JSON)?.remove(KEY_DAILY)?.apply()
            publish()
        }
    }

    /** Немедленно записывает накопленные счётчики (например, при остановке VPN). */
    fun flush() {
        persistHandler.removeCallbacks(persistRunnable)
        synchronized(lock) { persist(forceDaily = true) }
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

    private fun addCount(
        hosts: MutableMap<String, HostCounts>,
        domain: String,
        kind: Kind
    ) {
        val prev = hosts[domain] ?: HostCounts()
        hosts[domain] = when (kind) {
            Kind.TRACKER -> HostCounts(prev.trackers + 1, prev.ads)
            Kind.ADS -> HostCounts(prev.trackers, prev.ads + 1)
        }
    }

    /** Ключи дат, попадающих в период. Вызывается под [lock]. */
    private fun periodKeysLocked(period: Period): Set<String> {
        val days = when (period) {
            Period.TODAY -> 1
            Period.WEEK -> 7
            Period.ALL -> return emptySet()
        }
        val keys = HashSet<String>(days)
        val calendar = Calendar.getInstance()
        repeat(days) {
            keys.add(dayFormat.format(calendar.time))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return keys
    }

    /** Удаляет дневные корзины старше срока хранения. Вызывается под [lock]. */
    private fun pruneDailyLocked() {
        if (daily.isEmpty()) return
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -DAILY_RETENTION_DAYS)
        val cutoff = dayFormat.format(calendar.time)
        daily.keys.removeIf { it < cutoff }
    }

    private fun load() {
        val shared = prefs ?: return
        val raw = shared.getString(KEY_JSON, null)
        if (raw != null) {
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
            } catch (e: Exception) {
                data.clear()
                shared.edit().remove(KEY_JSON).apply()
            }
        }

        val rawDaily = shared.getString(KEY_DAILY, null)
        if (rawDaily != null) {
            try {
                val root = JSONObject(rawDaily)
                root.keys().forEach { date ->
                    val appsObject = JSONObject(root.getString(date))
                    val apps = LinkedHashMap<String, MutableMap<String, HostCounts>>()
                    appsObject.keys().forEach { pkg ->
                        val hostsObject = JSONObject(appsObject.getString(pkg))
                        val hosts = LinkedHashMap<String, HostCounts>()
                        hostsObject.keys().forEach { host ->
                            val value = hostsObject.get(host)
                            if (value is JSONObject) {
                                hosts[host] = HostCounts(
                                    trackers = value.optLong("t", 0),
                                    ads = value.optLong("a", 0)
                                )
                            }
                        }
                        apps[pkg] = hosts
                    }
                    daily[date] = apps
                }
                pruneDailyLocked()
            } catch (e: Exception) {
                daily.clear()
                shared.edit().remove(KEY_DAILY).apply()
            }
        }

        publish()
    }

    private fun persist(forceDaily: Boolean = false) {
        val shared = prefs ?: return
        try {
            val root = JSONObject()
            data.forEach { (pkg, hosts) ->
                val hostsObject = JSONObject()
                hosts.forEach { (host, counts) ->
                    hostsObject.put(host, countsToJson(counts))
                }
                root.put(pkg, hostsObject)
            }

            val editor = shared.edit().putString(KEY_JSON, root.toString())
            val now = System.currentTimeMillis()
            if (forceDaily || now - dailyPersistedAt >= DAILY_PERSIST_INTERVAL_MS) {
                editor.putString(KEY_DAILY, dailyToJson().toString())
                dailyPersistedAt = now
            }
            editor.apply()
        } catch (_: Exception) {
            // статистика не критична — ошибку записи просто пропускаем
        }
    }

    private fun dailyToJson(): JSONObject {
        val dailyRoot = JSONObject()
        daily.forEach { (date, apps) ->
            val appsObject = JSONObject()
            apps.forEach { (pkg, hosts) ->
                val hostsObject = JSONObject()
                hosts.forEach { (host, counts) ->
                    hostsObject.put(host, countsToJson(counts))
                }
                appsObject.put(pkg, hostsObject)
            }
            dailyRoot.put(date, appsObject)
        }
        return dailyRoot
    }

    private fun countsToJson(counts: HostCounts): JSONObject = JSONObject().apply {
        put("t", counts.trackers)
        put("a", counts.ads)
    }

    private fun publish() {
        val snapshot = LinkedHashMap<String, Map<String, HostCounts>>()
        data.forEach { (pkg, hosts) -> snapshot[pkg] = LinkedHashMap(hosts) }
        state.value = snapshot
    }
}
