package ru.tapps.trackerblocker.stable

import androidx.compose.ui.graphics.Color

/**
 * Брендовые подписи для статистики: если домен из логов содержит имя бренда,
 * вместо корневого домена показываем название бренда, а в списке — его
 * иконку-бейдж (цвет + глиф). Остальные домены получают аватар из первой
 * заглавной буквы названия на стабильном (детерминированном) цвете.
 */
internal object BrandNames {

    data class Brand(
        val color: Color,
        val glyph: String,
        val displayName: String,
        val iconRes: Int?,
        val matches: (String) -> Boolean
    )

    val brands = listOf(
        Brand(Color(0xFFFC3F1D), "Я", "Яндекс", R.drawable.ic_brand_yandex) {
            it.contains("yandex") || it.contains("yastatic") ||
                it.contains("yardianalytics") || it.contains("yadro") ||
                it.contains("ya.ru") || it.equals("yandex")
        },
        Brand(Color(0xFF4285F4), "G", "Google", R.drawable.ic_brand_google) {
            it.contains("google") || it.contains("gstatic") ||
                it.contains("app-measurement") || it.contains("crashlytics") ||
                it.contains("firebase") || it.equals("googl")
        },
        Brand(Color(0xFF4285F4), "G", "DoubleClick (Google)", R.drawable.ic_brand_google) {
            it.contains("doubleclick")
        },
        Brand(Color(0xFF005FF9), "@", "Mail.ru", R.drawable.ic_brand_mail_ru) {
            it.contains("mail.ru") || it.equals("mail")
        },
        Brand(Color(0xFF0077FF), "В", "ВКонтакте", R.drawable.ic_brand_vk) {
            it.contains("vk") || it.contains("userapi")
        },
        Brand(Color(0xFFFF6900), "mi", "Xiaomi", R.drawable.ic_brand_xiaomi) {

            it.contains("xiaomi") || it.contains("miui") ||
                it.contains("mi.com") || it.contains("miui") || it.contains("xiaomi")
        },
        Brand(Color(0xFF0866FF), "f", "Facebook", R.drawable.ic_brand_facebook) {
            it.contains("facebook") || it.contains("fbcdn")
        },

        // — атрибуция и installs —
        Brand(Color(0xFF1D7AFC), "A", "AppsFlyer", null) {
            it.contains("appsflyer")
        },
        Brand(Color(0xFF455A64), "A", "Adjust", null) {
            it.contains("adjust")
        },
        Brand(Color(0xFF00A572), "B", "Branch", null) {
            it.contains("branch")
        },
        Brand(Color(0xFF37474F), "U", "Unity", R.drawable.ic_brand_unity) {
            it.contains("unity3d") || it.contains("unity.com") || it.contains("unityads")
        },
        Brand(Color(0xFF00B0F0), "L", "AppLovin", R.drawable.ic_brand_applovin) {
            it.contains("applovin") || it.contains("applvn")
        },
        Brand(Color(0xFF1F7A8C), "K", "Kochava", null) {
            it.contains("kochava")
        },

        // — push и краш-репорты —
        Brand(Color(0xFF455A64), "O", "OneSignal", null) {
            it.contains("onesignal")
        },
        Brand(Color(0xFF7569C9), "S", "Sentry", null) {
            it.contains("sentry")
        },
        Brand(Color(0xFFE0245E), "B", "Bugsnag", null) {
            it.contains("bugsnag")
        },

        // — продуктовая аналитика —
        Brand(Color(0xFF2D6BFF), "A", "Amplitude", null) {
            it.contains("amplitude")
        },
        Brand(Color(0xFF7856FF), "M", "Mixpanel", null) {
            it.contains("mixpanel")
        },
        Brand(Color(0xFF3FA796), "S", "Segment", R.drawable.ic_brand_segment) {
            it.contains("segment")
        },

        // — соцсети и реклама —
        Brand(Color(0xFFFE2C55), "T", "TikTok", R.drawable.ic_brand_tiktok) {
            it.contains("tiktok") || it.contains("bytedance") || it.contains("musical.ly")
        },
        Brand(Color(0xFFFE2C55), "p", "Pinterest", R.drawable.ic_brand_pinterest) {
            it.contains("pinterest")
        },
        Brand(Color(0xFFFF9900), "a", "Amazon Ads", R.drawable.ic_brand_amazon) {
            it.contains("amazon")
        }
    )

    fun brandOf(domain: String): Brand? =
        brands.find { it.matches(domain.lowercase()) }

    /**
     * Имя для строки статистики: название бренда, а для неизвестных доменов —
     * корневой лейбл, чтобы в списке не было длинных хостов.
     */
    fun displayName(domain: String): String =
        brandOf(domain)?.displayName ?: DomainLabeler.rootLabel(domain)

    private val avatarPalette = listOf(
        Color(0xFFE57373),
        Color(0xFF64B5F6),
        Color(0xFF81C784),
        Color(0xFFFFB74D),
        Color(0xFF9575CD),
        Color(0xFF4DB6AC),
        Color(0xFFF06292),
        Color(0xFFA1887F),
        Color(0xFF90A4AE),
        Color(0xFF7986CB)
    )

    /** Стабильный цвет для домена: одинаковый между перезапусками. */
    fun avatarColor(seed: String): Color {
        var hash = 7
        for (c in seed) hash = (hash * 31 + c.code) and 0x7fffffff
        return avatarPalette[hash % avatarPalette.size]
    }
}
