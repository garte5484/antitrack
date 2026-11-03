package ru.tapps.trackerblocker.stable

/**
 * Разбор DNS-сообщений: QNAME запроса, TTL и CNAME-цепочка ответа,
 * а также небольшие преобразования пакета.
 *
 * Полноценный DNS-парсер здесь не нужен: приложение работает только с
 * обычными запросами (один вопрос, UDP) и ответами на них. Всё, что не
 * укладывается в эту модель, аккуратно игнорируется.
 */
object DnsParser {

    /** Сведения из секции ответов, нужные кешу и CNAME-фильтру. */
    data class AnswerInfo(
        /** Минимальный TTL среди записей ответа в секундах; -1 — записей нет. */
        val minTtl: Long,
        /** Все CNAME-цели из ответа в порядке появления. */
        val cnames: List<String>
    )

    /**
     * Декодирует QNAME из DNS-сообщения, начиная с [offset].
     * Возвращает имя хоста или null, если пакет повреждён или это не стандартный запрос.
     * [end] — фактическая длина данных в буфере: за её пределами может лежать
     * мусор от предыдущего прочитанного пакета.
     *
     * Сжатие имён (compression pointers) в вопросах запросов обычно не используется,
     * поэтому здесь обрабатывается только простой вариант; для ответов — [readName].
     */
    fun readQName(packet: ByteArray, offset: Int, end: Int = packet.size): String? {
        var pos = offset
        val sb = StringBuilder()
        while (true) {
            if (pos >= end) return null
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xC0 == 0xC0) return null // сжатое имя — не ожидается в вопросе
            if (len and 0xC0 != 0) return null // расширенные метки не поддерживаются
            pos++
            if (pos + len > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                sb.append((packet[pos + i].toInt() and 0xFF).toChar())
            }
            pos += len
        }
        return sb.toString()
    }

    /**
     * Читает имя с поддержкой compression pointers — так устроены имена в
     * секциях ответа (CNAME-цель часто ссылается на вопрос через 0xC00C).
     */
    fun readName(packet: ByteArray, offset: Int, end: Int = packet.size): String? {
        var pos = offset
        var jumps = 0
        val sb = StringBuilder()
        while (true) {
            if (pos >= end) return null
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= end) return null
                val pointer = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                if (pointer < 12 || pointer >= end) return null
                pos = pointer
                if (++jumps > 16) return null // защита от зацикливания
                continue
            }
            if (len and 0xC0 != 0) return null
            pos++
            if (pos + len > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                sb.append((packet[pos + i].toInt() and 0xFF).toChar())
            }
            pos += len
        }
        return sb.toString()
    }

    /** Разбирает секцию ответов: минимальный TTL и все CNAME-цели. */
    fun parseAnswer(packet: ByteArray, end: Int = packet.size): AnswerInfo? {
        if (end < 12) return null
        val questions = read16(packet, 4)
        val answers = read16(packet, 6)
        var pos = 12
        repeat(questions) {
            pos = skipName(packet, pos, end) ?: return null
            pos += 4
            if (pos > end) return null
        }

        var minTtl = -1L
        val cnames = ArrayList<String>(minOf(answers, 16))
        repeat(answers) {
            pos = skipName(packet, pos, end) ?: return null
            if (pos + 10 > end) return null
            val type = read16(packet, pos)
            val ttl = readU32(packet, pos + 4)
            val rdLength = read16(packet, pos + 8)
            pos += 10
            if (pos + rdLength > end) return null
            if (type == TYPE_CNAME) {
                readName(packet, pos, end)?.let { cnames.add(it) }
            }
            if (minTtl < 0 || ttl < minTtl) minTtl = ttl
            pos += rdLength
        }
        return AnswerInfo(minTtl, cnames)
    }

    /** Тип вопроса (A, AAAA, …); -1, если пакет короче вопроса. */
    fun questionType(packet: ByteArray, dnsStart: Int, end: Int): Int {
        if (dnsStart + 12 > end) return -1
        val qEnd = skipName(packet, dnsStart + 12, end) ?: return -1
        if (qEnd + 4 > end) return -1
        return read16(packet, qEnd)
    }

    /**
     * Проверяет, что [response] действительно отвечает на [query]: совпадают
     * transaction id, выставлен QR, вопрос и его тип/класс. Это защищает кеш
     * от подмены и случайных пакетов с чужого адреса.
     */
    fun isResponseFor(
        query: ByteArray,
        queryEnd: Int,
        response: ByteArray,
        responseEnd: Int
    ): Boolean {
        if (queryEnd < 12 || responseEnd < 12) return false
        if (query[0] != response[0] || query[1] != response[1]) return false
        if (response[2].toInt() and 0x80 == 0) return false // QR=0 — это не ответ
        val queryName = readQName(query, 12, queryEnd) ?: return false
        val responseName = readName(response, 12, responseEnd) ?: return false
        if (!queryName.equals(responseName, ignoreCase = true)) return false
        val queryPos = skipName(query, 12, queryEnd) ?: return false
        val responsePos = skipName(response, 12, responseEnd) ?: return false
        if (queryPos + 4 > queryEnd || responsePos + 4 > responseEnd) return false
        return read16(query, queryPos) == read16(response, responsePos) &&
            read16(query, queryPos + 2) == read16(response, responsePos + 2)
    }

    /** Сервер сообщил, что ответ не поместился в UDP (TC=1). */
    fun isTruncated(packet: ByteArray): Boolean =
        packet.size >= 3 && (packet[2].toInt() and 0x02) != 0

    /**
     * Уменьшает заявленный в EDNS0 размер UDP-ответа и удаляет опцию
     * ECS (EDNS Client Subnet), чтобы не отдавать провайдеру подсеть
     * пользователя. Большие ответы иначе не помещаются в MTU туннеля и
     * молча теряются; 1232 байта — безопасный размер для большинства сетей.
     */
    fun sanitizeEdns(
        packet: ByteArray,
        end: Int = packet.size,
        maxPayload: Int = 1232
    ): ByteArray = rewriteEdns(packet, end, maxPayload, stripEcs = true)

    /** Только ограничивает размер EDNS0, не трогая опции. */
    fun clampEdnsPayload(
        packet: ByteArray,
        end: Int = packet.size,
        maxPayload: Int = 1232
    ): ByteArray = rewriteEdns(packet, end, maxPayload, stripEcs = false)

    private fun rewriteEdns(
        packet: ByteArray,
        end: Int,
        maxPayload: Int,
        stripEcs: Boolean
    ): ByteArray {
        val copy = packet.copyOf()
        if (end < 12) return copy
        val questions = read16(copy, 4)
        val answers = read16(copy, 6)
        val authority = read16(copy, 8)
        val additional = read16(copy, 10)
        var pos = 12
        repeat(questions) {
            pos = skipName(copy, pos, end) ?: return copy
            pos += 4
            if (pos > end) return copy
        }
        repeat(answers + authority) {
            pos = skipName(copy, pos, end) ?: return copy
            if (pos + 10 > end) return copy
            val rdLength = read16(copy, pos + 8)
            pos += 10 + rdLength
            if (pos > end) return copy
        }
        repeat(additional) {
            pos = skipName(copy, pos, end) ?: return copy
            val fixedStart = pos
            if (pos + 10 > end) return copy
            val type = read16(copy, pos)
            val klass = read16(copy, pos + 2)
            val rdLength = read16(copy, pos + 8)
            val rdataStart = pos + 10
            val rdataEnd = rdataStart + rdLength
            if (rdataEnd > end) return copy
            if (type == TYPE_OPT) {
                // В OPT-записи поле CLASS хранит размер UDP-полезной нагрузки.
                if (klass > maxPayload) write16(copy, fixedStart + 2, maxPayload)
                // Опцию имеет смысл вырезать, только если OPT — последняя
                // запись: иначе сдвиг байтов сломает compression pointers.
                if (stripEcs && rdataEnd == end) {
                    removeEcsOption(copy, fixedStart, rdataStart, rdataEnd, end)
                        ?.let { return it }
                }
                return copy
            }
            pos = rdataEnd
        }
        return copy
    }

    /**
     * Удаляет EDNS-опцию ECS (код 8), сохраняя остальные опции. Возвращает
     * новый пакет или null, если ECS не найден.
     */
    private fun removeEcsOption(
        packet: ByteArray,
        fixedStart: Int,
        rdataStart: Int,
        rdataEnd: Int,
        end: Int
    ): ByteArray? {
        var i = rdataStart
        var hasEcs = false
        var keptLength = 0
        while (i + 4 <= rdataEnd) {
            val code = read16(packet, i)
            val length = read16(packet, i + 2)
            if (i + 4 + length > rdataEnd) break
            if (code == OPT_ECS) hasEcs = true else keptLength += 4 + length
            i += 4 + length
        }
        if (!hasEcs) return null

        val kept = ByteArray(keptLength)
        i = rdataStart
        var dst = 0
        while (i + 4 <= rdataEnd) {
            val code = read16(packet, i)
            val length = read16(packet, i + 2)
            if (i + 4 + length > rdataEnd) break
            if (code != OPT_ECS) {
                System.arraycopy(packet, i, kept, dst, 4 + length)
                dst += 4 + length
            }
            i += 4 + length
        }

        val out = ByteArray(end - (rdataEnd - rdataStart) + kept.size)
        System.arraycopy(packet, 0, out, 0, rdataStart)
        System.arraycopy(kept, 0, out, rdataStart, kept.size)
        // OPT — последняя запись, поэтому после rdataEnd данных нет.
        write16(out, fixedStart + 8, kept.size)
        return out
    }

    /** Позиция сразу после имени (для compressed name — после указателя). */
    private fun skipName(packet: ByteArray, offset: Int, end: Int): Int? {
        var pos = offset
        while (true) {
            if (pos >= end) return null
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) {
                return if (pos + 1 < end) pos + 2 else null
            }
            if (len and 0xC0 != 0) return null
            pos += 1 + len
        }
    }

    private fun read16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun write16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun readU32(packet: ByteArray, offset: Int): Long =
        ((packet[offset].toLong() and 0xFF) shl 24) or
            ((packet[offset + 1].toLong() and 0xFF) shl 16) or
            ((packet[offset + 2].toLong() and 0xFF) shl 8) or
            (packet[offset + 3].toLong() and 0xFF)

    private const val TYPE_CNAME = 5
    private const val TYPE_OPT = 41
    private const val OPT_ECS = 8
}
