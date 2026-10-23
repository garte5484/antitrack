package ru.tapps.trackerblocker.stable

/**
 * Отбрасывает у домена «доменную зону» и возвращает «корневой» лейбл
 * для группировки в статистике.
 *
 * Например: yandex.ru и yandex.ru/trck/min.js → «yandex»,
 * metrics.google.com → «google», a.b.youtube.com → «youtube».
 */
object DomainLabeler {

    private val multiPartZones = setOf(
        "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk",
        "com.ru", "net.ru", "org.ru", "pp.ru", "msk.ru", "spb.ru", "su",
        "com.tr", "com.br", "com.mx", "com.ar", "com.pe", "com.co", "com.ve", "com.ec",
        "co.jp", "ne.jp", "or.jp",
        "com.cn", "org.cn", "net.cn", "com.hk", "com.tw", "com.sg", "com.my",
        "com.au", "co.nz", "co.za", "com.ua", "in.ua", "com.pt", "com.pl", "com.gr", "com.ua"
    )

    private val singleZones = setOf(
        "com", "org", "net", "ru", "io", "info", "me", "tv", "us", "uk", "de", "fr", "it",
        "es", "nl", "no", "se", "fi", "dk", "pl", "cz", "ch", "at", "be", "eu", "ua", "by",
        "kz", "am", "ge", "az", "md", "ee", "lv", "lt", "edu", "gov", "mil", "biz", "mobi",
        "name", "pro", "asia", "su", "ca", "au", "jp", "cn", "in", "br", "mx", "ru"
    )

    /**
     * Возвращает лейбл домена без доменной зоны, в нижнем регистре.
     */
    fun rootLabel(host: String): String {
        var value = host.lowercase().trim().trimEnd('.')
        if (value.isEmpty()) return "unknown"

        for (zone in multiPartZones) {
            if (value.endsWith(".$zone")) {
                value = value.removeSuffix(".$zone")
                break
            }
        }

        val labels = value.split('.')
        val last = labels.lastOrNull() ?: return "unknown"
        if (labels.size >= 2 && last in singleZones) {
            return labels[labels.size - 2]
        }
        return last
    }
}