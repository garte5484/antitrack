package ru.tapps.trackerblocker.stable

/**
 * Список доменов рекламных сетей (отдельно от трекеров).
 *
 * Тот же формат, что у [TrackerBlocklist]: записи без точки — ключевые слова
 * (блокируют любой хост с таким словом), записи с точкой — домен и его
 * поддомены. Основная часть списка скачивается [BlocklistRepository] по URL
 * рекламного списка (alladlist), встроенный набор — минимальный запас на
 * случай, когда список ещё не загружен или сети нет.
 */
object AdBlocklist {

    private val builtinDomains: Set<String> = setOf(
        "adform.net",
        "smartadserver.com",
        "yieldmanager.net",
        "adsafeprotected.com",
        "doubleverify.com",
        "appnexus.com",
        "integralads.com",
        "spotxchange.com",
        "adtech.de",
        "adtechus.com"
    )

    @Volatile
    private var custom: Set<String> = emptySet()

    @Volatile
    private var domains: Set<String> = builtinDomains

    @Volatile
    private var keywords: Set<String> = emptySet()

    val size: Int
        get() = domains.size + keywords.size

    /** Заблокирован ли хост рекламным списком (точное совпадение + поддомены + ключевые слова). */
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