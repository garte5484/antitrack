package ru.tapps.trackerblocker.stable

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdBlocklistTest {

    @After
    fun resetCustomRules() {
        AdBlocklist.updateCustom(emptySet())
    }

    @Test
    fun matchesBuiltinDomainAndSubdomains() {
        assertEquals("adform.net", AdBlocklist.findMatch("adform.net"))
        assertEquals("adform.net", AdBlocklist.findMatch("cdn.adform.net"))
        assertEquals("appnexus.com", AdBlocklist.findMatch("ib.appnexus.com"))
    }

    @Test
    fun ignoresUnknownHosts() {
        assertNull(AdBlocklist.findMatch("example.com"))
        assertNull(AdBlocklist.findMatch(""))
    }

    @Test
    fun customRulesBlockAds() {
        AdBlocklist.updateCustom(setOf("banner-host.net", "advert"))
        assertEquals("banner-host.net", AdBlocklist.findMatch("img.banner-host.net"))
        assertEquals("advert", AdBlocklist.findMatch("cdn.advert-server.org"))
    }
}
