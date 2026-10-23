package ru.tapps.trackerblocker.stable

/**
 * Список доменов и ключевых слов, которые считаются трекерами.
 *
 * Записи без точки (например, «track», «telemetry») трактуются как
 * ключевые слова и блокируют ЛЮБОЙ хост, содержащий их в имени.
 * Записи с точкой — обычные домены: блокируется сам домен и все поддомены.
 *
 * Данные делятся на:
 *  - встроенный демо-набор ([builtinDomains]) — нужен для быстрого старта;
 *  - основная часть — список, скачанный [BlocklistRepository] из сети.
 */
object TrackerBlocklist {

    private val builtinDomains: Set<String> = setOf(
        // — рекламные сети и аналитика Google —
        "google-analytics.com",
        "googletagmanager.com",
        "googlesyndication.com",
        "googleadservices.com",
        "doubleclick.net",
        "analytics.google.com",

        // — соцсети (пиксели, виджеты) —
        "facebook.com",
        "fbcdn.net",
        "instagram.com",
        "web.facebook.com",

        // — метрики и аналитика —
        "mixpanel.com",
        "amplitude.com",
        "segment.io",
        "segment.com",
        "hotjar.com",
        "crazyegg.com",
        "mouseflow.com",
        "chartbeat.com",
        "optimizely.com",
        "quantcast.com",
        "scorecardresearch.com",
        "newrelic.com",
        "matomo.cloud",

        // — рекламные платформы —
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "moatads.com",
        "adroll.com",
        "bluekai.com",
        "mathtag.com",
        "lijit.com",
        "amazon-adsystem.com",
        "advertising.com",

        // — трекинг мобильных приложений —
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "kochava.com",
        "flurry.com",

        // — российские трекеры —
        "mc.yandex.ru",
        "top.mail.ru",
        "vk.com",
        "tns-counter.ru"
    )

    @Volatile
    private var custom: Set<String> = emptySet()

    @Volatile
    private var domains: Set<String> = builtinDomains

    @Volatile
    private var keywords: Set<String> = emptySet()

    val size: Int
        get() = domains.size + keywords.size

    /**
     * Проверяет, заблокирован ли хост.
     * Сначала — точная проверка домена и его поддоменов, затем — поиск
     * ключевых слов в имени хоста.
     */
    fun isBlocked(host: String): Boolean {
        val value = host.lowercase().trimEnd('.')
        if (value.isEmpty()) return false

        var suffix = value
        while (true) {
            if (suffix in domains) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }

        for (keyword in keywords) {
            if (value.contains(keyword)) return true
        }
        return false
    }

    fun updateCustom(entries: Set<String>) {
        custom = entries.filterNot { it.isBlank() }.map { it.lowercase().trim().trimEnd('.') }.toSet()
        rebuild()
    }

    private fun rebuild() {
        val all = builtinDomains + custom
        domains = all.filter { it.contains('.') }.toSet()
        keywords = all.filter { !it.contains('.') }.toSet()
    }
}