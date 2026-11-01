package ru.tapps.trackerblocker.stable

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerBlocklistTest {

    @After
    fun resetCustomRules() {
        TrackerBlocklist.updateCustom(emptySet())
    }

    @Test
    fun matchesBuiltinDomainAndSubdomains() {
        assertEquals("google-analytics.com", TrackerBlocklist.findMatch("google-analytics.com"))
        assertEquals("google-analytics.com", TrackerBlocklist.findMatch("www.google-analytics.com"))
        assertEquals("facebook.com", TrackerBlocklist.findMatch("graph.facebook.com."))
    }

    @Test
    fun ignoresUnknownHosts() {
        assertNull(TrackerBlocklist.findMatch("example.com"))
        assertNull(TrackerBlocklist.findMatch(""))
    }

    @Test
    fun customDomainBlocksSubdomains() {
        TrackerBlocklist.updateCustom(setOf("custom-metrics.dev"))
        assertEquals("custom-metrics.dev", TrackerBlocklist.findMatch("api.custom-metrics.dev"))
        assertTrue(TrackerBlocklist.isBlocked("deep.api.custom-metrics.dev"))
    }

    @Test
    fun customKeywordMatchesAnyHostContainingIt() {
        TrackerBlocklist.updateCustom(setOf("tracker"))
        assertEquals("tracker", TrackerBlocklist.findMatch("cdn.tracker-service.com"))
        assertEquals("tracker", TrackerBlocklist.findMatch("tracker.example.org"))
    }

    @Test
    fun resetRemovesCustomRules() {
        TrackerBlocklist.updateCustom(setOf("custom-metrics.dev"))
        TrackerBlocklist.updateCustom(emptySet())
        assertNull(TrackerBlocklist.findMatch("api.custom-metrics.dev"))
    }
}
