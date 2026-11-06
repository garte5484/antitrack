package ru.tapps.trackerblocker.stable.tunnel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Таблица «IP → домены», собранная из DNS-ответов, которые нативный стек
 * разобрал в режиме полного туннеля.
 *
 * Зачем она нужна: в туннель приходит уже готовое TCP/UDP-соединение, и
 * приложение-владелец известно точно (UID сокета), а вот домен — нет. По
 * адресату ищем в таблице, какие имена недавно резолвились в этот IP, и
 * проверяем их по спискам трекеров. Это тот же приём, что в TrackerControl.
 *
 * Записи живут по TTL из DNS-ответа; просроченные удаляются при чтении.
 * Таблица потокобезопасна: пишет и читает нативный поток, а список под
 * блокировкой защищает от гонок при одновременном обновлении TTL.
 */
internal class TunnelDnsTable {

    data class Entry(
        val qname: String,
        val aname: String?,
        val expiresAtMs: Long
    )

    private val byIp = ConcurrentHashMap<String, ArrayList<Entry>>()
    private val writes = AtomicLong()

    /**
     * Добавляет ответ. [timeMs] — время получения в миллисекундах (как его
     * отдаёт NetGuard), [ttlSec] — TTL из ответа.
     */
    fun record(ip: String, qname: String, aname: String?, timeMs: Long, ttlSec: Int) {
        val host = qname.lowercase().trim().trimEnd('.')
        if (ip.isBlank() || host.isEmpty()) return
        val ttl = ttlSec.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC)
        val base = if (timeMs > 0) timeMs else System.currentTimeMillis()
        val expires = base + ttl * 1000L
        val entry = Entry(
            host,
            aname?.lowercase()?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() },
            expires
        )

        val list = byIp.computeIfAbsent(ip) { ArrayList() }
        synchronized(list) {
            // Не плодим одинаковые записи: обновляем TTL существующей.
            val existing = list.indexOfFirst { it.qname == entry.qname && it.aname == entry.aname }
            if (existing >= 0) {
                list[existing] = entry
            } else {
                list.add(entry)
                if (list.size > MAX_ENTRIES_PER_IP) {
                    // Оставляем самые свежие: старые записи уже мало полезны.
                    list.sortByDescending { it.expiresAtMs }
                    while (list.size > MAX_ENTRIES_PER_IP) list.removeAt(list.size - 1)
                }
            }
        }
        // Раз в несколько сотен записей вычищаем IP, к которым больше не
        // обращались: иначе таблица росла бы вместе с числом резолвнутых имён.
        if (writes.incrementAndGet() % CLEANUP_EVERY == 0L) cleanup(System.currentTimeMillis())
    }

    /** Удаляет просроченные записи по всем IP. */
    private fun cleanup(now: Long) {
        val iterator = byIp.entries.iterator()
        while (iterator.hasNext()) {
            val list = iterator.next().value
            synchronized(list) {
                list.removeAll { it.expiresAtMs <= now }
                if (list.isEmpty()) iterator.remove()
            }
        }
    }

    /** Свежие записи для IP; просроченные вычищаются. */
    fun candidates(ip: String, now: Long = System.currentTimeMillis()): List<Entry> {
        val list = byIp[ip] ?: return emptyList()
        synchronized(list) {
            list.removeAll { it.expiresAtMs <= now }
            if (list.isEmpty()) {
                byIp.remove(ip, list)
                return emptyList()
            }
            return ArrayList(list)
        }
    }

    /**
     * Домены-кандидаты для потока к [ip].
     *
     * Если нативный стек распознал SNI (TLS ClientHello), это точное имя
     * соединения: остальные записи для этого IP игнорируются, иначе общий
     * IP с десятком доменов «размывает» решение. Исключение — CNAME-цель
     * именно этого SNI: при CNAME-маскировке клиент открывает first-party
     * имя, а A-запись приходит на tracker-домен. Без неё такие трекеры
     * проскакивают, хотя в DNS-режиме блокируются.
     *
     * Если SNI нет, берём все недавние имена, включая CNAME-цели: A-запись
     * могла прийти не на исходный qname, а на конечное имя цепочки.
     */
    fun hostsFor(
        ip: String,
        sni: String? = null,
        now: Long = System.currentTimeMillis()
    ): List<String> {
        val exact = sni?.lowercase()?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
            ?: return candidates(ip, now)
                .flatMap { listOfNotNull(it.qname, it.aname) }
                .distinct()

        val result = LinkedHashSet<String>()
        result.add(exact)
        for (entry in candidates(ip, now)) {
            if (entry.qname == exact && entry.aname != null) result.add(entry.aname)
        }
        return result.toList()
    }

    fun clear() {
        byIp.clear()
    }

    private companion object {
        const val MIN_TTL_SEC = 5
        const val MAX_TTL_SEC = 24 * 60 * 60
        const val MAX_ENTRIES_PER_IP = 32
        const val CLEANUP_EVERY = 256L
    }
}
