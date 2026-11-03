package ru.tapps.trackerblocker.stable

import androidx.annotation.StringRes

/**
 * Описание DNS-сервера, который используется для разрешённых запросов.
 *
 * @property id стабильный идентификатор для SharedPreferences;
 * @property nameRes подпись в настройках;
 * @property ipv4/ipv6 адреса для обычного DNS по UDP:53;
 * @property dotHost имя для DNS over TLS (SNI и проверка сертификата);
 * @property dotIps IP-адреса, по которым открывается DoT-соединение;
 * @property worksInRussia `false`, если сервис может быть недоступен в РФ —
 *   тогда в настройках показывается предупреждение;
 * @property custom означает, что адреса вводит пользователь.
 */
internal data class DnsProvider(
    val id: String,
    @param:StringRes val nameRes: Int,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val dotHost: String? = null,
    val dotIps: List<String> = emptyList(),
    val worksInRussia: Boolean = true,
    val custom: Boolean = false
)

/**
 * Каталог DNS-провайдеров.
 *
 * По умолчанию выбран системный DNS: он использует резолверы текущей сети
 * (обычно провайдера или роутера), поэтому работает быстрее всего и не ломает
 * локальные имена. Остальные варианты — явный выбор пользователя; для
 * зарубежных сервисов показываем предупреждение о доступности в России.
 */
internal object DnsProviders {

    const val SYSTEM_ID = "system"
    const val CUSTOM_ID = "custom"

    /** Запасной резолвер, если системный DNS получить не удалось. */
    const val FALLBACK_IPV4 = "77.88.8.8"

    val all: List<DnsProvider> = listOf(
        DnsProvider(
            id = SYSTEM_ID,
            nameRes = R.string.settings_dns_system
        ),
        DnsProvider(
            id = "yandex",
            nameRes = R.string.settings_dns_yandex,
            ipv4 = listOf("77.88.8.8", "77.88.8.1"),
            ipv6 = listOf("2a02:6b8::feed:0ff", "2a02:6b8:0:1::feed:0ff"),
            dotHost = "common.dot.dns.yandex.net",
            dotIps = listOf("77.88.8.8", "77.88.8.1")
        ),
        DnsProvider(
            id = "adguard",
            nameRes = R.string.settings_dns_adguard,
            ipv4 = listOf("94.140.14.14", "94.140.15.15"),
            ipv6 = listOf("2a00:5a60::ad1:0ff", "2a00:5a60::ad2:0ff"),
            dotHost = "dns.adguard-dns.com",
            dotIps = listOf("94.140.14.14", "94.140.15.15")
        ),
        DnsProvider(
            id = "cloudflare",
            nameRes = R.string.settings_dns_cloudflare,
            ipv4 = listOf("1.1.1.1", "1.0.0.1"),
            ipv6 = listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
            dotHost = "one.one.one.one",
            dotIps = listOf("1.1.1.1", "1.0.0.1"),
            worksInRussia = false
        ),
        DnsProvider(
            id = "google",
            nameRes = R.string.settings_dns_google,
            ipv4 = listOf("8.8.8.8", "8.8.4.4"),
            ipv6 = listOf("2001:4860:4860::8888", "2001:4860:4860::8844"),
            dotHost = "dns.google",
            dotIps = listOf("8.8.8.8", "8.8.4.4"),
            worksInRussia = false
        ),
        DnsProvider(
            id = "quad9",
            nameRes = R.string.settings_dns_quad9,
            ipv4 = listOf("9.9.9.9", "149.112.112.112"),
            ipv6 = listOf("2620:fe::fe", "2620:fe::9"),
            dotHost = "dns.quad9.net",
            dotIps = listOf("9.9.9.9", "149.112.112.112"),
            worksInRussia = false
        ),
        DnsProvider(
            id = "opendns",
            nameRes = R.string.settings_dns_opendns,
            ipv4 = listOf("208.67.222.222", "208.67.220.220"),
            ipv6 = listOf("2620:119:35::35", "2620:119:53::53"),
            worksInRussia = false
        ),
        DnsProvider(
            id = CUSTOM_ID,
            nameRes = R.string.settings_dns_custom,
            worksInRussia = true,
            custom = true
        )
    )

    fun byId(id: String?): DnsProvider =
        all.firstOrNull { it.id == id } ?: all.first()
}
