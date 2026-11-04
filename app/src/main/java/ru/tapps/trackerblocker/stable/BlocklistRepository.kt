package ru.tapps.trackerblocker.stable

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Загрузка списков блокировки по URL с локальным кешем.
 *
 * Два независимых источника: список трекеров ([trackersSource]) и список
 * рекламных сетей ([adsSource]). Логика каждого источника одинакова:
 *  - при запуске, если есть интернет и кеш старше 6 часов — список перекачивается;
 *  - если интернета нет или загрузка не удалась — используется старый кеш, если он есть;
 *  - свежий кеш (моложе 6 часов) в память просто поднимается из файла.
 *
 * Парсер запасён под несколько форматов: hosts-стиль («0.0.0.0 domain»),
 * «просто домен в строке», а также AdBlock-синтаксис («||domain^»).
 */
object BlocklistRepository {

    private const val TAG = "BlocklistRepository"
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_VERSION = "version"

    /** Настройки автообновления списков (общие и по каждому списку). */
    private const val PREF_SETTINGS = "blocklist_settings"
    private const val KEY_AUTO_UPDATE_ALL = "auto_update_all"
    private const val KEY_AUTO_UPDATE_PREFIX = "auto_update_"

    /** Какой именно список: у каждого свой URL, кеш и настройки. */
    enum class Kind { TRACKERS, ADS }

    /** Состояние списка для экрана управления. */
    data class ListState(
        val kind: Kind,
        val count: Int,
        val bytes: Long,
        val lastUpdated: Long,
        val autoUpdate: Boolean,
        val url: String
    )

    /** Результат принудительного обновления блоклистов. */
    enum class RefreshResult { SUCCESS, NO_INTERNET, FAILED }

    private val ignoreList = setOf(
        "localhost", "localhost.localdomain", "broadcasthost", "local",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters"
    )

    private class Source(
        private val sourceName: String,
        val url: String,
        private val prefName: String,
        private val cacheVersion: Int,
        private val cacheFileName: String,
        private val onLoaded: (Set<String>) -> Unit
    ) {
        private val lock = Any()

        @Volatile
        var current: Set<String> = emptySet()
            private set

        val size: Int
            get() = current.size

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE)

        private fun cacheFile(context: Context): File = File(context.filesDir, cacheFileName)

        fun refreshIfStale(context: Context, allowDownload: Boolean = true) {
            val app = context.applicationContext
            synchronized(lock) {
                // Кеш поднимаем всегда: даже с выключенным автообновлением
                // фильтрация должна работать на последней скачанной версии.
                ensureLoadedLocked(app)
                if (!allowDownload) return

                val cachedTime = prefs(app).getLong(KEY_TIMESTAMP, 0L)
                val cachedVersion = prefs(app).getInt(KEY_VERSION, 0)
                val now = System.currentTimeMillis()

                if (cachedVersion == cacheVersion && cachedTime != 0L && now - cachedTime < CACHE_TTL_MS) {
                    return // кеш свежий и уже загружен
                }

                if (!BlocklistRepository.hasInternet(app)) return

                val fresh = try {
                    download()
                } catch (e: Exception) {
                    Log.w(TAG, "$sourceName: не удалось обновить список по URL", e)
                    null
                }

                if (fresh != null && fresh.isNotEmpty()) {
                    writeCache(app, fresh)
                    apply(fresh)
                } else {
                    ensureLoadedLocked(app) // загрузка сорвалась — старый кеш
                }
            }
        }

        /**
         * Скачивает список в обход кеша. Возвращает true, если список получен
         * и применён; иначе поднимает старый кеш и возвращает false.
         */
        fun forceRefresh(app: Context): Boolean {
            synchronized(lock) {
                val fresh = try {
                    download()
                } catch (e: Exception) {
                    Log.w(TAG, "$sourceName: не удалось принудительно обновить список по URL", e)
                    null
                }

                if (fresh != null && fresh.isNotEmpty()) {
                    writeCache(app, fresh)
                    apply(fresh)
                    return true
                }
                ensureLoadedLocked(app) // загрузка сорвалась — старый кеш
                return false
            }
        }

        /** Поднимает сохранённый кеш в память, если список ещё не загружен. */
        fun loadFromCache(app: Context) {
            synchronized(lock) { ensureLoadedLocked(app) }
        }

        /** Время последнего успешного скачивания списка, мс (0 — ещё не качали). */
        fun lastUpdated(app: Context): Long = prefs(app).getLong(KEY_TIMESTAMP, 0L)

        /** Размер файла кеша в байтах (0 — кеша ещё нет). */
        fun cacheBytes(app: Context): Long = cacheFile(app).length()

        /** Снимок загруженных записей; при необходимости сначала читает кеш. */
        fun snapshot(app: Context): List<String> {
            synchronized(lock) {
                ensureLoadedLocked(app)
                return current.sorted()
            }
        }

        private fun ensureLoadedLocked(app: Context) {
            if (current.isNotEmpty()) return
            val file = cacheFile(app)
            if (!file.exists()) return
            val loaded = try {
                parse(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "$sourceName: не удалось прочитать кеш", e)
                emptySet()
            }
            if (loaded.isNotEmpty()) apply(loaded)
        }

        private fun apply(domains: Set<String>) {
            current = domains
            onLoaded(domains)
        }

        private fun writeCache(context: Context, domains: Set<String>) {
            try {
                cacheFile(context).writeText(domains.sorted().joinToString("\n"))
                prefs(context).edit()
                    .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                    .putInt(KEY_VERSION, cacheVersion)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "$sourceName: не удалось записать кеш", e)
            }
        }

        private fun download(): Set<String> {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AntiTrack/1.0")
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val buffer = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (buffer.size() + n > MAX_BYTES) throw IOException("Список слишком большой")
                        buffer.write(chunk, 0, n)
                    }
                }
                return parse(buffer.toString(Charsets.UTF_8.name()))
            } finally {
                connection.disconnect()
            }
        }
    }

    private val trackersSource = Source(
        sourceName = "трекеры",
        url = "https://raw.githubusercontent.com/garte5484/alltrackerlist/refs/heads/main/simple/lite",
        prefName = "blocklist_cache",
        cacheVersion = 3,
        cacheFileName = "antitrack_blocklist.txt"
    ) { TrackerBlocklist.updateCustom(it) }

    private val adsSource = Source(
        sourceName = "реклама",
        url = "https://raw.githubusercontent.com/garte5484/alladlist/refs/heads/main/simple/lite",
        prefName = "blocklist_cache_ads",
        cacheVersion = 2,
        cacheFileName = "antitrack_adblock.txt"
    ) { AdBlocklist.updateCustom(it) }

    private fun source(kind: Kind): Source = when (kind) {
        Kind.TRACKERS -> trackersSource
        Kind.ADS -> adsSource
    }

    private fun settingsPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)

    /** Глобальный переключатель автообновления всех списков. */
    fun isAutoUpdateEnabled(context: Context): Boolean =
        settingsPrefs(context).getBoolean(KEY_AUTO_UPDATE_ALL, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        settingsPrefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ALL, enabled).apply()
    }

    /** Автообновление отдельного списка (действует только при включённом общем). */
    fun isListAutoUpdateEnabled(context: Context, kind: Kind): Boolean =
        settingsPrefs(context).getBoolean(KEY_AUTO_UPDATE_PREFIX + kind.name, true)

    fun setListAutoUpdateEnabled(context: Context, kind: Kind, enabled: Boolean) {
        settingsPrefs(context)
            .edit()
            .putBoolean(KEY_AUTO_UPDATE_PREFIX + kind.name, enabled)
            .apply()
    }

    private fun shouldAutoUpdate(context: Context, kind: Kind): Boolean =
        isAutoUpdateEnabled(context) && isListAutoUpdateEnabled(context, kind)

    /** Число доменов/ключевых слов в списке трекеров. */
    val trackerCount: Int
        get() = trackersSource.size

    /** Число доменов/ключевых слов в списке рекламы. */
    val adCount: Int
        get() = adsSource.size

    fun count(kind: Kind): Int = source(kind).size

    /**
     * Обновляет оба списка при необходимости. Вызывается при входе в приложение
     * и при старте VPN-сервиса (не на главном потоке).
     */
    fun refreshIfStale(context: Context) {
        val app = context.applicationContext
        trackersSource.refreshIfStale(app, shouldAutoUpdate(app, Kind.TRACKERS))
        adsSource.refreshIfStale(app, shouldAutoUpdate(app, Kind.ADS))
    }

    /**
     * Принудительно обновляет оба списка и дожидается результата.
     * Блокирующий вызов — запускайте не на главном потоке.
     */
    fun forceRefresh(context: Context): RefreshResult {
        val app = context.applicationContext
        if (!hasInternet(app)) {
            // Без сети показываем уже сохранённые списки.
            trackersSource.loadFromCache(app)
            adsSource.loadFromCache(app)
            return RefreshResult.NO_INTERNET
        }

        val trackersOk = trackersSource.forceRefresh(app)
        val adsOk = adsSource.forceRefresh(app)
        return if (trackersOk && adsOk) RefreshResult.SUCCESS else RefreshResult.FAILED
    }

    /** Принудительно обновляет один список. Блокирующий вызов. */
    fun forceRefresh(context: Context, kind: Kind): RefreshResult {
        val app = context.applicationContext
        val src = source(kind)
        if (!hasInternet(app)) {
            src.loadFromCache(app)
            return RefreshResult.NO_INTERNET
        }
        return if (src.forceRefresh(app)) RefreshResult.SUCCESS else RefreshResult.FAILED
    }

    /** Время последнего успешного обновления любого из списков, мс. */
    fun lastUpdated(context: Context): Long {
        val app = context.applicationContext
        return maxOf(trackersSource.lastUpdated(app), adsSource.lastUpdated(app))
    }

    fun lastUpdated(context: Context, kind: Kind): Long {
        val app = context.applicationContext
        return source(kind).lastUpdated(app)
    }

    /** Состояние одного списка для экрана управления. */
    fun state(context: Context, kind: Kind): ListState {
        val app = context.applicationContext
        val src = source(kind)
        return ListState(
            kind = kind,
            count = src.size,
            bytes = src.cacheBytes(app),
            lastUpdated = src.lastUpdated(app),
            autoUpdate = isListAutoUpdateEnabled(app, kind),
            url = src.url
        )
    }

    fun states(context: Context): List<ListState> =
        Kind.entries.map { state(context, it) }

    /** Содержимое списка (отсортированное). Блокирующий вызов — не на главном потоке. */
    fun entries(context: Context, kind: Kind): List<String> =
        source(kind).snapshot(context.applicationContext)

    private fun hasInternet(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /** Разбирает текст списка: домены и ключевые слова (без дублей, в нижнем регистре). */
    internal fun parse(text: String): Set<String> {
        val result = HashSet<String>()
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            line = line.substringBefore('#').trim() // комментарии
            if (line.isEmpty()) continue
            line = line.removePrefix("||").removeSuffix("^").trim() // AdBlock-синтаксис
            val tokens = line.split(Regex("\\s+"))
            for (token in tokens) {
                val domain = normalize(token) ?: continue
                result.add(domain)
            }
        }
        return result
    }

    /**
     * Нормализует токен из списка.
     *
     * Домен — имя, содержащее точку («mail.ru»). Ключевое слово — токен без
     * точки («track», «metric»): им блокируется любой хост, содержащий его
     * в имени. Разные мусорные записи (URL-пути со слэшем `/watdog/`,
     * подстановки `*`, IPv4, служебные имена, знаки препинания) пропускаем.
     */
    internal fun normalize(token: String): String? {
        var value = token.trim()
        if (value.isEmpty()) return null
        value = value.removeSuffix(".")
        if (value.contains('/')) return null // это URL-путь, а не имя хоста
        if ('*' in value) return null
        if (value in ignoreList) return null
        if (isIpv4(value)) return null

        if (!value.contains('.')) {
            // ключевое слово без зоны
            if (!value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
            if (value.length < 3) return null // слишком короткие — ложные срабатывания
        }
        return value.lowercase()
    }

    private fun isIpv4(candidate: String): Boolean {
        val parts = candidate.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.all { it.isDigit() } && (p.toIntOrNull() in 0..255)
        }
    }
}