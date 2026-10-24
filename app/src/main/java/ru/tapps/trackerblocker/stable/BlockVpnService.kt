package ru.tapps.trackerblocker.stable

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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

    private var tunnel: ParcelFileDescriptor? = null
    private var tunnelWriter: java.io.FileDescriptor? = null

    private val writeLock = Any()

    @Volatile
    private var relayPool: java.util.concurrent.ExecutorService? = null

    private class DnsCacheEntry(val response: ByteArray, val expiresAt: Long)
    private val dnsCache = ConcurrentHashMap<String, DnsCacheEntry>()

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

    companion object {
        private const val TAG = "BlockVpnService"
        private const val CHANNEL_ID = "antitrack_vpn"
        private const val NOTIFICATION_ID = 1

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

        private const val TUN_IP = "10.88.88.1"
        private const val TUN_PREFIX = 24
        private const val DNS_IP = "10.88.88.53"
        private const val DNS_ROUTE_PREFIX = 32

        // Резолверы для пересылки разрешённых запросов. Первый рабочий в РФ — Яндекс.DNS.
        val upstreamDnsServers: List<String> = listOf(
            "77.88.8.8",           // Yandex.DNS
            "77.88.8.1",           // Yandex.DNS (backup)
            "208.67.222.222",      // OpenDNS
            "208.67.220.220"       // OpenDNS (backup)
        )

        private const val DNS_PORT = 53
        private const val MTU = 1500
        private const val DNS_TIMEOUT_MS = 5000
        private const val DNS_CACHE_TTL_MS = 120_000L
        private const val DNS_MAX_RESPONSE = 2048
        private const val DNS_CACHE_MAX_SIZE = 2048

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
            "84.200.70.40"        // DNS.WATCH
        )

        @Volatile
        var isRunning = false
            private set

        val blockedCount = AtomicLong(0)

        /** Пакеты, чей трафик полностью освобождён от блокировки трекеров. */
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand: running=$running startId=$startId action=${intent?.action ?: "null"}")
        if (intent?.action == ACTION_STOP) {
            // Платформа держит binding к VpnService, поэтому stopService() не может
            // уничтожить сервис. Правильное отключение: закрыть fd туннеля — система
            // разорвёт VPN, отпустит binding и вызовет onDestroy.
            Log.i(TAG, "Команда отключения")
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground до establish(): если create вернёт null, сервис можно
        // остановить без ForegroundServiceDidNotStartInTimeException.
        showNotification()
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        teardown()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke: согласие на VPN отозвано системой")
        teardown()
        super.onRevoke()
    }

    // ————————————————————— включение/выключение —————————————————————

    private fun startVpn() {
        synchronized(lifecycleLock) {
            if (running) {
                Log.i(TAG, "startVpn пропущен: уже работает")
                return
            }
            val tun = buildTunnel()
            if (tun == null) {
                Log.e(TAG, "Не удалось создать туннель (нет согласия на VPN?)")
                stopSelf()
                return
            }
            tunnel = tun
            tunnelWriter = tun.fileDescriptor
            running = true
            isRunning = true
            blockedCount.set(0)
            dnsCache.clear()
            rebuildUidCache()
            relayPool = Executors.newFixedThreadPool(8)
            if (ATTRIBUTION_DIAGNOSTICS) {
                diagnosticsPool = Executors.newSingleThreadExecutor()
                Log.i(TAG, "Диагностика атрибуции ВКЛЮЧЕНА")
            }
            Log.i(TAG, "VPN запущен: fd=" + tun.fileDescriptor)

            // обновляем список блокировки в фоне (не задерживая запуск туннеля)
            Thread {
                BlocklistRepository.refreshIfStale(applicationContext)
            }.apply {
                name = "AntiTrack-Blocklist"
                isDaemon = true
            }.start()

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
    }

    private fun buildTunnel(): ParcelFileDescriptor? {
        val configureIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Builder()
            .setSession(getString(R.string.vpn_session_name))
            .setConfigureIntent(configureIntent)
            .setMtu(MTU)
            .addAddress(TUN_IP, TUN_PREFIX)
            .addRoute(DNS_IP, DNS_ROUTE_PREFIX)
            .addDnsServer(DNS_IP)
        // DoH/DoT не должен обходить фильтр: TCP и UDP к этим адресам уводятся
        // в туннель, TCP там гаснет, а UDP:53 проходит наш DNS-фильтр.
        for (ip in bypassDnsIps) builder.addRoute(ip, 32)
        return builder.establish()
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
            relayPool?.shutdownNow()
            relayPool = null
            diagnosticsPool?.shutdownNow()
            diagnosticsPool = null
            dnsCache.clear()
            try {
                tunnel?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Ошибка при закрытии туннеля", e)
            }
            tunnel = null
            tunnelWriter = null
            Log.i(TAG, "VPN остановлен")
        }
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
    }

    private fun handlePacket(packet: ByteArray, len: Int) {
        val vhl = packet[0].toInt() and 0xFF
        if (vhl shr 4 != 4) return // только IPv4
        val ihl = (vhl and 0x0F) * 4
        if (ihl < 20 || len < ihl + 20) return
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // в туннель попадают только UDP DNS-пакеты
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = read16(packet, ihl)
        val dstPort = read16(packet, ihl + 2)
        if (dstPort != DNS_PORT) return

        val dnsStart = ihl + 8
        if (len < dnsStart + 12) return
        val qname = DnsParser.readQName(packet, dnsStart + 12) ?: return

        logAttributionSignals(srcIp, dstIp, srcPort, qname)

        val logging = RequestLog.isEnabled
        val srcIpStr = srcIp.joinToString(".")
        val dstIpStr = dstIp.joinToString(".")

        // Пользовательские правила работают по точному хосту
        if (HostRules.isBlocked(qname)) {
            val appRule = if (AppSettings.blockTrackers() && TrackerBlocklist.isBlocked(qname) ||
                AppSettings.blockAds() && AdBlocklist.isBlocked(qname) ||
                logging || activeThrottles > 0
            ) {
                attributedApp(srcIp, dstIp, srcPort)
            } else {
                null
            }
            val owner = appRule ?: BlockStats.OTHER
            blockedCount.incrementAndGet()
            BlockStats.record(owner, qname, BlockStats.Kind.TRACKER)
            Log.d(TAG, "Заблокировано пользователем: $qname")
            if (logging) RequestLog.record(owner, qname, RequestLog.Status.CUSTOM_BLOCKED, srcIpStr, dstIpStr)
            buildNxdomainResponse(packet, dnsStart)?.let {
                writeDnsResponse(srcIp, dstIp, srcPort, it)
            }
            return
        }

        val trackerCandidate = AppSettings.blockTrackers() && TrackerBlocklist.isBlocked(qname)
        val adsCandidate = AppSettings.blockAds() && AdBlocklist.isBlocked(qname)
        val hostAllowedByUser = HostRules.isAllowed(qname)

        // Автор запроса нужен для лога, per-app настроек и трекер-троттлинга.
        val app = if (trackerCandidate || adsCandidate || logging || activeThrottles > 0) {
            attributedApp(srcIp, dstIp, srcPort)
        } else {
            null
        }

        val trackersMatch = trackerCandidate &&
            (app == null || PerAppSettings.isTrackerBlockingEnabled(app))
        val adsMatch = adsCandidate

        if (trackersMatch || adsMatch) {
            if ((app != null && app in exemptPackages) || isExemptAppRequest(srcIp, dstIp, srcPort)) {
                Log.d(TAG, "Не блокируем от исключённого приложения: $qname")
            } else {
                if (hostAllowedByUser) {
                    Log.d(TAG, "Пользователь разрешил хост: $qname")
                } else {
                    val owner = app ?: BlockStats.OTHER
                    val kind = if (trackersMatch) BlockStats.Kind.TRACKER else BlockStats.Kind.ADS
                    blockedCount.incrementAndGet()
                    BlockStats.record(owner, qname, kind)
                    Log.d(TAG, "Заблокировано: $qname (${kind.name})")
                    if (trackersMatch) {
                        applyThrottle(owner)
                        if (logging) RequestLog.record(owner, qname, RequestLog.Status.TRACKER, srcIpStr, dstIpStr)
                    } else if (logging) {
                        RequestLog.record(owner, qname, RequestLog.Status.ADS, srcIpStr, dstIpStr)
                    }
                    // NXDOMAIN вместо «тишины»: клиент завершает запрос сразу, без
                    // таймаута и повторов, которые могли бы «проскочить» мимо фильтра.
                    buildNxdomainResponse(packet, dnsStart)?.let {
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
                buildNxdomainResponse(packet, dnsStart)?.let {
                    writeDnsResponse(srcIp, dstIp, srcPort, it)
                }
                return
            }
        }

        if (logging) {
            val owner = app ?: attributedApp(srcIp, dstIp, srcPort)
            RequestLog.record(owner, qname, RequestLog.Status.ALLOWED, srcIpStr, dstIpStr)
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

    private fun uidForSourcePort(sourcePort: Int): Int {
        val portHex = String.format(Locale.ROOT, "%04X", sourcePort)
        for (procPath in listOf("/proc/net/udp", "/proc/net/udp6")) {
            try {
                File(procPath).readLines().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size > 8 && parts[1].endsWith(":$portHex")) {
                        val uid = parts[7].toIntOrNull()
                        if (uid != null) return uid
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Не удалось прочитать $procPath", e)
            }
        }
        return -1
    }

    /**
     * Собирает DNS-ответ NXDOMAIN по входящему запросу: отдаём клиенту его же
     * transaction id и копию вопроса, RCODE=3 (имя не существует). Дополнительные
     * записи (EDNS OPT и т.п.) отбрасываются, чтобы алгоритм не сбоил на хвосте.
     */
    private fun buildNxdomainResponse(packet: ByteArray, dnsStart: Int): ByteArray? {
        if (dnsStart + 12 > packet.size) return null
        var i = dnsStart + 12
        while (true) {
            if (i >= packet.size) return null
            val labelLen = packet[i].toInt() and 0xFF
            if (labelLen == 0) break
            i += 1 + labelLen
        }
        val qEnd = i + 1 + 4 // нулевой байт qname + TYPE + CLASS
        if (qEnd > packet.size) return null
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
        val cached = dnsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            val response = cached.response.copyOf()
            response[0] = dnsPayload[0]
            response[1] = dnsPayload[1]
            writeDnsResponse(srcIp, dstIp, srcPort, response)
            return
        }

        var lastError: IOException? = null
        val startMs = System.currentTimeMillis()
        for (server in upstreamDnsServers) {
            if (!running) return
            val upstream = DatagramSocket()
            try {
                protect(upstream)
                upstream.soTimeout = DNS_TIMEOUT_MS
                Log.d(TAG, "DNS -> $server (len=${dnsPayload.size})")
                upstream.send(
                    DatagramPacket(dnsPayload, dnsPayload.size, InetAddress.getByName(server), DNS_PORT)
                )

                val buffer = ByteArray(DNS_MAX_RESPONSE)
                val response = DatagramPacket(buffer, buffer.size)
                upstream.receive(response)
                if (!running) return

                val answer = response.data.copyOf(response.length)
                Log.d(TAG, "DNS <- $server len=${answer.size} за ${System.currentTimeMillis() - startMs} мс")
                if (dnsCache.size >= DNS_CACHE_MAX_SIZE) pruneCache()
                dnsCache[cacheKey] = DnsCacheEntry(answer, System.currentTimeMillis() + DNS_CACHE_TTL_MS)

                writeDnsResponse(srcIp, dstIp, srcPort, answer)
                lastError = null
                break
            } catch (e: IOException) {
                lastError = e
                if (server != upstreamDnsServers.last()) {
                    Log.w(TAG, "DNS $server недоступен, пробую следующий", e)
                }
            } finally {
                upstream.close()
            }
        }
        if (lastError != null && running) {
            Log.w(TAG, "Все DNS-резолверы недоступны", lastError)
        }
    }

    /**
     * Собирает IPv4+UDP пакет с ответом (src/dst разворачиваются:
     * источник = адрес виртуального DNS, назначение = клиент) и пишет в туннель.
     */
    private fun writeDnsResponse(
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

    private fun pruneCache() {
        val now = System.currentTimeMillis()
        dnsCache.keys.removeIf { key -> dnsCache[key]?.let { it.expiresAt < now } ?: true }
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
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(blockingStatusText())
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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