package ru.tapps.trackerblocker.stable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsParserTest {

    /** DNS-запрос: 12-байтный заголовок, QNAME, TYPE и CLASS. */
    private fun query(name: String, extra: Int = 0): ByteArray {
        val labels = name.split('.')
        val qnameLen = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(12 + qnameLen + 4 + extra)
        out[5] = 1 // QDCOUNT=1
        var pos = 12
        for (label in labels) {
            out[pos++] = label.length.toByte()
            for (ch in label) out[pos++] = ch.code.toByte()
        }
        out[pos] = 0
        out[pos + 1] = 0
        out[pos + 2] = 1 // TYPE=A
        out[pos + 3] = 1 // CLASS=IN
        return out
    }

    private fun write16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun write32(packet: ByteArray, offset: Int, value: Long) {
        packet[offset] = (value ushr 24).toByte()
        packet[offset + 1] = (value ushr 16).toByte()
        packet[offset + 2] = (value ushr 8).toByte()
        packet[offset + 3] = value.toByte()
    }

    private fun encodeName(name: String): ByteArray {
        val labels = name.split('.')
        val out = ByteArray(labels.sumOf { it.length + 1 } + 1)
        var pos = 0
        for (label in labels) {
            out[pos++] = label.length.toByte()
            for (ch in label) out[pos++] = ch.code.toByte()
        }
        return out
    }

    @Test
    fun readsPlainName() {
        assertEquals("metrics.yandex.ru", DnsParser.readQName(query("metrics.yandex.ru"), 12))
    }

    @Test
    fun ignoresBytesPastPacketEnd() {
        val packet = query("ads.example.com", extra = 32)
        // мусор в хвосте буфера не должен попасть в имя
        for (i in packet.size - 32 until packet.size) packet[i] = 0x3F

        val end = packet.size - 32
        assertEquals("ads.example.com", DnsParser.readQName(packet, 12, end))
    }

    @Test
    fun failsWhenNameIsCutByEnd() {
        val packet = query("tracker.example.com")
        assertNull(DnsParser.readQName(packet, 12, 20))
    }

    @Test
    fun rejectsCompressionPointer() {
        val packet = query("example.com")
        packet[12] = 0xC0.toByte()
        assertNull(DnsParser.readQName(packet, 12))
    }

    @Test
    fun readsCompressedName() {
        val packet = query("example.com")
        val withPointer = packet + byteArrayOf(0xC0.toByte(), 0x0C)
        assertEquals(
            "example.com",
            DnsParser.readName(withPointer, packet.size, withPointer.size)
        )
    }

    @Test
    fun parsesTtlAndCnameChain() {
        val name = "metrics.example.com"
        val q = query(name)
        val target = encodeName("tracker.example.com")
        val response = ByteArray(q.size + 12 + target.size + 12 + 4)
        q.copyInto(response)
        write16(response, 2, 0x8180) // QR, RD, RA, NOERROR
        write16(response, 6, 2)      // ANCOUNT=2
        var pos = q.size
        // CNAME: owner — указатель на вопрос, TTL 300
        response[pos] = 0xC0.toByte()
        response[pos + 1] = 0x0C
        write16(response, pos + 2, 5)
        write16(response, pos + 4, 1)
        write32(response, pos + 6, 300)
        write16(response, pos + 10, target.size)
        target.copyInto(response, pos + 12)
        pos += 12 + target.size
        // A: owner — указатель, TTL 60
        response[pos] = 0xC0.toByte()
        response[pos + 1] = 0x0C
        write16(response, pos + 2, 1)
        write16(response, pos + 4, 1)
        write32(response, pos + 6, 60)
        write16(response, pos + 10, 4)
        response[pos + 12] = 1
        response[pos + 13] = 2
        response[pos + 14] = 3
        response[pos + 15] = 4

        val info = DnsParser.parseAnswer(response)
        assertEquals(60L, info?.minTtl)
        assertEquals(listOf("tracker.example.com"), info?.cnames)
    }

    @Test
    fun validatesResponseMatchesQuery() {
        val q = query("example.com")
        val response = q.copyOf()
        write16(response, 2, 0x8180)
        assertTrue(DnsParser.isResponseFor(q, q.size, response, response.size))
        write16(response, 0, 0x9999)
        assertFalse(DnsParser.isResponseFor(q, q.size, response, response.size))
    }

    @Test
    fun clampsEdnsPayload() {
        val q = query("example.com")
        val packet = ByteArray(q.size + 11)
        q.copyInto(packet)
        write16(packet, 10, 1) // ARCOUNT=1
        var pos = q.size
        packet[pos++] = 0      // корневое имя OPT
        write16(packet, pos, 41) // TYPE=OPT
        write16(packet, pos + 2, 4096) // заявленный UDP payload
        write16(packet, pos + 8, 0)    // RDLENGTH=0

        val clamped = DnsParser.clampEdnsPayload(packet, packet.size, 1232)
        assertEquals(1232, (clamped[pos + 2].toInt() and 0xFF) shl 8 or
            (clamped[pos + 3].toInt() and 0xFF))
    }

    @Test
    fun stripsEcsOptionButKeepsOthers() {
        val q = query("example.com")
        // EDNS Client Subnet: family=1, source prefix=24, scope=0.
        val ecs = byteArrayOf(0, 8, 0, 4, 0, 1, 24, 0)
        // Произвольная опция, которую трогать нельзя.
        val other = byteArrayOf(0xFD.toByte(), 0xE9.toByte(), 0, 2, 1, 2)
        val rdataLen = ecs.size + other.size
        val packet = ByteArray(q.size + 11 + rdataLen)
        q.copyInto(packet)
        write16(packet, 10, 1)
        var pos = q.size
        packet[pos++] = 0
        write16(packet, pos, 41)
        write16(packet, pos + 2, 4096)
        write16(packet, pos + 8, rdataLen)
        ecs.copyInto(packet, pos + 10)
        other.copyInto(packet, pos + 10 + ecs.size)

        val sanitized = DnsParser.sanitizeEdns(packet, packet.size, 1232)
        assertEquals(packet.size - ecs.size, sanitized.size)
        assertEquals(other.size, (sanitized[pos + 8].toInt() and 0xFF) shl 8 or
            (sanitized[pos + 9].toInt() and 0xFF))
        // Оставшаяся опция начинается сразу после OPT-заголовка.
        assertEquals(0xFD, sanitized[pos + 10].toInt() and 0xFF)
        assertEquals(0xE9, sanitized[pos + 11].toInt() and 0xFF)
    }

    @Test
    fun readsQuestionType() {
        val q = query("example.com")
        assertEquals(1, DnsParser.questionType(q, 0, q.size))
        assertEquals(-1, DnsParser.questionType(q, 0, 14))
    }
}
