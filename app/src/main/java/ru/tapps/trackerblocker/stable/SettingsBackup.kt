package ru.tapps.trackerblocker.stable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Резервная копия пользовательских настроек и правил.
 *
 * В файл попадает только то, что пользователь настроил сам: переключатели
 * блокировки, DNS, ответ на блокировку, пользовательские правила, исключённые
 * приложения и настройки автообновления списков. Статистика и состояние
 * защиты (включена/выключена) не переносятся.
 */
object SettingsBackup {

    private const val FORMAT = "antitrack-backup"
    private const val FORMAT_VERSION = 1

    /** Результат импорта: сколько правил и приложений применилось. */
    data class ImportResult(
        val success: Boolean,
        val rules: Int = 0,
        val apps: Int = 0,
        val error: String? = null
    )

    fun export(context: Context): String {
        val settings = JSONObject()
            .put("blockTrackers", AppSettings.blockTrackers())
            .put("blockAds", AppSettings.blockAds())
            .put("blockMode", AppSettings.blockMode())
            .put("tunnelStrictIp", AppSettings.tunnelStrictIp())
            .put("dnsProvider", AppSettings.dnsProviderId())
            .put("customDns", AppSettings.customDnsRaw())
            .put("dnsEncrypt", AppSettings.dnsEncryptionEnabled())
            .put("blockWithNullAddress", AppSettings.blockWithNullAddress())
            .put("autoUpdateBlocklists", BlocklistRepository.isAutoUpdateEnabled(context))
            .put(
                "autoUpdateTrackers",
                BlocklistRepository.isListAutoUpdateEnabled(
                    context,
                    BlocklistRepository.Kind.TRACKERS
                )
            )
            .put(
                "autoUpdateAds",
                BlocklistRepository.isListAutoUpdateEnabled(
                    context,
                    BlocklistRepository.Kind.ADS
                )
            )

        val rules = JSONObject()
            .put("blocked", JSONArray(HostRules.blockedHosts().sorted()))
            .put("allowed", JSONArray(HostRules.allowedHosts().sorted()))

        val apps = JSONObject()
            .put(
                "trackerBlockingDisabled",
                JSONArray(PerAppSettings.disabledTrackerPackages().sorted())
            )

        return JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("settings", settings)
            .put("rules", rules)
            .put("apps", apps)
            .toString(2)
    }

    fun import(context: Context, text: String): ImportResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ImportResult(false, error = "not_json")
        }
        if (root.optString("format") != FORMAT) {
            return ImportResult(false, error = "wrong_format")
        }

        val settings = root.optJSONObject("settings")
        if (settings != null) {
            if (settings.has("blockTrackers")) {
                AppSettings.setBlockTrackers(settings.optBoolean("blockTrackers"))
            }
            if (settings.has("blockAds")) {
                AppSettings.setBlockAds(settings.optBoolean("blockAds"))
            }
            val mode = settings.optString("blockMode", "")
            if (mode == AppSettings.MODE_DNS || mode == AppSettings.MODE_TUNNEL) {
                AppSettings.setBlockMode(mode)
            }
            if (settings.has("tunnelStrictIp")) {
                AppSettings.setTunnelStrictIp(settings.optBoolean("tunnelStrictIp"))
            }
            val provider = settings.optString("dnsProvider", "")
            if (provider.isNotEmpty() && DnsProviders.all.any { it.id == provider }) {
                AppSettings.setDnsProviderId(provider)
            }
            if (settings.has("customDns")) {
                AppSettings.setCustomDnsRaw(settings.optString("customDns", ""))
            }
            if (settings.has("dnsEncrypt")) {
                AppSettings.setDnsEncryptionEnabled(settings.optBoolean("dnsEncrypt"))
            }
            if (settings.has("blockWithNullAddress")) {
                AppSettings.setBlockWithNullAddress(
                    settings.optBoolean("blockWithNullAddress")
                )
            }
            if (settings.has("autoUpdateBlocklists")) {
                BlocklistRepository.setAutoUpdateEnabled(
                    context,
                    settings.optBoolean("autoUpdateBlocklists")
                )
            }
            if (settings.has("autoUpdateTrackers")) {
                BlocklistRepository.setListAutoUpdateEnabled(
                    context,
                    BlocklistRepository.Kind.TRACKERS,
                    settings.optBoolean("autoUpdateTrackers")
                )
            }
            if (settings.has("autoUpdateAds")) {
                BlocklistRepository.setListAutoUpdateEnabled(
                    context,
                    BlocklistRepository.Kind.ADS,
                    settings.optBoolean("autoUpdateAds")
                )
            }
        }

        var rules = 0
        root.optJSONObject("rules")?.let { json ->
            val blocked = json.optJSONArray("blocked").toStringSet()
            val allowed = json.optJSONArray("allowed").toStringSet()
            HostRules.replaceAll(blocked, allowed)
            rules = blocked.size + allowed.size
        }

        var apps = 0
        root.optJSONObject("apps")?.let { json ->
            val disabled = json.optJSONArray("trackerBlockingDisabled").toStringSet()
            PerAppSettings.replaceDisabledPackages(disabled)
            apps = disabled.size
        }

        return ImportResult(true, rules = rules, apps = apps)
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val result = LinkedHashSet<String>(length())
        for (i in 0 until length()) {
            val value = optString(i, "").trim()
            if (value.isNotEmpty()) result.add(value)
        }
        return result
    }
}
