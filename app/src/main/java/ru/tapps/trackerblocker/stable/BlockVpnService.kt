package ru.tapps.trackerblocker.stable

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.Keep
import ru.tapps.trackerblocker.stable.tunnel.Allowed
import ru.tapps.trackerblocker.stable.tunnel.Packet
import ru.tapps.trackerblocker.stable.tunnel.ResourceRecord
import ru.tapps.trackerblocker.stable.tunnel.TunnelDnsTable
import ru.tapps.trackerblocker.stable.tunnel.Usage
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN-блокировщик (гибридная схема):
 *
 *  - в туннель маршрутизируется ТОЛЬКО адрес виртуального DNS ([DNS_IP], /32),
 *    поэтому здесь гарантированно оказываются все системные DNS-запросы;
 *  - TCP и остальной UDP Android отправляет в реальную сеть напрямую —
 *    интернет не ломается и не замедляется проксированием;
 *  - рабочий поток читает пакеты из туннеля, разбирает QNAME;
 *    трекер — блокируется ответом NXDOMAIN (клиент завершает запрос сразу,
 *    факт — в [BlockStats]);
 *    разрешённый запрос — пересылается реальному резолверу защищённым
 *    сокетом, ответ собирается обратно и пишется в туннель;
 *  - ответы кешируются (подмена transaction id под входящий запрос);
 *  - адреса известных DoH/DoT-резолверов ([bypassDnsIps]) тоже уводятся
 *    в туннель, чтобы зашифрованный DNS не обходил фильтр;
 *  - трекер-троттлинг: приложение, шлющее больше [THROTTLE_HIT_LIMIT]
 *    трекерных запросов за [THROTTLE_WINDOW_MS], на [THROTTLE_DURATION_MS]
 *    теряет доступ к интернету (глушатся даже разрешённые его запросы).
 *
 * SNI/DPI по TCP-трафику в этой схеме физически недоступны (TCP здесь не
 * маршрутизируется), поэтому домены фильтруются строго на уровне DNS —
 * это и есть гибридный режим «TCP напрямую».
 */
class BlockVpnService : VpnService() {

    // ————————————— режим полного туннеля (userspace-стек) —————————————

    /** Контекст нативного стека; 0 — стек не запущен. */
    @Volatile
    private var nativeContext = 0L

    @Volatile
    private var nativeThread: Thread? = null

    /** Нативная библиотека загружена; ошибка загрузки логируется один раз. */
    @Volatile
    private var nativeLoaded = false

    /** Таблица «IP → домены», собранная из DNS-ответов нативного стека. */
    private val tunnelDns = TunnelDnsTable()

    /** Режим, с которым построен текущий туннель. */
    @Volatile
    private var appliedMode: String = AppSettings.MODE_DNS

    private external fun jniInit(sdk: Int): Long
    private external fun jniStart(context: Long, logLevel: Int)
    private external fun jniRun(context: Long, tun: Int, fwd53: Boolean, rcode: Int)
    private external fun jniStop(context: Long)
    private external fun jniClear(context: Long)
    private external fun jniDone(context: Long)
    private external fun jniGetMtu(): Int

    private var tunnel: ParcelFileDescriptor? = null
    private var tunnelWriter: java.io.FileDescriptor? = null

    private val writeLock = Any()

    /** Набор пакетов, исключённых из VPN на момент последнего establish(). */
    @Volatile
    private var appliedExcludedPackages: Set<String> = emptySet()

    /** Обновление уведомления после окончания паузы. */
    private val notifyHandler = Handler(Looper.getMainLooper())
    private val pauseEndRunnable = Runnable { if (running) showNotification() }

    /**
     * Плановая проверка блоклистов, пока VPN работает: без неё списки
     * обновлялись только при входе в приложение и старте сервиса, а при
     * длительной работе (сутки и больше) успевали устареть.
     */
    private val blocklistCheckRunnable: Runnable = Runnable {
        if (!running) return@Runnable
        Thread {
            BlocklistRepository.refreshIfStale(applicationContext)
            Log.d(TAG, "Плановая проверка блоклистов завершена")
        }.apply {
            name = "AntiTrack-BlocklistPeriodic"
            isDaemon = true
        }.start()
        notifyHandler.postDelayed(blocklistCheckRunnable, BLOCKLIST_CHECK_INTERVAL_MS)
    }

    @Volatile
    private var relayPool: java.util.concurrent.ExecutorService? = null

    private class DnsCacheEntry(val response: ByteArray, val expiresAt: Long)
    private val dnsCache = ConcurrentHashMap<String, DnsCacheEntry>()

    /**
     * DNS-серверы текущей сети для режима «Системный». Обновляются при смене
     * Wi-Fi/мобильной сети, иначе после переключения запросы уходили бы на
     * старый, уже недоступный резолвер.
     */
    @Volatile
    private var systemDnsServers: List<String> = emptyList()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Транспорт до upstream: UDP и DoT, с переиспользованием TLS-соединения. */
    @Volatile
    private var dnsUpstream: DnsUpstream? = null

    @Volatile
    private var appliedDnsProviderId: String? = null

    /** Последний ответивший UDP-сервер: пробуем его первым. */
    @Volatile
    private var lastGoodDns: Pair<String, String>? = null

    // — возврат защиты после другого VPN —

    /**
     * Наш VPN отозван не пользователем, а другим VPN-приложением. Сервис
     * остаётся в foreground и ждёт, пока чужой VPN выключится, чтобы поднять
     * блокировку снова. Ручная остановка (ACTION_STOP) этот флаг сбрасывает.
     */
    @Volatile
    private var awaitingOtherVpn = false

    /** Чужой VPN уже наблюдался: не перехватываем его раньше времени. */
    private var sawOtherVpn = false

    /** С какого момента чужого VPN не видно; 0 — виден или ещё не проверяли. */
    private var noVpnSinceMs = 0L

    /** Когда началось ожидание: страховка на случай, если чужой VPN не виден. */
    private var waitStartedMs = 0L

    /** VPN-сети, замеченные подпиской. Наличие любой означает чужой VPN. */
    private val vpnNetworks: MutableSet<Network> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Network, Boolean>())

    private var resumeAttempts = 0

    private var resumeVpnWatcher: ConnectivityManager.NetworkCallback? = null
    private var resumeDefaultWatcher: ConnectivityManager.NetworkCallback? = null

    private val resumePollRunnable = object : Runnable {
        override fun run() {
            if (!awaitingOtherVpn) return
            checkOtherVpnState()
            if (awaitingOtherVpn) notifyHandler.postDelayed(this, RESUME_POLL_MS)
        }
    }

    @Volatile
    private var running = false

    /**
     * Приложение, которое последним реально передало данные. Основной сигнал —
     * [ConnectivityManager.getConnectionOwnerUid] (API 29+): он ищет владельца
     * сокета по паре адресов; если он безуспешен либо версия Android меньше 10,
     * авторство определяется эвристикой «недавняя активность» (API 23+).
     * Полная цепочка — в [attributedApp].
     *
     * На современных прошивках чужие uid в /proc/net замаскированы, а /proc/uid_stat
     * удалён — единственный легальный источник per-uid активности — [NetworkStatsManager]
     * (требует выданный «Доступ к использованию приложений»). Победителя выбираем
     * по приросту с прошлого опроса (~1 с), предпочитая приложения, начавшие
     * передавать «только что»: автор DNS-запроса обычно просыпается первым и
     * отправляет именно его, а фоновый сервис болтает постоянно.
     */
    @Volatile
    private var lastActiveUid = -1
    @Volatile
    private var lastActiveAtMs = 0L
    private val trafficWindowMutex = Any()

    // — состояние сигналов атрибуции и её диагностики —

    /** Сумма байт per-uid на прошлом опросе — база для расчёта прироста. */
    private val uidBytesAtScan = HashMap<Int, Long>()

    /** uid, у которых был прирост на предыдущем опросе — т.е. не «свежие». */
    private val prevDeltaUids = HashSet<Int>()

    /** uid -> прирост байт за последний опрос, по убыванию (печатается при [ATTRIBUTION_DIAGNOSTICS]). */
    @Volatile
    private var topUidDeltas: List<Pair<Int, Long>> = emptyList()

    /** Отдельный поток для проб: IPC-вызовы не должны блокировать чтение туннеля. */
    @Volatile
    private var diagnosticsPool: java.util.concurrent.ExecutorService? = null


    // — трекер-троттлинг: доступ только из потока туннеля (handlePacket) —

    /** Моменты трекерных блоков приложения (пакет -> метки времени). */
    private val trackerHits = HashMap<String, MutableList<Long>>()
    /** До какого момента отключён DNS для приложения (пакет -> метка). */
    private val throttledUntil = HashMap<String, Long>()
    /** Количество активных «банов» (быстрый путь: 0 = не вычисляем атрибуцию). */
    private var activeThrottles = 0

    private val lifecycleLock = Any()

    /** Сервис запущен после перезагрузки — результат пишем в диагностику. */
    private var autostartRequested = false

    companion object {
        private const val TAG = "BlockVpnService"
        private const val CHANNEL_ID = "antitrack_vpn"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_PAUSE = 10
        private const val REQUEST_RESUME = 11
        private const val REQUEST_STOP = 12
        private const val REQUEST_RESUME_NOW = 13

        /** Окно, в течение которого приложение считается «активно передававшим». */
        private const val ACTIVE_APP_WINDOW_MS = 30_000L
        /**
         * Окно агрегации NetworkStats за один опрос. Опросы идут раз в секунду,
         * поэтому прирост между соседними опросами — это байты за последнюю
         * секунду; 30 с нужны, чтобы запрос успел накрыть интересующие бакеты.
         */
        private const val TRAFFIC_WINDOW_MS = 30_000L

        /**
         * Диагностика атрибуции DNS-запроса. Сама проба владельца сокета работает
         * всегда (см. [probeAppName]); флаг включает только сравнительный лог:
         * результат пробы, прирост per-uid счётчиков и текущий результат
         * эвристики рядом, чтобы на живом устройстве сравнить их между собой.
         */
        private const val ATTRIBUTION_DIAGNOSTICS = false

        /** Типы сетей, которые опрашиваем у [NetworkStatsManager]. */
        private val STATS_NETWORK_TYPES = intArrayOf(
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_ETHERNET
        )

        const val ACTION_STOP = "ru.tapps.trackerblocker.stable.action.STOP"
        const val ACTION_PAUSE = "ru.tapps.trackerblocker.stable.action.PAUSE"
        const val ACTION_RESUME = "ru.tapps.trackerblocker.stable.action.RESUME"
        const val ACTION_REFRESH = "ru.tapps.trackerblocker.stable.action.REFRESH"

        /** Немедленно вернуть защиту, даже если сейчас работает другой VPN. */
        const val ACTION_RESUME_NOW = "ru.tapps.trackerblocker.stable.action.RESUME_NOW"

        /**
         * Сервис жив, но VPN сейчас принадлежит другому приложению.
         * MainActivity показывает по этому флагу отдельный статус.
         */
        @Volatile
        var isWaitingForOtherVpn = false
            private set

        /** true, если сервис поднят [BootReceiver] — для диагностики автозапуска. */
        const val EXTRA_AUTOSTART = "ru.tapps.trackerblocker.stable.extra.AUTOSTART"

        /** Длительность паузы из уведомления. */
        private const val PAUSE_DURATION_MS = 15 * 60 * 1000L

        private const val TUN_IP = "10.88.88.1"
        private const val TUN_PREFIX = 24
        private const val DNS_IP = "10.88.88.53"
        private const val DNS_ROUTE_PREFIX = 32

        /**
         * IPv6-адрес туннеля и виртуального DNS (ULA). Нужен, чтобы запросы,
         * ушедшие на IPv6-резолвер системы, тоже попадали в фильтр: маршрут
         * /128 забирает в туннель только адрес нашего DNS, остальной IPv6
         * по-прежнему идёт напрямую.
         */
        private const val TUN_IP6 = "fd00:1:fd00:1::1"
        private const val TUN_PREFIX6 = 128
        private const val DNS_IP6 = "fd00:1:fd00:1::53"
        private const val DNS_ROUTE_PREFIX6 = 128

        // Запасной резолвер, если системный DNS недоступен или не определился.
        private const val FALLBACK_DNS_IPV4 = "77.88.8.8"

        private const val DNS_PORT = 53
        /**
         * MTU туннеля. Через него ходят только DNS-пакеты, поэтому запас
         * больше 1500 безопасен и нужен для ответов DoT, которые не всегда
         * укладываются в классический лимит UDP.
         */
        private const val MTU = 4096
        /** MTU полного туннеля: нативный стек рассчитан на 10000 (как NetGuard). */
        private const val TUNNEL_MTU = 10000
        private const val TUNNEL_RCODE = 3
        /** Сколько держать соответствие «домен → IP», полученное из SNI. */
        private const val SNI_MAPPING_TTL_SEC = 300
        private const val DNS_TIMEOUT_MS = 2000
        private const val DNS_DOT_TIMEOUT_MS = 4000
        /** TTL кеша, когда в ответе нет записей или его не удалось разобрать. */
        private const val DNS_CACHE_TTL_MS = 120_000L
        private const val DNS_CACHE_MIN_TTL_MS = 30_000L
        private const val DNS_CACHE_MAX_TTL_MS = 60 * 60 * 1000L
        private const val DNS_MAX_RESPONSE = 4096
        private const val DNS_CACHE_MAX_SIZE = 2048
        /**
         * Максимальный размер EDNS0-ответа. 1232 байта — безопасный размер
         * для любой сети; ответы больше прежнего лимита теперь не теряются
         * молча, а заодно не провоцируют фрагментацию.
         */
        private const val EDNS_MAX_PAYLOAD = 1232

        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28

        /**
         * Известные серверы зашифрованного/альтернативного DNS (DoH/DoT и
         * жёстко прописанные резолверы). Их адреса уводятся в туннель, где TCP
         * не ретранслируется и гаснет: приложение откатывается на системный DNS,
         * который уже проходит через наш фильтр. Собственные relay-сокеты выведены
         * через VpnService.protect(), поэтому наши запросы к upstream не страдают.
         */
        private val bypassDnsIps = listOf(
            "1.1.1.1",            // Cloudflare
            "1.0.0.1",            // Cloudflare
            "8.8.8.8",            // Google
            "8.8.4.4",            // Google
            "9.9.9.9",            // Quad9
            "149.112.112.112",    // Quad9
            "208.67.222.222",     // OpenDNS
            "208.67.220.220",     // OpenDNS
            "94.140.14.14",       // AdGuard DNS
            "94.140.15.15",       // AdGuard DNS
            "176.103.130.130",    // AdGuard DNS (legacy)
            "176.103.130.131",    // AdGuard DNS (legacy)
            "64.6.64.6",          // Neustar
            "64.6.65.6",          // Neustar
            "84.200.69.80",       // DNS.WATCH
            "84.200.70.40",       // DNS.WATCH
            "76.76.2.0",          // ControlD
            "76.76.10.0",         // ControlD
            "194.242.2.2",        // Mullvad
            "193.110.81.0",       // DNS0.eu
            "185.253.5.0",        // DNS0.eu
            "185.228.168.9",      // CleanBrowsing
            "185.228.169.9",      // CleanBrowsing
            "45.90.28.0",         // NextDNS
            "45.90.30.0"          // NextDNS
        )

        /** Те же резолверы по IPv6 — иначе DoH/DoT по IPv6 обходил бы туннель. */
        private val bypassDnsIps6 = listOf(
            "2606:4700:4700::1111",   // Cloudflare
            "2606:4700:4700::1001",   // Cloudflare
            "2001:4860:4860::8888",   // Google
            "2001:4860:4860::8844",   // Google
            "2620:fe::fe",            // Quad9
            "2620:fe::9",             // Quad9
            "2620:119:35::35",        // OpenDNS
            "2620:119:53::53",        // OpenDNS
            "2a00:5a60::ad1:0ff",     // AdGuard DNS
            "2a00:5a60::ad2:0ff",     // AdGuard DNS
            "2a02:6b8::feed:0ff",     // Yandex.DNS
            "2a02:6b8:0:1::feed:0ff", // Yandex.DNS (backup)
            "2606:1a40::",            // ControlD
            "2606:1a40:1::",          // ControlD
            "2a07:e340::2",           // Mullvad
            "2a0f:fc80::",            // DNS0.eu
            "2a0f:fc81::",            // DNS0.eu
            "2a0d:2a00:1::2",         // CleanBrowsing
            "2a0d:2a00:2::2",         // CleanBrowsing
            "2a07:a8c0::",            // NextDNS
            "2a07:a8c1::"             // NextDNS
        )

        /**
         * Как часто сервис проверяет, не устарели ли блоклисты. Сама проверка
         * дешёвая: [BlocklistRepository.refreshIfStale] скачивает списки только
         * если кеш старше 6 часов.
         */
        private const val BLOCKLIST_CHECK_INTERVAL_MS = 60 * 60 * 1000L

        /** Как часто перепроверяем, не отключился ли чужой VPN. */
        private const val RESUME_POLL_MS = 5_000L
        /**
         * Пауза после исчезновения чужого VPN. VPN-приложения могут
         * переподключаться, поэтому спешить с establish() нельзя: иначе мы
         * отберём слот у только что поднявшегося чужого туннеля.
         */
        private const val RESUME_GRACE_MS = 15_000L
        /**
         * Если чужой VPN так и не стал виден (приложение упало при запуске),
         * ждём достаточно долго, чтобы не мешать медленному подключению.
         */
        private const val RESUME_UNSEEN_MS = 45_000L
        /** Задержка перед повторной попыткой, если establish() не удался. */
        private const val RESUME_RETRY_MS = 15_000L
        private const val RESUME_MAX_ATTEMPTS = 5

        /**
         * Пауза после перезапуска сервиса системой (intent == null): даём
         * чужому VPN проявиться, чтобы не выбить его из слота сразу.
         */
        private const val SYSTEM_RESTART_SETTLE_MS = 4_000L

        @Volatile
        var isRunning = false
            private set

        /**
         * Полный туннель выбран, но нативный стек не загрузился, и сервис
         * работает в DNS-режиме. UI показывает предупреждение, иначе
         * пользователь думает, что включён точный режим.
         */
        @Volatile
        var tunnelUnavailable = false
            private set

        val blockedCount = AtomicLong(0)

        /**
         * Пакеты, которые всегда исключены из VPN: само приложение и браузер
         * Mangum со встроенной блокировкой. Их трафик не попадает в туннель.
         */
        private val exemptPackages = setOf(
            "ru.tapps.trackerblocker.stable",
            "ru.tapps.mangum.android"
        )

        /** Трекер-троттлинг: окно, за которое считаются трекерные запросы. */
        private const val THROTTLE_WINDOW_MS = 10_000L
        /** Больше стольких трекерных запросов за окно — отключаем доступ. */
        private const val THROTTLE_HIT_LIMIT = 2
        /** На сколько полностью отключаем DNS (интернет) нарушителю. */
        private const val THROTTLE_DURATION_MS = 3_000L

        private val uidPackages = ConcurrentHashMap<Int, String>()
        private val uidCacheSync = Any()
        @Volatile
        private var uidCacheLoaded = false

        fun rebuildUidCache() {
            uidCacheLoaded = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BlockStats.init(this)
        AppSettings.init(this)
        PerAppSettings.init(this)
        RequestLog.init(this)
        HostRules.init(this)
        AppTrackerMap.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand: running=$running startId=$startId action=${intent?.action ?: "null"}")
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            autostartRequested = true
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // Платформа держит binding к VpnService, поэтому stopService() не может
                // уничтожить сервис. Правильное отключение: закрыть fd туннеля — система
                // разорвёт VPN, отпустит binding и вызовет onDestroy.
                Log.i(TAG, "Команда отключения")
                AppSettings.clearPause()
                // Пользователь выключил защиту вручную — после перезагрузки
                // она не должна включаться сама.
                AppSettings.setProtectionEnabled(false)
                stopWaitingForOtherVpn()
                notifyHandler.removeCallbacks(pauseEndRunnable)
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                AppSettings.pauseFor(PAUSE_DURATION_MS)
                Log.i(TAG, "Пауза блокировки на ${PAUSE_DURATION_MS / 60_000} мин")
                showNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                AppSettings.clearPause()
                Log.i(TAG, "Блокировка возобновлена")
                showNotification()
                return START_STICKY
            }
            ACTION_RESUME_NOW -> {
                Log.i(TAG, "Возврат защиты по команде пользователя")
                stopWaitingForOtherVpn()
                showNotification()
                startVpn(force = true)
                return START_STICKY
            }
            ACTION_REFRESH -> {
                // Настройки изменились. Если состав исключённых приложений или
                // режим блокировки другие, пересоздаём туннель: и то и другое
                // применяется только в момент establish().
                val modeChanged = AppSettings.blockMode() != appliedMode
                if (running && (excludedPackages() != appliedExcludedPackages || modeChanged)) {
                    Log.i(
                        TAG,
                        "Настройки VPN изменились" +
                            " (режим: ${AppSettings.blockMode()}, был $appliedMode)" +
                            " — пересоздаю туннель"
                    )
                    teardown()
                    // Слот только что был наш: не путаем собственную
                    // закрывающуюся сеть с чужим VPN.
                    startVpn(force = true)
                }
                showNotification()
                return START_STICKY
            }
        }
        // Система перезапустила сервис (intent == null), например при
        // «Постоянной VPN»: не спешим с establish(). Чужой VPN мог
        // подключиться одновременно с нашим перезапуском и ещё не проявился
        // в списке сетей — иначе мы бы его сразу выбили из слота.
        if (intent == null) {
            showNotification()
            if (otherVpnPresent()) {
                beginWaitingForOtherVpn()
                return START_STICKY
            }
            notifyHandler.postDelayed({
                if (running || !AppSettings.isProtectionEnabled()) return@postDelayed
                if (otherVpnPresent()) beginWaitingForOtherVpn() else startVpn()
            }, SYSTEM_RESTART_SETTLE_MS)
            return START_STICKY
        }
        // Обычный запуск (в том числе из MainActivity) отменяет ожидание:
        // пользователь сам решил вернуть защиту.
        if (awaitingOtherVpn) stopWaitingForOtherVpn()
        // startForeground до establish(): если create вернёт null, сервис можно
        // остановить без ForegroundServiceDidNotStartInTimeException.
        showNotification()
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopWaitingForOtherVpn()
        notifyHandler.removeCallbacks(pauseEndRunnable)
        BlockStats.flush()
        teardown()
        destroyNativeContext()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke: VPN отозван системой")
        // onRevoke приходит в двух случаях:
        //  - другое приложение подняло свой VPN и забрало единственный слот
        //    (согласие на наш VPN при этом сохраняется);
        //  - пользователь сам отозвал согласие, выключив VPN.
        // В первом случае ждём освобождения слота и включаемся снова, во
        // втором VpnService.prepare() вернёт Intent, и ожидание сразу завершится.
        val shouldResume = AppSettings.isProtectionEnabled()
        teardown()
        if (shouldResume) {
            // Если VPN-сети не видно, это могло быть не вытеснение чужим VPN,
            // а ручной отзыв согласия в настройках Android. Проверяем сразу,
            // чтобы не показывать ложное «Другой VPN активен».
            if (!otherVpnPresent() && VpnService.prepare(this) != null) {
                Log.i(TAG, "Согласие на VPN отозвано — защита выключена")
                AppSettings.setProtectionEnabled(false)
                AppSettings.setAutostartResult(AppSettings.AUTOSTART_NO_CONSENT)
                stopSelf()
                super.onRevoke()
                return
            }
            // Не вызываем super: его реализация делает stopSelf(), а сервис
            // должен остаться жив, чтобы вернуть защиту после чужого VPN.
            notifyHandler.post { beginWaitingForOtherVpn() }
        } else {
            super.onRevoke()
        }
    }

    // ————————————————————— включение/выключение —————————————————————

    private fun startVpn(keepAliveOnFailure: Boolean = false, force: Boolean = false): Boolean =
        synchronized(lifecycleLock) {
            if (running) {
                Log.i(TAG, "startVpn пропущен: уже работает")
                return@synchronized true
            }
            // Никогда не отбираем слот у другого VPN без явной команды
            // пользователя: establish() мгновенно отключает чужой туннель.
            if (!force && otherVpnPresent()) {
                Log.i(TAG, "Запуск отложен: активен другой VPN")
                if (!awaitingOtherVpn) {
                    notifyHandler.post { beginWaitingForOtherVpn() }
                }
                return@synchronized false
            }
            val tun = buildTunnel()
            if (tun == null) {
                Log.e(TAG, "Не удалось создать туннель (нет согласия на VPN?)")
                if (autostartRequested) {
                    AppSettings.setAutostartResult(AppSettings.AUTOSTART_ERROR)
                    autostartRequested = false
                }
                if (!keepAliveOnFailure) stopSelf()
                return@synchronized false
            }
            tunnel = tun
            tunnelWriter = tun.fileDescriptor
            running = true
            isRunning = true
            if (autostartRequested) {
                AppSettings.setAutostartResult(AppSettings.AUTOSTART_STARTED)
                autostartRequested = false
            }
            blockedCount.set(0)
            dnsCache.clear()
            rebuildUidCache()
            if (appliedMode == AppSettings.MODE_TUNNEL) {
                // Весь трафик обслуживает нативный userspace-стек: он сам
                // определяет UID соединения и решает, пропускать ли поток.
                startNativeStack(tun)
            } else {
                refreshSystemDns()
                startNetworkMonitor()
                relayPool = Executors.newFixedThreadPool(8)
                if (ATTRIBUTION_DIAGNOSTICS) {
                    diagnosticsPool = Executors.newSingleThreadExecutor()
                    Log.i(TAG, "Диагностика атрибуции ВКЛЮЧЕНА")
                }
            }
            Log.i(TAG, "VPN запущен: режим=$appliedMode fd=${tun.fileDescriptor}")

            // обновляем список блокировки в фоне (не задерживая запуск туннеля)
            Thread {
                BlocklistRepository.refreshIfStale(applicationContext)
            }.apply {
                name = "AntiTrack-Blocklist"
                isDaemon = true
            }.start()

            // и дальше проверяем его раз в час, пока сервис работает
            notifyHandler.removeCallbacks(blocklistCheckRunnable)
            notifyHandler.postDelayed(blocklistCheckRunnable, BLOCKLIST_CHECK_INTERVAL_MS)

            if (appliedMode == AppSettings.MODE_DNS) {
                // свежий поток на каждый запуск, чтобы повторный старт не упал
                // с IllegalThreadStateException
                Thread(::tunnelLoop).apply {
                    name = "AntiTrack-Tunnel"
                    isDaemon = true
                }.start()

                Thread(::trafficWatcherLoop).apply {
                    name = "AntiTrack-TrafficWatcher"
                    isDaemon = true
                }.start()
            }

            true
        }

    private fun buildTunnel(): ParcelFileDescriptor? {
        val configureIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        appliedMode = AppSettings.blockMode()
        // Если нативный стек недоступен (например, в APK нет библиотеки под
        // ABI устройства), остаёмся с защитой: переключаемся на DNS-режим.
        tunnelUnavailable = false
        if (appliedMode == AppSettings.MODE_TUNNEL && !ensureNativeLoaded()) {
            Log.e(TAG, "Полный туннель недоступен — запускаю DNS-режим")
            appliedMode = AppSettings.MODE_DNS
            tunnelUnavailable = true
        }
        val builder = Builder()
            .setSession(getString(R.string.vpn_session_name))
            .setConfigureIntent(configureIntent)

        if (appliedMode == AppSettings.MODE_TUNNEL) {
            // Полный туннель: весь трафик проходит через userspace-стек,
            // поэтому блокировать можно и по IP. MTU задаёт нативный стек
            // (10 000, как в NetGuard).
            val mtu = runCatching { jniGetMtu() }.getOrDefault(TUNNEL_MTU)
            builder.setMtu(mtu)
                .addAddress(TUN_IP, 32)
                .addAddress(TUN_IP6, 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("2000::", 3) // глобальный unicast IPv6

            // Если выбран конкретный резолвер, сообщаем его системе. Для
            // «системного» ничего не добавляем: Android возьмёт DNS текущей
            // сети, а нативный стек всё равно перехватит и разберёт ответы.
            for (ip in tunnelDnsServers()) {
                try {
                    builder.addDnsServer(ip)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Некорректный DNS-сервер: $ip", e)
                }
            }
        } else {
            builder.setMtu(MTU)
                .addAddress(TUN_IP, TUN_PREFIX)
                .addRoute(DNS_IP, DNS_ROUTE_PREFIX)
                .addDnsServer(DNS_IP)
                .addAddress(TUN_IP6, TUN_PREFIX6)
                .addRoute(DNS_IP6, DNS_ROUTE_PREFIX6)
                .addDnsServer(DNS_IP6)
            // DoH/DoT не должен обходить фильтр: TCP и UDP к этим адресам уводятся
            // в туннель, TCP там гаснет, а UDP:53 проходит наш DNS-фильтр.
            for (ip in bypassDnsIps) builder.addRoute(ip, 32)
            for (ip in bypassDnsIps6) builder.addRoute(ip, 128)
        }

        // Исключённые приложения выпускаем из VPN целиком. Своё приложение тоже
        // исключаем: в полном туннеле иначе его собственные сокеты (обновление
        // списков, DNS нативного стека) уходили бы обратно в туннель.
        val excluded = excludedPackages()
        for (pkg in excluded) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Исключённое приложение не установлено: $pkg")
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "Пакет нельзя исключить из VPN: $pkg", e)
            }
        }
        appliedExcludedPackages = excluded

        return builder.establish()
    }

    /**
     * DNS-серверы для режима полного туннеля: выбранный пользователем
     * резолвер либо ничего для «системного».
     */
    private fun tunnelDnsServers(): List<String> {
        val provider = DnsProviders.byId(AppSettings.dnsProviderId())
        if (provider.id == DnsProviders.SYSTEM_ID) return emptyList()
        if (provider.custom) {
            return AppSettings.customDnsServers().filter { isNumericAddress(it) }.take(4)
        }
        return (provider.ipv4.take(2) + provider.ipv6.take(2))
    }

    /** Приложения, которые не должны проходить через VPN. */
    private fun excludedPackages(): Set<String> =
        exemptPackages + PerAppSettings.disabledTrackerPackages()

    // ———————————————————— полный туннель: нативный стек ————————————————————

    /**
     * Загружает userspace-стек NetGuard. Библиотека лежит в APK под именем
     * `libantitrack_tunnel.so`; без неё режим полного туннеля недоступен, но
     * DNS-режим продолжает работать.
     */
    private fun ensureNativeLoaded(): Boolean {
        if (nativeLoaded) return true
        return try {
            System.loadLibrary("antitrack_tunnel")
            nativeLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Не удалось загрузить нативный стек туннеля", e)
            false
        }
    }

    private fun ensureNativeContext(): Long {
        if (!ensureNativeLoaded()) return 0L
        if (nativeContext == 0L) {
            nativeContext = try {
                jniInit(Build.VERSION.SDK_INT)
            } catch (e: Throwable) {
                Log.e(TAG, "Не удалось создать контекст нативного стека", e)
                0L
            }
        }
        return nativeContext
    }

    /** Запускает обработку пакетов в нативном потоке (он блокирующий). */
    private fun startNativeStack(tun: ParcelFileDescriptor) {
        val ctx = ensureNativeContext()
        if (ctx == 0L) {
            Log.e(TAG, "Полный туннель недоступен: нативный стек не загрузился")
            notifyHandler.post {
                if (running) {
                    teardown()
                    stopSelf()
                }
            }
            return
        }
        try {
            jniStart(ctx, android.util.Log.WARN)
        } catch (e: Throwable) {
            Log.e(TAG, "Не удалось запустить нативный стек", e)
            return
        }
        val thread = Thread({
            Log.i(TAG, "Нативный стек запущен")
            try {
                // fwd53=false: DNS не перенаправляем на прокси, а пропускаем
                // через защищённый сокет и разбираем ответы в dnsResolved.
                jniRun(ctx, tun.fd, false, TUNNEL_RCODE)
            } catch (e: Throwable) {
                Log.e(TAG, "Нативный стек завершился с ошибкой", e)
            } finally {
                nativeThread = null
                Log.i(TAG, "Нативный стек остановлен")
                if (running) {
                    // Стек завершился сам (не через teardown) — считаем, что
                    // туннель потерян, и действуем как при отзыве VPN.
                    notifyHandler.post {
                        if (running) handleTunnelLoss()
                    }
                }
            }
        }, "AntiTrack-Native")
        thread.isDaemon = true
        nativeThread = thread
        thread.start()
    }

    private fun stopNativeStack() {
        val ctx = nativeContext
        val thread = nativeThread
        if (ctx != 0L) {
            try {
                jniStop(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, "Ошибка остановки нативного стека", e)
            }
        }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(3_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        nativeThread = null
        if (ctx != 0L && (thread == null || !thread.isAlive)) {
            try {
                jniClear(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, "Ошибка очистки нативного стека", e)
            }
        }
        tunnelDns.clear()
    }

    private fun destroyNativeContext() {
        val ctx = nativeContext
        nativeContext = 0L
        if (ctx != 0L) {
            try {
                jniDone(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, "Ошибка освобождения нативного контекста", e)
            }
        }
    }

    // ———————— JNI-колбэки userspace-стека (вызываются из native) ————————

    /** Владелец сокета по данным netd. Вызывается для каждого нового потока. */
    @Keep
    fun getUidQ(
        version: Int,
        protocol: Int,
        saddr: String?,
        sport: Int,
        daddr: String?,
        dport: Int
    ): Int {
        if (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP) {
            return Process.INVALID_UID
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Process.INVALID_UID
        if (saddr.isNullOrBlank() || daddr.isNullOrBlank()) return Process.INVALID_UID
        val cm = getSystemService(ConnectivityManager::class.java) ?: return Process.INVALID_UID
        return try {
            val local = InetSocketAddress(InetAddress.getByName(saddr), sport)
            val remote = InetSocketAddress(InetAddress.getByName(daddr), dport)
            cm.getConnectionOwnerUid(protocol, local, remote)
        } catch (e: Exception) {
            Process.INVALID_UID
        }
    }

    /**
     * Проверка имени из SNI. В туннельном режиме атрибуция делается по IP и
     * UID, поэтому здесь всегда `false`: блокировка по SNI не нужна и только
     * мешала бы точной статистике.
     */
    @Keep
    fun isDomainBlocked(name: String?): Boolean = false

    /**
     * Блокировка по имени из DNS-ответа в полном туннеле. Сюда нативный стек
     * передаёт UID запросившего приложения, поэтому учитываются per-app
     * настройки, а статистика пишется сразу. При `true` native подменяет ответ
     * на NXDOMAIN: приложение не получает IP и не открывает соединение — тот же
     * барьер, что и в режиме «Только DNS», но без потери точной атрибуции.
     */
    @Keep
    fun isDnsBlocked(name: String?, uid: Int, qname: String?): Boolean {
        if (name.isNullOrBlank() || AppSettings.isPaused()) return false
        val host = name.lowercase().trim().trimEnd('.')
        if (host.isEmpty()) return false

        val requested = qname?.lowercase()?.trim()?.trimEnd('.')
        // Явное разрешение исходного имени сильнее CNAME-цели: пользователь
        // разрешил сайт, а его A-запись пришла на сторонний CDN/трекер.
        if (requested != null && requested != host && HostRules.isAllowed(requested)) return false

        val app = if (uid >= 10_000) packageNameForUid(uid) else null
        if (app != null && app in exemptPackages) return false
        if (HostRules.isAllowed(host)) return false

        val custom = HostRules.isBlocked(host)
        // Per-app переключатель относится только к трекерам: рекламу глушим
        // всегда, как и в DNS-режиме.
        val tracker = if (AppSettings.blockTrackers() &&
            (app == null || PerAppSettings.isTrackerBlockingEnabled(app))
        ) TrackerBlocklist.findMatch(host) else null
        val ads = if (AppSettings.blockAds()) AdBlocklist.findMatch(host) else null
        if (!custom && tracker == null && ads == null) return false

        val owner = app ?: BlockStats.OTHER
        val kind: BlockStats.Kind
        val status: RequestLog.Status
        val pattern: String?
        when {
            custom -> {
                kind = BlockStats.Kind.TRACKER
                status = RequestLog.Status.CUSTOM_BLOCKED
                pattern = null
            }
            tracker != null -> {
                kind = BlockStats.Kind.TRACKER
                status = RequestLog.Status.TRACKER
                pattern = tracker
            }
            else -> {
                kind = BlockStats.Kind.ADS
                status = RequestLog.Status.ADS
                pattern = ads
            }
        }

        blockedCount.incrementAndGet()
        BlockStats.record(owner, host, kind)
        if (kind == BlockStats.Kind.TRACKER) applyThrottle(owner)
        if (RequestLog.isEnabled) {
            RequestLog.record(
                owner,
                host,
                status,
                null,
                null,
                pattern,
                if (uid <= 0) RequestLog.Reason.UID_UNKNOWN else null
            )
        }
        Log.d(TAG, "Туннель: DNS $host заблокирован ($kind) для $owner (uid $uid)")
        return true
    }

    /**
     * Решение по конкретному потоку. Native отдаёт точный UID соединения и
     * адресата; домен берём из таблицы DNS-ответов. Возврат `null` — блокировка,
     * [Allowed] — пропуск.
     */
    @Keep
    fun isAddressAllowed(packet: Packet): Allowed? {
        // Native не всегда может назвать владельца сокета: например, для
        // непривязанного UDP-сокета getConnectionOwnerUid возвращает
        // INVALID_UID. Второй шанс — таблица сокетов в /proc/net по локальному
        // порту. Если и она не помогла, запрос честно уходит в «Другое».
        val uid = if (packet.uid > 0) packet.uid else uidForSocket(packet)
        if (uid <= 0) {
            Log.d(
                TAG,
                "Туннель: UID не определён ${packet.saddr}:${packet.sport} -> " +
                    "${packet.daddr}:${packet.dport} (p${packet.protocol}) — «Другое»"
            )
        }
        val app = if (uid > 0) packageNameForUid(uid) else BlockStats.OTHER
        val ip = packet.daddr

        // Своё приложение и явно исключённые пакеты не фильтруем.
        if (uid == Process.myUid() || app in exemptPackages) return Allowed()
        if (AppSettings.isPaused()) return Allowed()
        if (uid > 0 && !PerAppSettings.isTrackerBlockingEnabled(app)) return Allowed()

        // Троттлинг: приложение недавно активно слало трекеры — на время
        // отключаем ему весь трафик, включая DNS.
        if (activeThrottles > 0 && isThrottled(app)) {
            if (RequestLog.isEnabled) {
                RequestLog.record(app, ip ?: "", RequestLog.Status.THROTTLE, null, ip, null)
            }
            return null
        }

        // DNS пропускаем всегда: его ответы наполняют таблицу IP → домен.
        if (packet.dport == DNS_PORT) return Allowed()
        if (ip.isNullOrBlank()) return Allowed()

        // SNI из TLS ClientHello — точное имя соединения. Если он есть,
        // используем только его, а не все домены, которые делят этот IP.
        val sni = sniOf(packet)
        // Явное разрешение хоста сильнее CNAME-цели из таблицы.
        if (sni != null && HostRules.isAllowed(sni)) return Allowed()
        val hosts = tunnelDns.hostsFor(ip, sni)
        if (hosts.isEmpty()) return Allowed()

        val blockTrackers = AppSettings.blockTrackers()
        val blockAds = AppSettings.blockAds()
        var trackerHit: String? = null
        var adsHit: String? = null
        val blockedHosts = HashSet<String>()

        for (host in hosts) {
            if (HostRules.isAllowed(host)) continue
            if (HostRules.isBlocked(host)) {
                blockedHosts.add(host)
                if (trackerHit == null) trackerHit = host
                continue
            }
            if (blockTrackers && TrackerBlocklist.findMatch(host) != null) {
                blockedHosts.add(host)
                if (trackerHit == null) trackerHit = host
                continue
            }
            if (blockAds && AdBlocklist.findMatch(host) != null) {
                blockedHosts.add(host)
                if (adsHit == null) adsHit = host
            }
        }

        if (blockedHosts.isEmpty()) return Allowed()

        // Общий IP: рядом с трекером живут обычные домены. По умолчанию
        // пропускаем такой поток, иначе ломаются CDN и крупные сервисы.
        // Но если нативный стек уже распознал SNI, домен точный — правило
        // общих IP к нему не применяем.
        if (sni == null && blockedHosts.size < hosts.size && !AppSettings.tunnelStrictIp()) {
            return Allowed()
        }

        val host = trackerHit ?: adsHit ?: return Allowed()
        val kind = if (trackerHit != null) BlockStats.Kind.TRACKER else BlockStats.Kind.ADS
        blockedCount.incrementAndGet()
        // В туннеле UID точный: пишем ровно то приложение, которому
        // принадлежит соединение. База связей здесь не подменяет владельца —
        // иначе «точное определение» перестаёт быть точным. Незнакомую пару
        // UI помечает звёздочкой и показывает сырой домен.
        BlockStats.record(app, host, kind)
        if (kind == BlockStats.Kind.TRACKER) applyThrottle(app)
        if (RequestLog.isEnabled) {
            RequestLog.record(
                app,
                host,
                if (kind == BlockStats.Kind.TRACKER) RequestLog.Status.TRACKER else RequestLog.Status.ADS,
                null,
                ip,
                host,
                if (uid <= 0) RequestLog.Reason.UID_UNKNOWN else null
            )
        }
        Log.d(TAG, "Туннель: заблокирован $host ($kind) для $app ($ip)")
        return null
    }

    /**
     * SNI из TLS ClientHello, который нативный стек положил в [Packet.data].
     * Для ICMP там лежит «type X/Y», для обычных пакетов — пусто. SNI без
     * точки не считаем доменом: списки всё равно работают по FQDN.
     */
    private fun sniOf(packet: Packet): String? =
        packet.data?.trim()?.takeIf {
            it.isNotEmpty() && !it.startsWith("type ") && !it.contains(' ') && it.contains('.')
        }

    /** Ответ DNS (или SNI), разобранный нативным стеком: IP → домен. */
    @Keep
    fun dnsResolved(rr: ResourceRecord) {
        val ip = rr.Resource
        val qname = rr.QName
        if (ip.isNullOrBlank() || qname.isNullOrBlank()) return
        // TTL = -1 приходит для SNI: срок жизни такого соответствия неизвестен,
        // но соединение реальное, поэтому держим запись несколько минут.
        val ttl = if (rr.TTL > 0) rr.TTL else SNI_MAPPING_TTL_SEC
        val time = if (rr.Time > 0) rr.Time else System.currentTimeMillis()
        tunnelDns.record(ip, qname, rr.AName, time, ttl)
    }

    /** Native просит залогировать пакет (используется только для pcap). */
    @Keep
    fun logPacket(packet: Packet) = Unit

    @Keep
    fun accountUsage(usage: Usage) = Unit

    @Keep
    fun nativeExit(reason: String?) {
        Log.w(TAG, "Нативный стек сообщил о завершении: $reason")
    }

    @Keep
    fun nativeError(error: Int, message: String?) {
        Log.w(TAG, "Ошибка нативного стека $error: $message")
    }

    private fun teardown() {
        synchronized(lifecycleLock) {
            if (tunnel == null && !running) {
                Log.i(TAG, "teardown пропущен: уже остановлен")
                return
            }
            Log.i(TAG, "Остановка VPN (running=$running)")
            running = false
            isRunning = false
            notifyHandler.removeCallbacks(blocklistCheckRunnable)
            stopNetworkMonitor()
            // Закрытие DoT-соединения берёт блокировку и может ждать
            // незавершённый запрос, поэтому не делаем это на main thread.
            val oldUpstream = dnsUpstream
            dnsUpstream = null
            if (oldUpstream != null) {
                Thread({ oldUpstream.close() }, "AntiTrack-DnsClose").apply {
                    isDaemon = true
                }.start()
            }
            appliedDnsProviderId = null
            lastGoodDns = null
            relayPool?.shutdownNow()
            relayPool = null
            diagnosticsPool?.shutdownNow()
            diagnosticsPool = null
            dnsCache.clear()
            // Нативный стек должен отпустить сессии и сокеты до закрытия
            // tуннеля, иначе его epoll останется на закрытом дескрипторе.
            stopNativeStack()
            try {
                tunnel?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Ошибка при закрытии туннеля", e)
            }
            tunnel = null
            tunnelWriter = null
            Log.i(TAG, "VPN остановлен")
            QuickTileService.notifyStateChanged(this)
        }
    }

    // ————————————————————— возврат после другого VPN —————————————————————

    /**
     * Держит сервис в ожидании, пока другое VPN-приложение освободит слот.
     * Мы не можем «жить» одновременно с ним: Android разрешает только один
     * активный VPN. Зато согласие на наш VPN при этом сохраняется, поэтому
     * после отключения чужого можно снова вызвать establish().
     */
    private fun beginWaitingForOtherVpn() {
        if (awaitingOtherVpn) return
        awaitingOtherVpn = true
        isWaitingForOtherVpn = true
        resumeAttempts = 0
        // Таймер запускаем не от момента onRevoke, а от момента, когда чужой
        // VPN реально исчез: нередко он подключается несколько секунд, и
        // ранний establish() просто выбил бы его из слота.
        sawOtherVpn = false
        noVpnSinceMs = 0L
        waitStartedMs = System.currentTimeMillis()
        Log.i(TAG, "Ждём освобождения слота VPN (другой VPN активен)")
        if (isAlwaysOnVpn()) {
            // При «Постоянной VPN» систему сама перезапускает наш сервис и
            // вытесняет чужой туннель — это ограничение Android, а не наш
            // таймер. Пишем в лог, чтобы причина была видна.
            Log.w(TAG, "AntiTrack назначен постоянной VPN: другие VPN будут вытесняться системой")
        }
        registerResumeWatcher()
        showNotification()
        notifyHandler.removeCallbacks(resumePollRunnable)
        notifyHandler.postDelayed(resumePollRunnable, RESUME_POLL_MS)
    }

    /** Назначено ли наше приложение «Постоянной VPN» в настройках Android. */
    private fun isAlwaysOnVpn(): Boolean = try {
        // Константа Settings.Secure.ALWAYS_ON_VPN_APP скрыта в публичном SDK.
        android.provider.Settings.Secure.getString(
            contentResolver,
            "always_on_vpn_app"
        ) == packageName
    } catch (e: Exception) {
        false
    }

    /**
     * Одна проверка состояния. Вызывается по таймеру и по событиям сети.
     * Возврат защиты делаем только когда чужого VPN нет достаточно долго,
     * иначе можно перехватить его в момент запуска.
     */
    private fun checkOtherVpnState() {
        if (!awaitingOtherVpn) return
        if (!AppSettings.isProtectionEnabled() ||
            (!AppSettings.blockTrackers() && !AppSettings.blockAds())
        ) {
            stopWaitingForOtherVpn()
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        if (otherVpnPresent()) {
            // Чужой VPN на месте — ничего не делаем. prepare() в этот момент
            // не вызываем, чтобы не вмешиваться в его работу.
            if (!sawOtherVpn) Log.i(TAG, "Другой VPN подключён — ждём его отключения")
            sawOtherVpn = true
            noVpnSinceMs = 0L
            return
        }

        if (sawOtherVpn) {
            // Чужой VPN был и пропал. Ждём: он может переподключаться.
            if (noVpnSinceMs == 0L) {
                noVpnSinceMs = now
                Log.i(TAG, "Другой VPN отключился — проверяем через ${RESUME_GRACE_MS / 1000} с")
                return
            }
            if (now - noVpnSinceMs < RESUME_GRACE_MS) return
        } else {
            // Чужой VPN так и не появился: возможно, приложение не смогло
            // подключиться. Ждём с большим запасом, чтобы не украсть слот у
            // медленно устанавливающегося туннеля.
            if (now - waitStartedMs < RESUME_UNSEEN_MS) return
            Log.i(TAG, "Другой VPN так и не появился — возвращаем защиту")
        }

        // Пользователь отозвал согласие (выключил VPN в системных настройках).
        // Без нового диалога согласия включиться нельзя — прекращаем ожидание.
        if (VpnService.prepare(this) != null) {
            Log.i(TAG, "Согласие на VPN отозвано — автоматический возврат отменён")
            AppSettings.setProtectionEnabled(false)
            AppSettings.setAutostartResult(AppSettings.AUTOSTART_NO_CONSENT)
            stopWaitingForOtherVpn()
            stopSelf()
            return
        }

        resumeProtection()
    }

    /** Поднимает туннель заново; при неудаче возвращает сервис в ожидание. */
    private fun resumeProtection() {
        awaitingOtherVpn = false
        notifyHandler.removeCallbacks(resumePollRunnable)
        unregisterResumeWatcher()
        Log.i(TAG, "Слот свободен — возвращаю защиту")
        Thread {
            // Ещё раз проверяем прямо перед establish(): чужой VPN мог
            // подключиться между проверкой и этим моментом.
            if (otherVpnPresent()) {
                Log.i(TAG, "Перед запуском снова обнаружен чужой VPN — продолжаем ждать")
                notifyHandler.post {
                    resumeAttempts = 0
                    beginWaitingForOtherVpn()
                }
                return@Thread
            }
            val started = startVpn(keepAliveOnFailure = true)
            notifyHandler.post {
                when {
                    started -> {
                        resumeAttempts = 0
                        isWaitingForOtherVpn = false
                        showNotification()
                    }
                    !AppSettings.isProtectionEnabled() -> stopSelf()
                    otherVpnPresent() -> {
                        // Слот снова заняли — возвращаемся в ожидание,
                        // не расходуя попытки.
                        resumeAttempts = 0
                        beginWaitingForOtherVpn()
                    }
                    resumeAttempts + 1 >= RESUME_MAX_ATTEMPTS -> {
                        Log.w(TAG, "Не удалось вернуть защиту после другого VPN")
                        isWaitingForOtherVpn = false
                        stopSelf()
                    }
                    else -> {
                        resumeAttempts++
                        awaitingOtherVpn = true
                        isWaitingForOtherVpn = true
                        sawOtherVpn = false
                        noVpnSinceMs = 0L
                        waitStartedMs = System.currentTimeMillis()
                        registerResumeWatcher()
                        showNotification()
                        notifyHandler.postDelayed(resumePollRunnable, RESUME_RETRY_MS)
                    }
                }
            }
        }.apply {
            name = "AntiTrack-Resume"
            isDaemon = true
        }.start()
    }

    /**
     * true, если сейчас активен хоть какой-то VPN. Наш собственный в момент
     * ожидания уже закрыт, поэтому любая VPN-сеть — чужая.
     *
     * Используем три источника: замеченные подпиской сети, активную сеть и
     * общий список. На разных версиях Android чужая VPN видна по-разному.
     */
    private fun otherVpnPresent(): Boolean {
        if (vpnNetworks.isNotEmpty()) return true
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        ) {
            return true
        }
        return try {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось перечислить сети при ожидании VPN", e)
            false
        }
    }

    /** Запоминает/забывает VPN-сеть, чтобы не пропустить чужой туннель. */
    private fun noteVpnNetwork(network: Network, present: Boolean) {
        if (present) {
            if (vpnNetworks.add(network)) {
                Log.i(TAG, "Замечена VPN-сеть другого приложения")
            }
        } else {
            vpnNetworks.remove(network)
        }
    }

    private fun isVpnNetwork(network: Network): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Подписка на появление/исчезновение VPN и смену обычной сети. */
    private fun registerResumeWatcher() {
        if (resumeVpnWatcher != null || resumeDefaultWatcher != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        val vpnCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                noteVpnNetwork(network, present = true)
                notifyHandler.post { checkOtherVpnState() }
            }

            override fun onLost(network: Network) {
                noteVpnNetwork(network, present = false)
                notifyHandler.post { checkOtherVpnState() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                noteVpnNetwork(
                    network,
                    present = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                )
                notifyHandler.post { checkOtherVpnState() }
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .apply {
                    // API 31+: видеть VPN-сети, которые не применяются к нам
                    // (чужой VPN с раздельным туннелированием).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { setIncludeOtherUidNetworks(true) }
                    }
                }
                .build()
            cm.registerNetworkCallback(request, vpnCallback)
            resumeVpnWatcher = vpnCallback
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на VPN-сети", e)
        }

        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isVpnNetwork(network)) noteVpnNetwork(network, present = true)
                notifyHandler.post { checkOtherVpnState() }
            }

            override fun onLost(network: Network) {
                noteVpnNetwork(network, present = false)
                notifyHandler.post { checkOtherVpnState() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                noteVpnNetwork(
                    network,
                    present = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                )
                notifyHandler.post { checkOtherVpnState() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(defaultCallback)
            resumeDefaultWatcher = defaultCallback
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на смену сети", e)
        }
    }

    private fun unregisterResumeWatcher() {
        val cm = getSystemService(ConnectivityManager::class.java)
        resumeVpnWatcher?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        resumeVpnWatcher = null
        resumeDefaultWatcher?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        resumeDefaultWatcher = null
        vpnNetworks.clear()
    }

    private fun stopWaitingForOtherVpn() {
        awaitingOtherVpn = false
        isWaitingForOtherVpn = false
        notifyHandler.removeCallbacks(resumePollRunnable)
        unregisterResumeWatcher()
    }

    // ————————————————————— системный DNS и upstream —————————————————————

    /**
     * Собирает DNS-серверы физической сети. `getUnderlyingNetworks()` в
     * публичном API VpnService нет, поэтому берём все не-VPN сети с доступом
     * в интернет. Адреса собственного виртуального DNS отфильтровываются,
     * чтобы не получить петлю «запрос самому себе».
     */
    private fun refreshSystemDns() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val result = LinkedHashSet<String>()

        fun collect(network: Network?, validatedOnly: Boolean) {
            if (network == null) return
            val caps = try {
                cm.getNetworkCapabilities(network)
            } catch (e: Exception) {
                null
            }
            if (caps == null ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                (validatedOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            ) {
                return
            }
            val linkProperties = try {
                cm.getLinkProperties(network)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось получить LinkProperties", e)
                null
            } ?: return
            for (address in linkProperties.dnsServers) {
                val host = address.hostAddress ?: continue
                if (host == DNS_IP || host == DNS_IP6) continue
                result.add(host)
            }
        }

        try {
            val networks = cm.allNetworks
            // Сначала проверенные сети: при одновременном Wi-Fi и мобильном
            // интернете это не даст взять DNS неиспользуемой сети.
            networks.forEach { collect(it, validatedOnly = true) }
            if (result.isEmpty()) networks.forEach { collect(it, validatedOnly = false) }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось перечислить сети", e)
        }
        if (result.isEmpty()) collect(cm.activeNetwork, validatedOnly = false)
        systemDnsServers = result.toList()
        Log.d(TAG, "Системные DNS: ${systemDnsServers.ifEmpty { listOf(FALLBACK_DNS_IPV4) }}")
    }

    private fun startNetworkMonitor() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshSystemDns()
            override fun onLost(network: Network) = refreshSystemDns()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                refreshSystemDns()
        }
        try {
            // Слушаем все интернет-сети, а не только default: при активном VPN
            // default — это наш туннель, и смена Wi-Fi может не дать события.
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на смену сети", e)
        }
    }

    private fun stopNetworkMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось отписаться от смены сети", e)
        }
    }

    private fun upstream(): DnsUpstream {
        dnsUpstream?.let { return it }
        synchronized(this) {
            dnsUpstream?.let { return it }
            return DnsUpstream(
                protectDatagram = { socket -> protect(socket) },
                protectSocket = { socket -> protect(socket) }
            ).also { dnsUpstream = it }
        }
    }

    /**
     * Список UDP-серверов для выбранного режима. Для «Системного» сначала идут
     * DNS текущей сети, затем запасной резолвер; для остальных — адреса
     * провайдера. Пользовательские адреса проверяются на корректность.
     */
    private fun upstreamServers(provider: DnsProvider): List<String> {
        val servers = when {
            provider.id == DnsProviders.SYSTEM_ID ->
                systemDnsServers.take(2) + FALLBACK_DNS_IPV4
            provider.custom ->
                AppSettings.customDnsServers()
            else ->
                provider.ipv4 + provider.ipv6
        }
        val validated = servers.filter { isNumericAddress(it) }.distinct()
        return if (validated.isEmpty() && provider.id == DnsProviders.CUSTOM_ID) {
            systemDnsServers.take(2) + FALLBACK_DNS_IPV4
        } else {
            // Больше четырёх попыток на один запрос — это уже несколько секунд
            // ожидания при недоступной сети; клиент всё равно повторит запрос.
            validated.take(4)
        }
    }

    /** Проверка, что строка — числовой IP, а не имя (иначе получим DNS-петлю). */
    private fun isNumericAddress(value: String): Boolean {
        val host = value.substringBefore('%')
        if (host.isEmpty()) return false
        val isIpv4 = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) &&
            host.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        val isIpv6 = host.contains(':') && host.matches(Regex("""[0-9a-fA-F:]+""")) &&
            runCatching { InetAddress.getByName(host) }.getOrNull() != null
        return isIpv4 || isIpv6
    }

    // ————————————————————— цикл обработки пакетов —————————————————————

    private fun tunnelLoop() {
        val fd = tunnel?.fileDescriptor ?: return
        val packet = ByteArray(MTU)
        while (running) {
            try {
                val length = Os.read(fd, packet, 0, MTU)
                if (length <= 0) break
                handlePacket(packet, length)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) {
                    // fd туннеля неблокирующий: пакетов пока нет — ждём и читаем снова
                    try {
                        Thread.sleep(10)
                    } catch (ie: InterruptedException) {
                        break
                    }
                    continue
                }
                if (running) Log.e(TAG, "Ошибка чтения туннеля", e)
                break
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Ошибка чтения туннеля", e)
                break
            }
        }
        // Чтение завершилось не по нашей команде (например, система отдала VPN
        // другому приложению): ждём его отключения и возвращаем защиту.
        if (running) handleTunnelLoss()
    }

    /**
     * Туннель закрылся сам, хотя сервис не останавливали. Это происходит,
     * когда слот VPN забирает другое приложение. Действуем так же, как в
     * [onRevoke], — на случай, если система не прислала onRevoke.
     */
    private fun handleTunnelLoss() {
        if (!running) return
        val shouldResume = AppSettings.isProtectionEnabled()
        Log.w(TAG, "Туннель закрыт системой")
        teardown()
        if (shouldResume) {
            notifyHandler.post { beginWaitingForOtherVpn() }
        } else {
            stopSelf()
        }
    }

    private fun handlePacket(packet: ByteArray, len: Int) {
        val version = (packet[0].toInt() and 0xFF) shr 4
        when (version) {
            4 -> handleIpv4Packet(packet, len)
            6 -> handleIpv6Packet(packet, len)
        }
    }

    /** Разбор IPv4+UDP: в туннель попадают только DNS-пакеты (порт 53). */
    private fun handleIpv4Packet(packet: ByteArray, len: Int) {
        val vhl = packet[0].toInt() and 0xFF
        val ihl = (vhl and 0x0F) * 4
        if (ihl < 20 || len < ihl + 20) return
        val protocol = packet[9].toInt() and 0xFF
        if (protocol == 6) {
            handleIpv4Tcp(packet, len, ihl)
            return
        }
        if (protocol != 17) return // в туннель попадают только DNS-пакеты
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = read16(packet, ihl)
        val dstPort = read16(packet, ihl + 2)
        if (dstPort != DNS_PORT) return
        handleDnsPacket(packet, ihl + 8, len, srcIp, dstIp, srcPort)
    }

    /**
     * Разбор IPv6+UDP. Расширения заголовка (фрагментация, хоп-бай-хоп)
     * не поддерживаются: DNS-запросы уходят без них, а всё остальное
     * в туннель не маршрутизируется.
     */
    private fun handleIpv6Packet(packet: ByteArray, len: Int) {
        if (len < 48) return // 40 байт IPv6 + 8 байт UDP
        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader == 6) {
            handleIpv6Tcp(packet, len)
            return
        }
        if (nextHeader != 17) return
        val srcIp = packet.copyOfRange(8, 24)
        val dstIp = packet.copyOfRange(24, 40)
        val srcPort = read16(packet, 40)
        val dstPort = read16(packet, 42)
        if (dstPort != DNS_PORT) return
        handleDnsPacket(packet, 48, len, srcIp, dstIp, srcPort)
    }

    /**
     * TCP попадает в туннель только на адрес виртуального DNS: маршрут /32
     * покрывает все протоколы. Ретрансляция TCP DNS без userspace-стека
     * невозможна, поэтому сразу отвечаем RST: клиент уходит на другой
     * резолвер или завершает запрос, а не ждёт таймаут.
     */
    private fun handleIpv4Tcp(packet: ByteArray, len: Int, ihl: Int) {
        if (len < ihl + 20) return
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = read16(packet, ihl)
        val dstPort = read16(packet, ihl + 2)
        if (dstPort != DNS_PORT) return
        val dataOffset = ((packet[ihl + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOffset < 20 || ihl + dataOffset > len) return
        val flags = packet[ihl + 13].toInt() and 0xFF
        val seq = read32(packet, ihl + 4)
        val ack = read32(packet, ihl + 8)
        sendTcpReset(srcIp, dstIp, srcPort, seq, ack, flags)
    }

    private fun handleIpv6Tcp(packet: ByteArray, len: Int) {
        if (len < 40 + 20) return
        val srcIp = packet.copyOfRange(8, 24)
        val dstIp = packet.copyOfRange(24, 40)
        val srcPort = read16(packet, 40)
        val dstPort = read16(packet, 42)
        if (dstPort != DNS_PORT) return
        val dataOffset = ((packet[52].toInt() and 0xF0) shr 4) * 4
        if (dataOffset < 20 || 40 + dataOffset > len) return
        val flags = packet[53].toInt() and 0xFF
        val seq = read32(packet, 44)
        val ack = read32(packet, 48)
        sendTcpReset(srcIp, dstIp, srcPort, seq, ack, flags)
    }

    /** Собирает RST(+ACK) от виртуального DNS и пишет его в туннель. */
    private fun sendTcpReset(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        incomingSeq: Int,
        incomingAck: Int,
        incomingFlags: Int
    ) {
        val syn = incomingFlags and 0x02 != 0
        val seq: Int
        val ack: Int
        val flags: Int
        if (syn) {
            // Ответ на SYN: подтверждаем его и сбрасываем соединение.
            seq = 0
            ack = incomingSeq + 1
            flags = 0x14 // ACK | RST
        } else {
            seq = incomingAck
            ack = 0
            flags = 0x04 // RST
        }

        if (clientIp.size == 16) {
            val total = 40 + 20
            val out = ByteArray(total)
            out[0] = 0x60
            write16(out, 4, 20)                 // payload length = TCP-заголовок
            out[6] = 6                          // next header: TCP
            out[7] = 64
            System.arraycopy(dnsIp, 0, out, 8, 16)
            System.arraycopy(clientIp, 0, out, 24, 16)
            write16(out, 40, DNS_PORT)
            write16(out, 42, clientPort)
            write32(out, 44, seq)
            write32(out, 48, ack)
            out[52] = 0x50                      // data offset = 5 слов
            out[53] = flags.toByte()
            write16(out, 56, transportChecksum(out, 40, 20, ipv6 = true))
            writeTunnelResponse(out)
        } else {
            val total = 20 + 20
            val out = ByteArray(total)
            out[0] = 0x45
            write16(out, 2, total)
            write16(out, 4, total)
            out[8] = 64
            out[9] = 6                          // TCP
            System.arraycopy(dnsIp, 0, out, 12, 4)
            System.arraycopy(clientIp, 0, out, 16, 4)
            write16(out, 20, DNS_PORT)
            write16(out, 22, clientPort)
            write32(out, 24, seq)
            write32(out, 28, ack)
            out[32] = 0x50
            out[33] = flags.toByte()
            write16(out, 34, 0)                 // окно
            write16(out, 10, ipChecksum(out.copyOfRange(0, 20)))
            write16(out, 36, transportChecksum(out, 20, 20, ipv6 = false))
            writeTunnelResponse(out)
        }
    }

    /** Контрольная сумма TCP/UDP по псевдозаголовку IPv4/IPv6. */
    private fun transportChecksum(
        packet: ByteArray,
        transportOffset: Int,
        transportLen: Int,
        ipv6: Boolean
    ): Int {
        val pseudoLen = if (ipv6) 40 else 12
        val buf = ByteArray(pseudoLen + transportLen)
        if (ipv6) {
            System.arraycopy(packet, 8, buf, 0, 32)
            write32(buf, 32, transportLen)
            buf[39] = 6
        } else {
            System.arraycopy(packet, 12, buf, 0, 8)
            buf[8] = 0
            buf[9] = 6
            write16(buf, 10, transportLen)
        }
        System.arraycopy(packet, transportOffset, buf, pseudoLen, transportLen)
        return ipChecksum(buf)
    }

    private fun read32(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 24) or
            ((packet[offset + 1].toInt() and 0xFF) shl 16) or
            ((packet[offset + 2].toInt() and 0xFF) shl 8) or
            (packet[offset + 3].toInt() and 0xFF)

    private fun handleDnsPacket(
        packet: ByteArray,
        dnsStart: Int,
        len: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int
    ) {
        if (len < dnsStart + 12) return
        val qname = DnsParser.readQName(packet, dnsStart + 12, len) ?: return

        logAttributionSignals(srcIp, dstIp, srcPort, qname)

        val logging = RequestLog.isEnabled
        val srcIpStr = ipToString(srcIp)
        val dstIpStr = ipToString(dstIp)

        // Пауза из уведомления: фильтрация временно отключена, запросы идут
        // напрямую к резолверу, но продолжают попадать в лог как разрешённые.
        if (AppSettings.isPaused()) {
            val owner = if (logging) attributedApp(srcIp, dstIp, srcPort) else null
            if (logging) {
                RequestLog.record(
                    owner ?: BlockStats.OTHER,
                    qname,
                    RequestLog.Status.ALLOWED,
                    srcIpStr,
                    dstIpStr
                )
            }
            val dnsPayload = packet.copyOfRange(dnsStart, len)
            val clientIp = srcIp
            val serverIp = dstIp
            relayPool?.execute {
                relayDns(dnsPayload, clientIp, serverIp, srcPort)
            }
            return
        }

        // Пользовательские правила: домен и все его поддомены
        if (HostRules.isBlocked(qname)) {
            val appRule = if (logging || activeThrottles > 0) {
                attributedApp(srcIp, dstIp, srcPort)
            } else {
                null
            }
            val owner = appRule ?: BlockStats.OTHER
            blockedCount.incrementAndGet()
            val statsOwner = AppTrackerMap.statsOwner(owner, qname)
            BlockStats.record(statsOwner, qname, BlockStats.Kind.TRACKER)
            Log.d(TAG, "Заблокировано пользователем: $qname")
            if (logging) {
                RequestLog.record(
                    statsOwner,
                    qname,
                    RequestLog.Status.CUSTOM_BLOCKED,
                    srcIpStr,
                    dstIpStr,
                    qname,
                    reasonFor(owner, statsOwner)
                )
            }
            buildBlockResponse(packet, dnsStart, len)?.let {
                writeDnsResponse(srcIp, dstIp, srcPort, it)
            }
            return
        }

        val trackerMatch = if (AppSettings.blockTrackers()) TrackerBlocklist.findMatch(qname) else null
        val adsMatch = if (AppSettings.blockAds()) AdBlocklist.findMatch(qname) else null
        val hostAllowedByUser = HostRules.isAllowed(qname)

        // Автор запроса нужен для лога, per-app настроек и трекер-троттлинга.
        val app = if (trackerMatch != null || adsMatch != null || logging || activeThrottles > 0) {
            attributedApp(srcIp, dstIp, srcPort)
        } else {
            null
        }

        val trackersMatch = trackerMatch != null &&
            (app == null || PerAppSettings.isTrackerBlockingEnabled(app))
        val adsMatchBlocked = adsMatch != null

        if (trackersMatch || adsMatchBlocked) {
            if ((app != null && app in exemptPackages) || isExemptAppRequest(srcIp, dstIp, srcPort)) {
                Log.d(TAG, "Не блокируем от исключённого приложения: $qname")
            } else {
                if (hostAllowedByUser) {
                    Log.d(TAG, "Пользователь разрешил хост: $qname")
                } else {
                    val owner = app ?: BlockStats.OTHER
                    val isTracker = trackersMatch
                    val kind = if (isTracker) BlockStats.Kind.TRACKER else BlockStats.Kind.ADS
                    blockedCount.incrementAndGet()
                    // База связей: подтверждённая пара остаётся у приложения,
                    // а заведомо чужие трекеры уходят в «Другое».
                    val statsOwner = AppTrackerMap.statsOwner(owner, qname)
                    BlockStats.record(statsOwner, qname, kind)
                    Log.d(TAG, "Заблокировано: $qname (${kind.name})")
                    val pattern = if (isTracker) trackerMatch else adsMatch
                    if (isTracker) {
                        // Троттлинг — только если трекер действительно
                        // принадлежит приложению. Иначе из-за ошибочной пары
                        // можно целиком отключить интернет невиновному app.
                        if (statsOwner != BlockStats.OTHER) applyThrottle(owner)
                        if (logging) RequestLog.record(
                            statsOwner,
                            qname,
                            RequestLog.Status.TRACKER,
                            srcIpStr,
                            dstIpStr,
                            pattern,
                            reasonFor(owner, statsOwner)
                        )
                    } else if (logging) {
                        RequestLog.record(
                            statsOwner,
                            qname,
                            RequestLog.Status.ADS,
                            srcIpStr,
                            dstIpStr,
                            pattern,
                            reasonFor(owner, statsOwner)
                        )
                    }
                    // NXDOMAIN вместо «тишины»: клиент завершает запрос сразу, без
                    // таймаута и повторов, которые могли бы «проскочить» мимо фильтра.
                    buildBlockResponse(packet, dnsStart, len)?.let {
                        writeDnsResponse(srcIp, dstIp, srcPort, it)
                    }
                    return
                }
            }
        }

        // Трекер-троттлинг: пока приложение в «бане», глушим даже разрешённые
        // запросы — новые соединения не создаются, доступ фактически отключён.
        if (activeThrottles > 0) {
            val owner = app ?: attributedApp(srcIp, dstIp, srcPort)
            if (isThrottled(owner)) {
                Log.d(TAG, "Троттлинг: доступ отключён \"$owner\" (запрос $qname)")
                if (logging) RequestLog.record(owner, qname, RequestLog.Status.THROTTLE, srcIpStr, dstIpStr)
                buildBlockResponse(packet, dnsStart, len)?.let {
                    writeDnsResponse(srcIp, dstIp, srcPort, it)
                }
                return
            }
        }

        if (logging) {
            val owner = app ?: attributedApp(srcIp, dstIp, srcPort)
            RequestLog.record(owner, qname, RequestLog.Status.ALLOWED, srcIpStr, dstIpStr, null)
        }

        // копируем полезную нагрузку: буфер перезапишется следующим чтением
        val dnsPayload = packet.copyOfRange(dnsStart, len)
        val clientIp = srcIp
        val serverIp = dstIp
        relayPool?.execute {
            relayDns(dnsPayload, clientIp, serverIp, srcPort)
        }
    }

    /**
     * Трекер-троттлинг: копим трекерные блоки приложения за [THROTTLE_WINDOW_MS];
     * больше [THROTTLE_HIT_LIMIT] за окно — полностью отключаем DNS этого
     * приложения на [THROTTLE_DURATION_MS]. Собственные и «система» не банятся.
     */
    private fun applyThrottle(app: String) {
        if (app == BlockStats.SYSTEM || app == BlockStats.OTHER || app in exemptPackages) return
        val now = System.currentTimeMillis()
        val hits = trackerHits.getOrPut(app) { mutableListOf() }
        hits.add(now)
        hits.removeAll { it < now - THROTTLE_WINDOW_MS }
        throttledUntil.keys.removeIf { throttledUntil[it]!! < now }
        if (hits.size > THROTTLE_HIT_LIMIT) {
            throttledUntil[app] = now + THROTTLE_DURATION_MS
            Log.w(TAG, "Троттлинг \"$app\": ${hits.size} трекерных запросов за " +
                "${THROTTLE_WINDOW_MS / 1000}с — интернет отключён на $THROTTLE_DURATION_MS мс")
        }
        activeThrottles = throttledUntil.size
    }

    /** Забанено ли приложение сейчас; заодно подчищает протухшие баны. */
    private fun isThrottled(app: String): Boolean {
        val now = System.currentTimeMillis()
        val until = throttledUntil[app]
        if (until != null) {
            if (now < until) return true
            throttledUntil.remove(app)
        }
        throttledUntil.keys.removeIf { throttledUntil[it]!! < now }
        activeThrottles = throttledUntil.size
        return false
    }

    /**
     * Решает, кто автор DNS-запроса. Используется для статистики, лога,
     * per-app настроек, троттлинга и освобождения собственных приложений.
     *
     * На Android DNS-запросы уходят через системный резолвер netd, поэтому
     * исходный сокет почти всегда «системный» (uid 0/1000) и по нему приложение
     * не определить. Цепочка сигналов — по уровню API, на котором каждый
     * появился, в порядке убывания надёжности:
     *  1. безусловно: сокет из /proc/net/udp принадлежит реальному приложению —
     *     берём его (дешёвый случай, ловит приложения со своим DNS-сокетом);
     *  2. API 29+: [ConnectivityManager.getConnectionOwnerUid] для той же пары
     *     адресов — владелец сокета в netd; иногда безуспешен (см.
     *     [probeConnectionOwnerUid]) — тогда молча уступает место ниже;
     *  3. API 23+: приложение, передавшее данные в последнем опросе NetworkStats
     *     (прирост за секунду; в пределах ACTIVE_APP_WINDOW_MS);
     *  4. иначе — «другое».
     *
     * Уровень 23+ версионной проверки не требует: minSdk 24 выше и API 23.
     * Уровень 29+ недоступен на Android 9 и ниже — там работает только эвристика.
     */
    private fun attributedApp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int): String {
        val uid = uidForSourcePort(srcPort)
        var raw = packageNameForUid(uid)
        if (raw == BlockStats.SYSTEM || raw.startsWith("uid:")) {
            raw = probeAppName(srcIp, dstIp, srcPort)
                ?: recentActiveApp()?.let { packageNameForUid(it) }?.takeIf { !it.startsWith("uid:") }
                ?: BlockStats.OTHER
        }
        return raw
    }

    /**
     * Причина, по которой запись в логе оказалась без точного приложения.
     * В DNS-режиме запрос часто уходит через системный резолвер (netd): сокет
     * принадлежит системе, и авторство приходится угадывать по активности.
     * Если же приложение определено, но база связей сочла трекер чужим, это
     * уже не «неизвестно кто», а осознанная переатрибуция в «Другое».
     */
    private fun reasonFor(owner: String, statsOwner: String): RequestLog.Reason? = when {
        owner == BlockStats.OTHER -> RequestLog.Reason.SYSTEM_RESOLVER
        statsOwner == BlockStats.OTHER -> RequestLog.Reason.DB_MISATTRIBUTION
        else -> null
    }

    /**
     * Имя пакета владельца сокета из [ConnectivityManager.getConnectionOwnerUid]
     * либо null, если определить не удалось: старый API (до Android 10), сокета
     * нет в таблицах, вызов отклонён (мы не активный VPN) либо сокет оказался
     * системным (uid < 10000) или нашим собственным — такое авторство не
     * считается, и цепочка сигналов уходит к недавней активности.
     */
    private fun probeAppName(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int): String? {
        val uid = probeConnectionOwnerUid(srcIp, dstIp, srcPort)
        if (uid < 10_000 || uid == Process.myUid()) return null
        return packageNameForUid(uid).takeIf { !it.startsWith("uid:") }
    }

    /**
     * uid владельца пары сокетов (клиентский UDP-сокет -> DNS-сервер) из
     * [ConnectivityManager.getConnectionOwnerUid] либо [Process.INVALID_UID],
     * если определить не удалось. Public API с Android 10, privileged-прав не
     * требует; кидает [SecurityException], если вызывающий не активный VpnService.
     */
    private fun probeConnectionOwnerUid(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Process.INVALID_UID
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Process.INVALID_UID
        return try {
            val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
            val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), DNS_PORT)
            cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
        } catch (e: SecurityException) {
            Process.INVALID_UID
        } catch (e: Exception) {
            Process.INVALID_UID
        }
    }

    // ————————————————————— диагностика атрибуции —————————————————————

    /**
     * Рядом с каждым DNS-запросом логирует все доступные сигналы «кто автор»:
     * пробу [ConnectivityManager.getConnectionOwnerUid], прирост per-uid счётчиков
     * [NetworkStatsManager] и текущий результат эвристики. Логика блокировки не
     * меняется — нужно только собрать данные для сравнения на живом устройстве.
     */
    private fun logAttributionSignals(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, host: String) {
        if (!ATTRIBUTION_DIAGNOSTICS) return
        val pool = diagnosticsPool ?: return // пул создаётся при старте VPN
        pool.execute {
            val deltas = topUidDeltas.take(3).joinToString { (uid, bytes) ->
                "${packageNameForUid(uid)}:$bytes" + if (uid == lastActiveUid) "*" else ""
            }
            val active = synchronized(trafficWindowMutex) {
                if (lastActiveUid < 0) "—" else "${packageNameForUid(lastActiveUid)}*"
            }
            Log.d(TAG, "Атрибуция \"$host\": socket=${probeConnectionOwner(srcIp, dstIp, srcPort)}" +
                " | дельты=[$deltas] | активный=$active" +
                " | в туннеле=$srcPort")
        }
    }

    /**
     * Проба [ConnectivityManager.getConnectionOwnerUid]: публичный API с Android 10,
     * privileged-прав не требует, но кидает [SecurityException], если вызывающий не
     * активный VpnService. Метод ищет владельца СОКЕТА по паре адресов, поэтому для
     * DNS-запроса ответ заведомо может оказаться нами или вовсе [Process.INVALID_UID]:
     * запрос отправляет netd от имени приложения, а не само приложение.
     */
    private fun probeConnectionOwner(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "API<29"
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "нет ConnectivityManager"
        return try {
            val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
            val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), DNS_PORT)
            val startedAtNs = System.nanoTime()
            val uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
            val tookUs = (System.nanoTime() - startedAtNs) / 1000
            val owner = when (uid) {
                Process.INVALID_UID -> "сокета нет"
                Process.myUid() -> "это мы"
                else -> packageNameForUid(uid)
            }
            "$uid ($owner) за ${tookUs}мкс"
        } catch (e: SecurityException) {
            "SecurityException: мы не активный VPN"
        } catch (e: Exception) {
            "ошибка ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Запрос от исключённого приложения: пересылаем резолверу, как разрешённый.
     * Сокет DNS-пакета почти всегда «системный», поэтому авторство неизвестно;
     * освобождаем по любому из сигналов: сокет, владелец сокета из
     * [ConnectivityManager.getConnectionOwnerUid], недавняя активность.
     */
    private fun isExemptAppRequest(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int): Boolean {
        val uid = uidForSourcePort(srcPort)
        if (packageNameForUid(uid) in exemptPackages) return true
        val owner = probeAppName(srcIp, dstIp, srcPort)
        if (owner != null && owner in exemptPackages) return true
        val recentUid = recentActiveApp()
        return recentUid != null && packageNameForUid(recentUid) in exemptPackages
    }

    /** Реальное приложение, последним передававшее данные в пределах ACTIVE_APP_WINDOW_MS. */
    private fun recentActiveApp(): Int? {
        val now = System.currentTimeMillis()
        val uid = synchronized(trafficWindowMutex) { lastActiveUid }
        if (uid < 10000) return null
        val age = now - synchronized(trafficWindowMutex) { lastActiveAtMs }
        if (age < 0 || age > ACTIVE_APP_WINDOW_MS) return null
        return uid
    }

    @Suppress("DEPRECATION")
    private fun packageNameForUid(uid: Int): String {
        if (uid <= 0) return BlockStats.SYSTEM
        val direct = packageManager.getPackagesForUid(uid)?.firstOrNull()
        if (direct != null) return direct
        ensureUidCache()
        val cached = uidPackages[uid]
        if (cached != null) return cached
        return "uid:$uid"
    }

    private fun ensureUidCache() {
        if (uidCacheLoaded) return
        synchronized(uidCacheSync) {
            if (uidCacheLoaded) return
            try {
                for (pi in packageManager.getInstalledPackages(0)) {
                    val u = pi.applicationInfo?.uid ?: continue
                    if (u > 0) uidPackages.putIfAbsent(u, pi.packageName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось построить кэш uid->package", e)
            }
            uidCacheLoaded = true
        }
    }

    /** Держит [lastActiveUid] свежим через [NetworkStatsManager]. */
    private fun trafficWatcherLoop() {
        while (running) {
            try {
                scanTraffic()
            } catch (e: Exception) {
                Log.w(TAG, "Сбой сканера трафика", e)
            }
            try {
                Thread.sleep(1_000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /**
     * Сигнал «недавняя активность» (уровень с API 23): опрашивает по-uid счётчики
     * трафика за [TRAFFIC_WINDOW_MS] и считает ПРИРОСТ с прошлого опроса (~1 с).
     * Побеждает приложение, передавшее больше всего данных за последнюю секунду;
     * если есть «свежие» (на прошлом опросе молчавшие) — среди них. Это ловит
     * момент пробуждения: DNS-запрос обычно — первый пакет новой сессии, а
     * фоновый сервис, напротив, передаёт данные постоянно и «свежим» не бывает.
     * Для чужого трафика нужен выданный «Доступ к использованию приложений»;
     * без него сигнал просто не работает (остаёмся на «другое»).
     * [NetworkStatsManager.querySummary] появился в API 23 — версионная проверка
     * не нужна, minSdk и так выше (минимум поддерживаемого уровня 24).
     */
    private fun scanTraffic() {
        val ns = getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return

        val now = System.currentTimeMillis()
        val start = now - TRAFFIC_WINDOW_MS
        val bytesInWindow = HashMap<Int, Long>()

        for (type in STATS_NETWORK_TYPES) {
            try {
                val stats = ns.querySummary(type, null, start, now)
                if (stats == null) continue // некоторые типы сети не поддерживаются
                stats.use { s ->
                    val bucket = NetworkStats.Bucket()
                    while (s.hasNextBucket()) {
                        s.getNextBucket(bucket)
                        val uid = bucket.uid
                        if (uid < 10_000) continue
                        if (uid == Process.myUid()) continue // трафик самого VPN-туннеля
                        val bytes = bucket.txBytes + bucket.rxBytes
                        bytesInWindow[uid] = bytesInWindow.getOrDefault(uid, 0L) + bytes
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось опросить NetworkStats (type=$type)", e)
            }
        }

        synchronized(trafficWindowMutex) {
            val firstScan = uidBytesAtScan.isEmpty()
            // прирост с прошлого опроса: байты, появившиеся только что (~1 с);
            // на первом опросе базы ещё нет, там будут накопленные за окно
            val deltas = bytesInWindow
                .mapNotNull { (uid, bytes) ->
                    val delta = bytes - (uidBytesAtScan[uid] ?: 0L)
                    if (delta > 0) uid to delta else null
                }
                .sortedByDescending { it.second }
            uidBytesAtScan.clear()
            uidBytesAtScan.putAll(bytesInWindow)
            topUidDeltas = deltas.take(5)

            // «свежие» — те, кто на прошлом опросе молчал: среди них и ищем автора,
            // а постоянно болтающего фонового сервиса пропускаем
            val fresh = deltas.filter { it.first !in prevDeltaUids }
            prevDeltaUids.clear()
            prevDeltaUids.addAll(deltas.map { it.first })

            // первый опрос только закладывает базу — иначе накопленное за 30 с
            // показалось бы свежим приростом
            val best = if (firstScan) null else (fresh.firstOrNull() ?: deltas.firstOrNull())
            if (best != null) {
                lastActiveUid = best.first
                lastActiveAtMs = now
            }
        }
    }

    /**
     * UID владельца пакета по таблице сокетов. Нужен как запасной сигнал,
     * когда нативный стек не смог получить владельца через
     * [ConnectivityManager.getConnectionOwnerUid] (чаще всего — непривязанные
     * UDP-сокеты и QUIC). На новых прошивках /proc/net может быть частично
     * скрыт, тогда просто возвращаем -1 и решение остаётся за вызывающим.
     */
    private fun uidForSocket(packet: Packet): Int =
        uidForSourcePort(packet.sport, packet.protocol, packet.dport)

    private fun uidForSourcePort(
        sourcePort: Int,
        protocol: Int = OsConstants.IPPROTO_UDP,
        destPort: Int = 0
    ): Int {
        if (sourcePort <= 0) return -1
        val files = when (protocol) {
            OsConstants.IPPROTO_TCP -> listOf("/proc/net/tcp", "/proc/net/tcp6")
            OsConstants.IPPROTO_UDP -> listOf("/proc/net/udp", "/proc/net/udp6")
            else -> return -1
        }
        val portHex = String.format(Locale.ROOT, "%04X", sourcePort)
        for (procPath in files) {
            try {
                File(procPath).readLines().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size <= 8 || !parts[1].endsWith(":$portHex")) {
                        return@forEach
                    }
                    // У несвязанного UDP-сокета удалённый порт нулевой, поэтому
                    // сравниваем только когда он известен.
                    val remotePort = parts[2].substringAfterLast(':').toIntOrNull(16) ?: 0
                    if (destPort > 0 && remotePort != 0 && remotePort != destPort) {
                        return@forEach
                    }
                    val uid = parts[7].toIntOrNull()
                    if (uid != null) return uid
                }
            } catch (e: IOException) {
                Log.w(TAG, "Не удалось прочитать $procPath", e)
            }
        }
        return -1
    }

    /**
     * Ответ на заблокированный запрос: NXDOMAIN (по умолчанию) или нулевой
     * адрес, если пользователь выбрал этот режим. Второй вариант помогает
     * приложениям, которые плохо переносят NXDOMAIN и начинают долбить
     * резолвер повторами; для AAAA отдаём `::`, для остальных типов — NXDOMAIN.
     */
    private fun buildBlockResponse(packet: ByteArray, dnsStart: Int, end: Int): ByteArray? =
        if (AppSettings.blockWithNullAddress()) {
            buildNullAddressResponse(packet, dnsStart, end)
                ?: buildNxdomainResponse(packet, dnsStart, end)
        } else {
            buildNxdomainResponse(packet, dnsStart, end)
        }

    /** DNS-ответ с одним A=0.0.0.0 или AAAA=:: для блокировки. */
    private fun buildNullAddressResponse(packet: ByteArray, dnsStart: Int, end: Int): ByteArray? {
        val qEnd = skipQuestion(packet, dnsStart, end) ?: return null
        val qType = read16(packet, qEnd - 4)
        if (qType != TYPE_A && qType != TYPE_AAAA) return null
        val questionLen = qEnd - (dnsStart + 12)
        val rdataLen = if (qType == TYPE_A) 4 else 16
        val resp = ByteArray(12 + questionLen + 12 + rdataLen)
        resp[0] = packet[dnsStart]        // transaction id
        resp[1] = packet[dnsStart + 1]
        resp[2] = 0x81.toByte()           // QR=1, RD=1
        resp[3] = 0x80.toByte()           // RA=1, RCODE=0
        resp[5] = 1                       // QDCOUNT=1
        resp[7] = 1                       // ANCOUNT=1
        System.arraycopy(packet, dnsStart + 12, resp, 12, questionLen)
        var pos = 12 + questionLen
        resp[pos] = 0xC0.toByte()         // имя — указатель на вопрос
        resp[pos + 1] = 0x0C
        write16(resp, pos + 2, qType)
        write16(resp, pos + 4, 1)         // класс IN
        write32(resp, pos + 6, 60)        // TTL 60 с
        write16(resp, pos + 10, rdataLen)
        // rdata уже нули: 0.0.0.0 или ::
        return resp
    }

    /** Позиция сразу после QNAME+TYPE+CLASS вопроса. */
    private fun skipQuestion(packet: ByteArray, dnsStart: Int, end: Int): Int? {
        if (dnsStart + 12 > end) return null
        var i = dnsStart + 12
        while (true) {
            if (i >= end) return null
            val labelLen = packet[i].toInt() and 0xFF
            if (labelLen == 0) break
            i += 1 + labelLen
        }
        val qEnd = i + 1 + 4 // нулевой байт QNAME + TYPE + CLASS
        return if (qEnd <= end) qEnd else null
    }

    /**
     * Собирает DNS-ответ NXDOMAIN по входящему запросу: отдаём клиенту его же
     * transaction id и копию вопроса, RCODE=3 (имя не существует). Дополнительные
     * записи (EDNS OPT и т.п.) отбрасываются, чтобы алгоритм не сбоил на хвосте.
     */
    private fun buildNxdomainResponse(packet: ByteArray, dnsStart: Int, end: Int): ByteArray? {
        if (dnsStart + 12 > end) return null
        val qEnd = skipQuestion(packet, dnsStart, end) ?: return null
        val questionLen = qEnd - (dnsStart + 12)
        val resp = ByteArray(12 + questionLen)
        resp[0] = packet[dnsStart]        // transaction id (отвечаем тем же)
        resp[1] = packet[dnsStart + 1]
        resp[2] = 0x81.toByte()           // QR=1, RD=1, RA=1
        resp[3] = 0x83.toByte()           // RCODE=3 (NXDOMAIN)
        resp[4] = 0
        resp[5] = 1                       // QDCOUNT=1; AN/NS/AR = 0
        System.arraycopy(packet, dnsStart + 12, resp, 12, questionLen)
        return resp
    }

    /** Пересылка разрешённого DNS-запроса резолверу и запись ответа в туннель. */
    private fun relayDns(
        dnsPayload: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int
    ) {
        val cacheKey = String(dnsPayload, 2, dnsPayload.size - 2, Charsets.ISO_8859_1)
        val now = System.currentTimeMillis()
        val cached = dnsCache[cacheKey]
        if (cached != null && now < cached.expiresAt) {
            // Списки обновляются на лету: даже закешированный ответ мог
            // получить трекерную CNAME-цель. Перепроверяем цепочку.
            val cachedInfo = DnsParser.parseAnswer(cached.response, cached.response.size)
            if (tryBlockCname(dnsPayload, srcIp, dstIp, srcPort, cachedInfo)) return
            val response = cached.response.copyOf()
            response[0] = dnsPayload[0]
            response[1] = dnsPayload[1]
            writeDnsResponse(srcIp, dstIp, srcPort, response)
            return
        }
        if (cached != null) dnsCache.remove(cacheKey, cached)

        val provider = DnsProviders.byId(AppSettings.dnsProviderId())
        if (provider.id != appliedDnsProviderId) {
            appliedDnsProviderId = provider.id
            upstream().resetDot()
        }
        val client = upstream()
        // EDNS0 ограничиваем до 1232 байт: иначе ответ не влезет в MTU туннеля
        // и будет молча потерян. Заодно вырезаем ECS, чтобы не отдавать
        // провайдеру подсеть пользователя. Кешируем по исходному запросу.
        val forwardPayload = DnsParser.sanitizeEdns(
            dnsPayload,
            dnsPayload.size,
            EDNS_MAX_PAYLOAD
        )

        var answer: ByteArray? = null
        if (AppSettings.dnsEncryptionEnabled() &&
            provider.dotHost != null &&
            provider.dotIps.isNotEmpty()
        ) {
            answer = client.queryDot(
                provider.dotHost,
                provider.dotIps,
                forwardPayload,
                DNS_DOT_TIMEOUT_MS
            )
        }
        if (answer == null) {
            val servers = upstreamServers(provider)
            val good = lastGoodDns?.takeIf { it.first == provider.id }?.second
            val ordered = if (good != null && good in servers) {
                listOf(good) + servers.filter { it != good }
            } else {
                servers
            }
            for (server in ordered) {
                if (!running) return
                answer = client.queryUdp(server, forwardPayload, DNS_TIMEOUT_MS)
                if (answer != null) {
                    lastGoodDns = provider.id to server
                    break
                }
            }
        }
        if (answer == null || !running) {
            if (running) Log.w(TAG, "Все DNS-резолверы недоступны (${provider.id})")
            return
        }

        // Отбрасываем ответы с чужим transaction id / вопросом: так кеш и
        // клиент защищены от подмены и случайных пакетов.
        if (!DnsParser.isResponseFor(dnsPayload, dnsPayload.size, answer, answer.size)) {
            Log.w(TAG, "Отброшен DNS-ответ, не соответствующий запросу")
            return
        }

        val info = DnsParser.parseAnswer(answer, answer.size)
        if (tryBlockCname(dnsPayload, srcIp, dstIp, srcPort, info)) return

        val ttlMs = cacheTtlMs(info, answer)
        if (ttlMs > 0 && answer.size <= DNS_MAX_RESPONSE) {
            if (dnsCache.size >= DNS_CACHE_MAX_SIZE) pruneCache()
            dnsCache[cacheKey] = DnsCacheEntry(answer, now + ttlMs)
        }
        writeDnsResponse(srcIp, dstIp, srcPort, answer)
    }

    /**
     * Проверяет CNAME-цепочку и, если цель в списке, сам записывает блокировку
     * в статистику/лог и отправляет клиенту ответ. Возвращает true, если
     * запрос заблокирован.
     */
    private fun tryBlockCname(
        dnsPayload: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        info: DnsParser.AnswerInfo?
    ): Boolean {
        val (target, kind) = blockedCname(info, dnsPayload) ?: return false
        val qname = DnsParser.readQName(dnsPayload, 12, dnsPayload.size) ?: return false
        val app = attributedApp(srcIp, dstIp, srcPort)
        val trackerDisabled = kind == BlockStats.Kind.TRACKER &&
            !PerAppSettings.isTrackerBlockingEnabled(app)
        if (trackerDisabled || app in exemptPackages) return false

        val owner = app
        blockedCount.incrementAndGet()
        // Для CNAME статистика хранит исходный qname, а проверять базу связей
        // нужно по реальному трекеру из цепочки.
        val statsOwner = AppTrackerMap.statsOwner(owner, target)
        BlockStats.record(statsOwner, qname, kind)
        val pattern = "CNAME $target"
        if (RequestLog.isEnabled) {
            RequestLog.record(
                statsOwner,
                qname,
                if (kind == BlockStats.Kind.TRACKER) RequestLog.Status.TRACKER
                else RequestLog.Status.ADS,
                ipToString(srcIp),
                ipToString(dstIp),
                pattern,
                reasonFor(owner, statsOwner)
            )
        }
        Log.d(TAG, "Заблокировано по CNAME: $qname -> $target")
        buildBlockResponse(dnsPayload, 0, dnsPayload.size)?.let {
            writeDnsResponse(srcIp, dstIp, srcPort, it)
        }
        return true
    }

    /**
     * Ищет в CNAME-цепочке ответа домен из списков блокировки (CNAME cloaking:
     * first-party имя маскирует трекер). Возвращает цель и тип блокировки.
     */
    private fun blockedCname(
        info: DnsParser.AnswerInfo?,
        query: ByteArray
    ): Pair<String, BlockStats.Kind>? {
        if (info == null || info.cnames.isEmpty()) return null
        // На паузе блокировка полностью выключена, включая CNAME-фильтр.
        if (AppSettings.isPaused()) return null
        val qname = DnsParser.readQName(query, 12, query.size) ?: return null
        // Пользовательское разрешение сильнее автоматической CNAME-блокировки.
        if (HostRules.isAllowed(qname)) return null
        for (target in info.cnames) {
            val host = target.lowercase().trimEnd('.')
            if (host.isEmpty()) continue
            if (AppSettings.blockTrackers()) {
                TrackerBlocklist.findMatch(host)?.let {
                    return target to BlockStats.Kind.TRACKER
                }
            }
            if (AppSettings.blockAds()) {
                AdBlocklist.findMatch(host)?.let {
                    return target to BlockStats.Kind.ADS
                }
            }
        }
        return null
    }

    /**
     * TTL кеша из ответа. Записи без ответа кешируем умеренно (120 с), TTL=0
     * не кешируем вовсе, слишком большие TTL ограничиваем часом, чтобы смена
     * блоклиста применялась без ожидания суток.
     */
    private fun cacheTtlMs(info: DnsParser.AnswerInfo?, answer: ByteArray): Long {
        if (DnsParser.isTruncated(answer)) return 0L
        if (info == null || info.minTtl < 0) return DNS_CACHE_TTL_MS
        if (info.minTtl == 0L) return 0L
        return (info.minTtl * 1000L)
            .coerceIn(DNS_CACHE_MIN_TTL_MS, DNS_CACHE_MAX_TTL_MS)
    }

    /**
     * Собирает IP+UDP пакет с ответом (src/dst разворачиваются: источник =
     * адрес виртуального DNS, назначение = клиент) и пишет в туннель.
     * Семейство адресов ответа совпадает с семейством запроса.
     */
    private fun writeDnsResponse(
        srcIp: ByteArray,
        dnsIp: ByteArray,
        srcPort: Int,
        dnsPayload: ByteArray
    ) {
        if (srcIp.size == 16) {
            writeIpv6DnsResponse(srcIp, dnsIp, srcPort, dnsPayload)
        } else {
            writeIpv4DnsResponse(srcIp, dnsIp, srcPort, dnsPayload)
        }
    }

    /** IPv4+UDP пакет с DNS-ответом. */
    private fun writeIpv4DnsResponse(
        srcIp: ByteArray,
        dnsIp: ByteArray,
        srcPort: Int,
        dnsPayload: ByteArray
    ) {
        val total = 20 + 8 + dnsPayload.size
        if (total > MTU) return
        val out = ByteArray(total)

        out[0] = 0x45
        write16(out, 2, total)
        write16(out, 4, total)
        out[8] = 64
        out[9] = 17
        System.arraycopy(dnsIp, 0, out, 12, 4)  // источник: адрес DNS, к нему обращался клиент
        System.arraycopy(srcIp, 0, out, 16, 4)  // назначение: клиент

        write16(out, 20, DNS_PORT)
        write16(out, 22, srcPort)
        write16(out, 24, 8 + dnsPayload.size)
        System.arraycopy(dnsPayload, 0, out, 28, dnsPayload.size)

        val ipCsum = ipChecksum(out.copyOfRange(0, 20))
        write16(out, 10, ipCsum)
        val udpCsum = udpChecksumFromPacket(out, 8 + dnsPayload.size)
        write16(out, 26, udpCsum)

        writeTunnelResponse(out)
    }

    /** IPv6+UDP пакет с DNS-ответом (заголовок фиксированной длины, без расширений). */
    private fun writeIpv6DnsResponse(
        srcIp: ByteArray,
        dnsIp: ByteArray,
        srcPort: Int,
        dnsPayload: ByteArray
    ) {
        val udpLen = 8 + dnsPayload.size
        val total = 40 + udpLen
        if (total > MTU) return
        val out = ByteArray(total)

        out[0] = 0x60.toByte()
        write16(out, 4, udpLen)          // payload length
        out[6] = 17                      // next header: UDP
        out[7] = 64                      // hop limit
        System.arraycopy(dnsIp, 0, out, 8, 16)   // источник: адрес DNS
        System.arraycopy(srcIp, 0, out, 24, 16)  // назначение: клиент

        write16(out, 40, DNS_PORT)
        write16(out, 42, srcPort)
        write16(out, 44, udpLen)
        System.arraycopy(dnsPayload, 0, out, 48, dnsPayload.size)

        // В IPv6 контрольная сумма UDP обязательна — нулевое значение запрещено.
        val udpCsum = udpChecksumFromPacket6(out, udpLen)
        write16(out, 46, if (udpCsum == 0) 0xFFFF else udpCsum)

        writeTunnelResponse(out)
    }

    private fun writeTunnelResponse(out: ByteArray) {
        try {
            writeToTunnel(out)
        } catch (e: ErrnoException) {
            if (running) Log.w(TAG, "Не удалось записать ответ в туннель", e)
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Не удалось записать ответ в туннель", e)
        }
    }

    /** Запись в неблокирующий fd туннеля с повтором при EAGAIN. */
    private fun writeToTunnel(out: ByteArray) {
        synchronized(writeLock) {
            val fd = tunnelWriter ?: return
            var offset = 0
            while (offset < out.size) {
                try {
                    val n = Os.write(fd, out, offset, out.size - offset)
                    if (n <= 0) break
                    offset += n
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN) {
                        try {
                            Thread.sleep(5)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        continue
                    }
                    throw e
                }
            }
        }
    }

    // ————————————————————— утилиты пакетов —————————————————————

    private fun read16(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    private fun write16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun write32(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 24).toByte()
        packet[offset + 1] = (value ushr 16).toByte()
        packet[offset + 2] = (value ushr 8).toByte()
        packet[offset + 3] = value.toByte()
    }

    /** Адрес в читаемом виде для логов: IPv4 или IPv6. */
    private fun ipToString(address: ByteArray): String = try {
        InetAddress.getByAddress(address).hostAddress ?: address.joinToString(".")
    } catch (e: Exception) {
        address.joinToString(".")
    }

    /** Контрольная сумма (RFC 1071). Нечётный хвост дополняется нулевым байтом. */
    private fun ipChecksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
            i += 2
        }
        if (i < data.size) { // нечётная длина: дополняем нулевым байтом справа
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        return ((0xFFFF - (sum and 0xFFFF)) and 0xFFFF).toInt()
    }

    /**
     * Контрольная сумма UDP поверх IPv4. Адреса источника и назначения берутся
     * из самого собранного IP-пакета ([packet][12..20]), чтобы псевдозаголовок
     * точно совпадал с направлением ответа.
     */
    private fun udpChecksumFromPacket(packet: ByteArray, udpLen: Int): Int {
        val buf = ByteArray(12 + udpLen)
        System.arraycopy(packet, 12, buf, 0, 8)
        buf[8] = 0
        buf[9] = 17
        write16(buf, 10, udpLen)
        System.arraycopy(packet, 20, buf, 12, udpLen) // UDP-заголовок (checksum=0) + данные
        return ipChecksum(buf)
    }

    /**
     * Контрольная сумма UDP поверх IPv6: псевдозаголовок из адресов [8..40],
     * длины верхнего уровня, нулей и номера протокола (RFC 8200).
     */
    private fun udpChecksumFromPacket6(packet: ByteArray, udpLen: Int): Int {
        val buf = ByteArray(40 + udpLen)
        System.arraycopy(packet, 8, buf, 0, 32)   // IPv6-адреса источника и назначения
        write32(buf, 32, udpLen)                  // длина UDP-датаграммы
        buf[39] = 17                              // следующий заголовок: UDP
        System.arraycopy(packet, 40, buf, 40, udpLen)
        return ipChecksum(buf)
    }

    private fun pruneCache() {
        val now = System.currentTimeMillis()
        dnsCache.keys.removeIf { key -> dnsCache[key]?.let { it.expiresAt < now } ?: true }
        // Протухших записей может не быть вовсе — тогда освобождаем четверть
        // самых старых, иначе словарь растёт выше лимита.
        if (dnsCache.size >= DNS_CACHE_MAX_SIZE) {
            val victims = dnsCache.entries
                .sortedBy { it.value.expiresAt }
                .take((DNS_CACHE_MAX_SIZE / 4).coerceAtLeast(1))
            for (entry in victims) dnsCache.remove(entry.key, entry.value)
        }
    }

    // ————————————————————— уведомление —————————————————————

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        val waiting = awaitingOtherVpn
        val paused = !waiting && AppSettings.isPaused()
        val title = when {
            waiting -> getString(R.string.notification_waiting_title)
            paused -> getString(R.string.notification_paused_title)
            else -> getString(R.string.notification_title)
        }
        val text = when {
            waiting -> getString(R.string.notification_waiting_text)
            paused -> getString(R.string.notification_paused_text, pauseEndText())
            else -> blockingStatusText()
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (waiting) {
            builder.addAction(
                notificationAction(
                    R.drawable.ic_notif_resume,
                    R.string.notification_action_resume_now,
                    ACTION_RESUME_NOW,
                    REQUEST_RESUME_NOW
                )
            )
        } else if (paused) {
            builder.addAction(
                notificationAction(
                    R.drawable.ic_notif_resume,
                    R.string.notification_action_resume,
                    ACTION_RESUME,
                    REQUEST_RESUME
                )
            )
        } else {
            builder.addAction(
                notificationAction(
                    R.drawable.ic_notif_pause,
                    R.string.notification_action_pause,
                    ACTION_PAUSE,
                    REQUEST_PAUSE
                )
            )
        }
        builder.addAction(
            notificationAction(
                R.drawable.ic_notif_stop,
                R.string.notification_action_stop,
                ACTION_STOP,
                REQUEST_STOP
            )
        )

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        schedulePauseEndRefresh()
        QuickTileService.notifyStateChanged(this)
    }

    private fun notificationAction(
        iconRes: Int,
        titleRes: Int,
        action: String,
        requestCode: Int
    ): Notification.Action {
        val intent = Intent(this, BlockVpnService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, iconRes),
            getString(titleRes),
            pending
        ).build()
    }

    /** Время окончания паузы в формате ЧЧ:ММ. */
    private fun pauseEndText(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(AppSettings.pauseUntil()))

    /** Через сколько закончится пауза — обновляем уведомление автоматически. */
    private fun schedulePauseEndRefresh() {
        notifyHandler.removeCallbacks(pauseEndRunnable)
        val remaining = AppSettings.pauseUntil() - System.currentTimeMillis()
        if (remaining > 0) {
            notifyHandler.postDelayed(pauseEndRunnable, remaining + 500)
        }
    }

    /** Текст статуса «что сейчас блокируется» — по настройкам. */
    private fun blockingStatusText(): String = when {
        AppSettings.blockTrackers() && AppSettings.blockAds() ->
            getString(R.string.status_on_all)
        AppSettings.blockTrackers() ->
            getString(R.string.status_on_trackers)
        AppSettings.blockAds() ->
            getString(R.string.status_on_ads)
        else ->
            getString(R.string.status_on)
    }
}