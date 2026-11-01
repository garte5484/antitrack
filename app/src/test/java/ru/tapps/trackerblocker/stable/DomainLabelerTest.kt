package ru.tapps.trackerblocker.stable

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainLabelerTest {

    @Test
    fun stripsSingleZone() {
        assertEquals("yandex", DomainLabeler.rootLabel("yandex.ru"))
        assertEquals("google", DomainLabeler.rootLabel("metrics.google.com"))
        assertEquals("youtube", DomainLabeler.rootLabel("a.b.youtube.com"))
    }

    @Test
    fun stripsMultiPartZone() {
        assertEquals("example", DomainLabeler.rootLabel("www.example.co.uk"))
        assertEquals("example", DomainLabeler.rootLabel("shop.example.com.au"))
    }

    @Test
    fun unknownZoneFallsBackToLastLabel() {
        assertEquals("example", DomainLabeler.rootLabel("sub.example"))
        assertEquals("localhost", DomainLabeler.rootLabel("localhost"))
    }

    @Test
    fun emptyHost() {
        assertEquals("unknown", DomainLabeler.rootLabel(""))
        assertEquals("unknown", DomainLabeler.rootLabel("."))
    }
}
