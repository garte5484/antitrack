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

    private val ignoreList = setOf(
        "localhost", "localhost.localdomain", "broadcasthost", "local",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters"
    )

    private class Source(
        private val sourceName: String,
        private val url: String,
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

        fun refreshIfStale(context: Context) {
            val app = context.applicationContext
            synchronized(lock) {
                val cachedTime = prefs(app).getLong(KEY_TIMESTAMP, 0L)
                val cachedVersion = prefs(app).getInt(KEY_VERSION, 0)
                val now = System.currentTimeMillis()

                if (cachedVersion == cacheVersion && cachedTime != 0L && now - cachedTime < CACHE_TTL_MS) {
                    ensureLoadedLocked(app) // кеш свежий — просто поднять в память
                    return
                }

                if (!hasInternet(app)) {
                    ensureLoadedLocked(app)
                    return
                }

                val fresh = try {
                    download()
                } catch (e: Exception) {
                    Log.w(TAG, "$sourceName: не удалось обновить список по URL", e)
                    null
                }

                if (fresh != null) {
                    writeCache(app, fresh)
                    apply(fresh)
                } else {
                    ensureLoadedLocked(app) // загрузка сорвалась — старый кеш
                }
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

    /** Число доменов/ключевых слов в списке трекеров. */
    val trackerCount: Int
        get() = trackersSource.size

    /** Число доменов/ключевых слов в списке рекламы. */
    val adCount: Int
        get() = adsSource.size

    /**
     * Обновляет оба списка при необходимости. Вызывается при входе в приложение
     * и при старте VPN-сервиса (не на главном потоке).
     */
    fun refreshIfStale(context: Context) {
        trackersSource.refreshIfStale(context)
        adsSource.refreshIfStale(context)
    }

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
    private fun parse(text: String): Set<String> {
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
    private fun normalize(token: String): String? {
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