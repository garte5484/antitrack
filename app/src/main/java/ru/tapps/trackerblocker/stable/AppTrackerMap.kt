package ru.tapps.trackerblocker.stable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Встроенная база связей «трекер ↔ приложение».
 *
 * Нужна, чтобы не показывать «возможно ошибочное определение» там, где трекер
 * действительно принадлежит приложению, и чтобы не приписывать приложению
 * трекеры, которых в нём заведомо нет.
 *
 * Данные лежат в `assets/app_trackers.json` и попадают в APK при сборке.
 * Формат:
 * ```json
 * {
 *   "version": 1,
 *   "apps": [
 *     {
 *       "package": "com.example.app",
 *       "label": "Example",
 *       "trackers": ["example-tracker.com"],
 *       "complete": false
 *     }
 *   ],
 *   "exclusiveTrackers": ["tracker-only-in-listed-apps.com"],
 *   "companies": [
 *     {
 *       "company": "heytap",
 *       "exclusive": true,
 *       "apps": ["com.heytap.market"]
 *     }
 *   ]
 * }
 * ```
 *
 * `complete = true` означает, что список трекеров приложения полный: любой
 * другой трекер, приписанный этому приложению, считается ошибкой атрибуции
 * и уходит в статистику «Другое». Для обычных приложений флаг должен быть
 * выключен — иначе новые SDK будут ошибочно считаться чужими.
 *
 * `exclusiveTrackers` — домены, которые встречаются только в перечисленных
 * приложениях; на экране связей они получают пометку «Только в этих
 * приложениях».
 *
 * `companies` — владельцы трекеров. `company` задаётся доменом (`heytap.com`,
 * совпадение по суффиксу) или ключевым словом (`heytap`, вхождение в имя
 * хоста). `exclusive: true` работает как `exclusiveTrackers`, но для всех
 * трекеров компании. Пакеты из `apps` показываются в том числе тогда, когда
 * отдельной записи в `apps` у них нет.
 */
object AppTrackerMap {

    private const val ASSET_NAME = "app_trackers.json"
    private val lock = Any()

    /** Одно приложение и известные в нём трекеры. */
    data class AppEntry(
        val packageName: String,
        val label: String?,
        val trackers: Set<String>,
        val complete: Boolean
    )

    /** Компания/владелец трекера и приложения, где он встречается. */
    data class CompanyEntry(
        val name: String,
        val exclusive: Boolean,
        val apps: Set<String>
    )

    /** Трекер и приложения, в которых он точно встречается. */
    data class TrackerLink(
        val domain: String,
        val apps: List<AppEntry>,
        val exclusive: Boolean,
        val isCompany: Boolean = false
    )

    internal data class Data(
        val version: Int,
        val apps: List<AppEntry>,
        val exclusive: Set<String>,
        val companies: List<CompanyEntry> = emptyList()
    ) {
        val byPackage: Map<String, AppEntry> = apps.associateBy { it.packageName }
    }

    private val empty = Data(0, emptyList(), emptySet())

    @Volatile
    private var data: Data = empty

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val text = runCatching {
                context.applicationContext.assets
                    .open(ASSET_NAME)
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull()
            data = text?.let {
                runCatching { parse(it) }.getOrElse { empty }
            } ?: empty
            loaded = true
        }
    }

    val version: Int
        get() = data.version

    fun apps(): List<AppEntry> {
        // Вкладка «По приложениям» показывает и пакеты из компаний: у них может
        // не быть отдельной записи в apps, но связь с компанией всё равно есть.
        val merged = LinkedHashMap<String, AppEntry>()
        for (app in data.apps) merged[app.packageName] = app
        for (company in data.companies) {
            for (pkg in company.apps) {
                merged.getOrPut(pkg) {
                    AppEntry(
                        packageName = pkg,
                        label = null,
                        trackers = emptySet(),
                        complete = false
                    )
                }
            }
        }
        return merged.values.sortedBy { it.packageName }
    }

    fun companiesForApp(packageName: String): List<CompanyEntry> =
        data.companies.filter { packageName in it.apps }

    fun links(): List<TrackerLink> {
        val byTracker = LinkedHashMap<String, MutableList<AppEntry>>()
        for (app in data.apps) {
            for (tracker in app.trackers) {
                byTracker.getOrPut(tracker) { mutableListOf() }.add(app)
            }
        }
        val domainLinks = byTracker.entries
            .sortedBy { it.key }
            .map { (domain, apps) ->
                TrackerLink(
                    domain = domain,
                    apps = apps.sortedBy { it.packageName },
                    exclusive = data.exclusive.any { matchesDomain(domain, it) }
                )
            }
        val companyLinks = data.companies
            .sortedBy { it.name }
            .map { company ->
                TrackerLink(
                    domain = company.name,
                    apps = company.apps
                        .map { pkg ->
                            data.byPackage[pkg] ?: AppEntry(
                                packageName = pkg,
                                label = null,
                                trackers = emptySet(),
                                complete = false
                            )
                        }
                        .sortedBy { it.packageName },
                    exclusive = company.exclusive,
                    isCompany = true
                )
            }
        return domainLinks + companyLinks
    }

    /** Есть ли трекер в списке конкретного приложения. */
    fun isKnownInApp(packageName: String, host: String): Boolean =
        isKnownInApp(data, packageName, host)

    /**
     * Автор для статистики: если приложение помечено полным списком, а трекер
     * в нём неизвестен, запись уходит в «Другое».
     */
    fun statsOwner(packageName: String, host: String): String =
        statsOwner(data, packageName, host)

    fun displayName(context: Context, entry: AppEntry): String {
        val pm = context.applicationContext.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(entry.packageName, 0)).toString()
        }.getOrElse { entry.label ?: entry.packageName }
    }

    internal fun isKnownInApp(
        data: Data,
        packageName: String,
        host: String
    ): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false

        val app = data.byPackage[packageName]
        if (app != null && app.trackers.any { matchesDomain(h, it) }) return true

        return data.companies.any { company ->
            packageName in company.apps && matchesCompany(h, company.name)
        }
    }

    internal fun statsOwner(
        data: Data,
        packageName: String,
        host: String
    ): String {
        val app = data.byPackage[packageName]

        // Подтверждённая пара всегда остаётся у приложения.
        if (isKnownInApp(data, packageName, host)) return packageName

        // Компания, которая по базе встречается только в перечисленных
        // приложениях, не может принадлежать остальным. Проверяем все
        // совпадения, а не только самое точное: иначе домен и ключевое слово
        // одной компании могли бы скрыть эксклюзивность друг друга.
        if (data.companies.any {
                it.exclusive && packageName !in it.apps && matchesCompany(host, it.name)
            }
        ) {
            return BlockStats.OTHER
        }

        // Трекер, который по базе встречается только в перечисленных
        // приложениях, не может принадлежать остальным.
        if (data.exclusive.any { matchesDomain(host, it) }) return BlockStats.OTHER

        // Приложение с полным списком: любой другой трекер — чужая атрибуция.
        if (app?.complete == true) return BlockStats.OTHER

        return packageName
    }

    /**
     * Компания задаётся либо доменом (`heytap.com`), либо ключевым словом
     * (`heytap`). Для домена работает суффиксное совпадение, для слова —
     * вхождение в имя хоста.
     */
    internal fun matchesCompany(host: String, name: String): Boolean {
        val h = normalizeHost(host)
        val n = name.lowercase().trim().trimEnd('.')
        if (h.isEmpty() || n.isEmpty()) return false
        return if (n.contains('.')) matchesDomain(h, n)
        else h.contains(n)
    }

    /** Разбирает JSON базы. Ошибки формата игнорируются построчно. */
    internal fun parse(text: String): Data {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)

        val appsJson = root.optJSONArray("apps") ?: JSONArray()
        val apps = LinkedHashMap<String, AppEntry>()
        for (i in 0 until appsJson.length()) {
            val obj = appsJson.optJSONObject(i) ?: continue
            val packageName = obj.optString("package", "").trim()
            if (packageName.isEmpty()) continue

            val trackers = LinkedHashSet<String>()
            obj.optJSONArray("trackers")?.let { array ->
                for (j in 0 until array.length()) {
                    val domain = normalizeHost(array.optString(j, ""))
                    // Ключевые слова без точки здесь не поддерживаются: связь
                    // должна указывать на конкретный домен.
                    if (domain.isNotEmpty() && domain.contains('.')) trackers.add(domain)
                }
            }

            apps[packageName] = AppEntry(
                packageName = packageName,
                label = obj.optString("label", "").takeIf { it.isNotBlank() },
                trackers = trackers,
                complete = obj.optBoolean("complete", false)
            )
        }

        val exclusive = LinkedHashSet<String>()
        root.optJSONArray("exclusiveTrackers")?.let { array ->
            for (i in 0 until array.length()) {
                val domain = normalizeHost(array.optString(i, ""))
                if (domain.isNotEmpty()) exclusive.add(domain)
            }
        }

        val companies = ArrayList<CompanyEntry>()
        root.optJSONArray("companies")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("company", "").trim()
                if (name.isEmpty()) continue

                val apps = LinkedHashSet<String>()
                obj.optJSONArray("apps")?.let { packages ->
                    for (j in 0 until packages.length()) {
                        val pkg = packages.optString(j, "").trim()
                        if (pkg.isNotEmpty()) apps.add(pkg)
                    }
                }
                if (apps.isEmpty()) continue

                companies.add(
                    CompanyEntry(
                        name = name,
                        exclusive = obj.optBoolean("exclusive", false),
                        apps = apps
                    )
                )
            }
        }

        return Data(version, apps.values.toList(), exclusive, companies)
    }

    private fun normalizeHost(host: String): String =
        host.lowercase().trim().trimEnd('.')

    /** Домен совпадает сам или является родителем хоста. */
    internal fun matchesDomain(host: String, domain: String): Boolean {
        val h = normalizeHost(host)
        val d = normalizeHost(domain)
        if (h.isEmpty() || d.isEmpty()) return false
        return h == d || h.endsWith(".$d")
    }
}
