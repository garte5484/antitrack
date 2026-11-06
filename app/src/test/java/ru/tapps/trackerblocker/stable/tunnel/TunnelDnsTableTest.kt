package ru.tapps.trackerblocker.stable.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelDnsTableTest {

    private val now = 1_000_000L

    @Test
    fun `returns fresh mapping for ip`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "Tracker.Example.COM.", null, now, 60)

        val entries = table.candidates("1.2.3.4", now + 1_000)
        assertEquals(1, entries.size)
        assertEquals("tracker.example.com", entries[0].qname)
    }

    @Test
    fun `drops expired mapping`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "tracker.example.com", null, now, 30)

        assertTrue(table.candidates("1.2.3.4", now + 31_000).isEmpty())
    }

    @Test
    fun `keeps mappings per ip`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "first.example.com", null, now, 60)
        table.record("5.6.7.8", "second.example.com", null, now, 60)

        assertEquals("first.example.com", table.candidates("1.2.3.4", now)[0].qname)
        assertEquals("second.example.com", table.candidates("5.6.7.8", now)[0].qname)
    }

    @Test
    fun `refreshes existing mapping instead of duplicating`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "tracker.example.com", null, now, 30)
        table.record("1.2.3.4", "tracker.example.com", null, now, 600)

        val entries = table.candidates("1.2.3.4", now + 100_000)
        assertEquals(1, entries.size)
        assertTrue(entries[0].expiresAtMs > now + 500_000)
    }

    @Test
    fun `ignores empty input`() {
        val table = TunnelDnsTable()
        table.record("", "tracker.example.com", null, now, 60)
        table.record("1.2.3.4", "  ", null, now, 60)

        assertTrue(table.candidates("1.2.3.4", now).isEmpty())
    }

    @Test
    fun `keeps cname target`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "cdn.example.com", "Edge.Example.NET", now, 60)

        val entry = table.candidates("1.2.3.4", now).single()
        assertEquals("cdn.example.com", entry.qname)
        assertEquals("edge.example.net", entry.aname)
    }

    @Test
    fun `uses sni as exact host and ignores shared ip candidates`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "tracker.example.com", null, now, 60)
        table.record("1.2.3.4", "cdn.example.com", null, now, 60)

        assertEquals(
            listOf("tracker.example.com"),
            table.hostsFor("1.2.3.4", "Tracker.Example.COM.", now)
        )
    }

    @Test
    fun `sni keeps its cname target to catch cloaked trackers`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "metrics.example.com", "tracker.net", now, 60)
        table.record("1.2.3.4", "other.example.com", null, now, 60)

        assertEquals(
            listOf("metrics.example.com", "tracker.net"),
            table.hostsFor("1.2.3.4", "metrics.example.com", now)
        )
    }

    @Test
    fun `sni does not pull cname targets of other domains`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "tracker.example.com", "tracker.net", now, 60)
        table.record("1.2.3.4", "www.example.com", null, now, 60)

        assertEquals(
            listOf("www.example.com"),
            table.hostsFor("1.2.3.4", "www.example.com", now)
        )
    }

    @Test
    fun `without sni includes cname targets`() {
        val table = TunnelDnsTable()
        table.record("1.2.3.4", "cdn.example.com", "edge.tracker.net", now, 60)

        assertEquals(
            listOf("cdn.example.com", "edge.tracker.net"),
            table.hostsFor("1.2.3.4", null, now)
        )
    }

    @Test
    fun `returns no hosts when nothing is known`() {
        val table = TunnelDnsTable()
        assertTrue(table.hostsFor("1.2.3.4", null, now).isEmpty())
        assertEquals(listOf("exact.example.com"), table.hostsFor("1.2.3.4", "exact.example.com", now))
    }
}
