package ru.tapps.trackerblocker.stable

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Информация о доступном обновлении из GitHub Releases.
 *
 * @property version версия релиза без префикса `v` (например, `1.2.0`);
 * @property notes текст релиза (может отсутствовать);
 * @property apkUrl прямая ссылка на APK-ассет релиза;
 * @property apkName имя файла ассета;
 * @property sizeBytes размер APK в байтах (0, если GitHub его не сообщил).
 */
data class UpdateInfo(
    val version: String,
    val notes: String?,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long
)

/**
 * Проверка обновлений через GitHub Releases и загрузка APK.
 *
 * Используется последний опубликованный релиз репозитория [REPO]:
 *  - запрос к GitHub API возвращает тег последнего релиза и список ассетов;
 *  - если тег новее установленной версии и в релизе есть `.apk` — предлагаем обновление;
 *  - APK скачивается в `filesDir/updates` с отчётом о прогрессе.
 *
 * Все сетевые операции выполняются на [Dispatchers.IO] и не бросают исключения
 * наружу при проверке: отсутствие релиза/сети/лимит GitHub API — это просто
 * «обновлений нет».
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "garte5484/antitrack"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val USER_AGENT = "AntiTrack"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Проверяет последний релиз GitHub. Возвращает [UpdateInfo], только если
     * версия релиза новее [currentVersion] и в релизе есть APK-ассет.
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(httpGet(API_URL, "application/vnd.github+json"))

            val tag = root.optString("tag_name").trim()
            if (tag.isEmpty()) return@withContext null
            if (!isNewer(tag, currentVersion)) {
                Log.i(TAG, "Обновлений нет: последний релиз $tag, установлено $currentVersion")
                return@withContext null
            }

            val assets = root.optJSONArray("assets") ?: return@withContext null
            var asset: JSONObject? = null
            for (i in 0 until assets.length()) {
                val candidate = assets.optJSONObject(i) ?: continue
                val name = candidate.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    asset = candidate
                    break
                }
            }
            if (asset == null) {
                Log.i(TAG, "Релиз $tag не содержит APK-ассета")
                return@withContext null
            }

            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) return@withContext null

            UpdateInfo(
                version = normalizeVersion(tag),
                notes = if (root.isNull("body")) null else root.optString("body").takeIf { it.isNotBlank() },
                apkUrl = url,
                apkName = asset.optString("name"),
                sizeBytes = asset.optLong("size", 0L)
            )
        } catch (e: Exception) {
            // Нет релизов (404), нет сети, лимит GitHub API (403) — не повод для ошибки в UI.
            Log.w(TAG, "Проверка обновлений не удалась", e)
            null
        }
    }

    /**
     * Скачивает APK обновления в `filesDir/updates` и возвращает файл.
     * [onProgress] вызывается с процентом 0..100 (может пропускать значения).
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        // Убираем остатки прошлых попыток, чтобы не копить место в кеше.
        dir.listFiles()?.forEach { it.delete() }
        val fileName = info.apkName
            .ifBlank { "antitrack.apk" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val file = File(dir, fileName)

        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var copied = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (file.length() == 0L) throw IOException("Пустой файл")
            onProgress(100)
            Log.i(TAG, "Обновление скачано: ${file.absolutePath} (${file.length()} байт)")
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Убирает префикс `v`/`V` из тега релиза. */
    private fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")

    /**
     * Сравнивает версии по числовым компонентам: `1.2.10` новее `1.2.9`,
     * `v2.0` новее `1.9.9`. Нечисловые суффиксы (`-beta`, `+build`) игнорируются
     * после своей числовой части.
     */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(version: String): List<Int> = normalizeVersion(version)
            .split('.', '-', '_', '+', ' ')
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        val remoteParts = parts(remote)
        val localParts = parts(local)
        if (remoteParts.isEmpty()) return false

        val count = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until count) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun httpGet(url: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
