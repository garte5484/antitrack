package ru.tapps.trackerblocker.stable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRulesTest {

    private val empty = emptySet<String>()

    @Test
    fun domainRuleBlocksItselfAndAllSubdomains() {
        val blocked = setOf("example.com")

        assertTrue(HostRules.decide("example.com", blocked, empty))
        assertTrue(HostRules.decide("ads.example.com", blocked, empty))
        assertTrue(HostRules.decide("1.ads.example.com", blocked, empty))
    }

    @Test
    fun subdomainRuleDoesNotBlockSiblings() {
        val blocked = setOf("ads.example.com")

        assertTrue(HostRules.decide("ads.example.com", blocked, empty))
        assertTrue(HostRules.decide("1.ads.example.com", blocked, empty))
        assertFalse(HostRules.decide("tracker.example.com", blocked, empty))
        assertFalse(HostRules.decide("example.com", blocked, empty))
    }

    @Test
    fun suffixLookalikesAreNotBlocked() {
        val blocked = setOf("example.com")

        assertFalse(HostRules.decide("notexample.com", blocked, empty))
        assertFalse(HostRules.decide("example.com.evil.net", blocked, empty))
    }

    @Test
    fun moreSpecificAllowWinsOverParentBlock() {
        val blocked = setOf("example.com")
        val allowed = setOf("ads.example.com")

        assertFalse(HostRules.decide("ads.example.com", blocked, allowed))
        assertFalse(HostRules.decide("1.ads.example.com", blocked, allowed))
        assertTrue(HostRules.decide("tracker.example.com", blocked, allowed))
        assertTrue(HostRules.decide("example.com", blocked, allowed))
    }

    @Test
    fun moreSpecificBlockWinsOverParentAllow() {
        val blocked = setOf("ads.example.com")
        val allowed = setOf("example.com")

        assertTrue(HostRules.decide("ads.example.com", blocked, allowed))
        assertTrue(HostRules.decide("1.ads.example.com", blocked, allowed))
        assertFalse(HostRules.decide("tracker.example.com", blocked, allowed))
    }

    @Test
    fun allowOnlyRulesUseTheSameSuffixMatching() {
        val allowed = setOf("example.com")

        assertTrue(HostRules.matchesRule("ads.example.com", allowed))
        assertTrue(HostRules.matchesRule("1.ads.example.com", allowed))
        assertFalse(HostRules.matchesRule("other.net", allowed))
    }

    @Test
    fun caseAndTrailingDotAreIgnored() {
        val blocked = setOf("example.com")

        assertTrue(HostRules.decide("ADS.Example.COM.", blocked, empty))
        assertTrue(HostRules.matchesRule("Example.com.", blocked))
    }

    @Test
    fun emptyHostIsNeverBlocked() {
        val blocked = setOf("example.com")

        assertFalse(HostRules.decide("", blocked, empty))
        assertFalse(HostRules.matchesRule("", blocked))
    }
}
