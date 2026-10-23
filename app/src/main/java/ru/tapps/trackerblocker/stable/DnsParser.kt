package ru.tapps.trackerblocker.stable

/**
 * Минимальный парсер DNS-сообщений, достаточный для извлечения
 * запрашиваемого имени хоста (QNAME) из вопроса (question section) запроса.
 */
object DnsParser {

    /**
     * Декодирует QNAME из DNS-сообщения, начиная с [offset].
     * Возвращает имя хоста или null, если пакет повреждён или это не стандартный запрос.
     *
     * Сжатие имён (compression pointers) в вопросах запросов обычно не используется,
     * поэтому здесь обрабатывается только простой вариант; для ответов мы пакет не разбираем.
     */
    fun readQName(packet: ByteArray, offset: Int): String? {
        var pos = offset
        val sb = StringBuilder()
        while (true) {
            if (pos >= packet.size) return null
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xC0 == 0xC0) return null // сжатое имя — не ожидается в вопросе
            if (len and 0xC0 != 0) return null // расширенные метки не поддерживаются
            pos++
            if (pos + len > packet.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                sb.append((packet[pos + i].toInt() and 0xFF).toChar())
            }
            pos += len
        }
        return sb.toString()
    }
}