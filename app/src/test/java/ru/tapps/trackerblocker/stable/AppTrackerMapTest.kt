package ru.tapps.trackerblocker.stable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTrackerMapTest {

    private val full = AppTrackerMap.AppEntry(
        packageName = "com.example.full",
        label = "Full",
        trackers = setOf("tracker.example.com"),
        complete = true
    )

    private val partial = AppTrackerMap.AppEntry(
        packageName = "com.example.partial",
        label = "Partial",
        trackers = setOf("facebook.com"),
        complete = false
    )

    private val empty = AppTrackerMap.AppEntry(
        packageName = "com.example.empty",
        label = null,
        trackers = emptySet(),
        complete = true
    )

    private val data = AppTrackerMap.Data(
        version = 7,
        apps = listOf(full, partial, empty),
        exclusive = setOf("tracker.example.com")
    )

    private val heytap = AppTrackerMap.CompanyEntry(
        name = "heytap",
        exclusive = true,
        apps = setOf("com.heytap.market", "com.heytap.music")
    )

    private val companyData = AppTrackerMap.Data(
        version = 1,
        apps = listOf(partial),
        exclusive = emptySet(),
        companies = listOf(heytap)
    )

    @Test
    fun dataIndexesAppsByPackage() {
        assertEquals(7, data.version)
        assertEquals(full, data.byPackage["com.example.full"])
        assertEquals(3, data.byPackage.size)
    }

    @Test
    fun trackerMatchesItselfAndSubdomainsOnly() {
        assertTrue(AppTrackerMap.matchesDomain("tracker.example.com", "tracker.example.com"))
        assertTrue(AppTrackerMap.matchesDomain("1.tracker.example.com", "tracker.example.com"))
        assertFalse(AppTrackerMap.matchesDomain("other.example.com", "tracker.example.com"))
        assertFalse(AppTrackerMap.matchesDomain("nottracker.example.com", "tracker.example.com"))
        assertTrue(AppTrackerMap.matchesDomain("ADS.Facebook.COM.", "facebook.com"))
    }

    @Test
    fun knownPairIgnoresSubdomainsAndCase() {
        assertTrue(AppTrackerMap.isKnownInApp(data, "com.example.full", "tracker.example.com"))
        assertTrue(AppTrackerMap.isKnownInApp(data, "com.example.full", "1.TRACKER.example.com."))
        assertFalse(AppTrackerMap.isKnownInApp(data, "com.example.full", "facebook.com"))
        assertFalse(AppTrackerMap.isKnownInApp(data, "com.unknown", "tracker.example.com"))
    }

    @Test
    fun incompleteAppKeepsAttribution() {
        assertEquals(
            "com.example.partial",
            AppTrackerMap.statsOwner(data, "com.example.partial", "google-analytics.com")
        )
        assertEquals(
            "com.example.partial",
            AppTrackerMap.statsOwner(data, "com.example.partial", "graph.facebook.com")
        )
    }

    @Test
    fun completeAppMovesUnknownTrackerToOther() {
        assertEquals(
            "com.example.full",
            AppTrackerMap.statsOwner(data, "com.example.full", "tracker.example.com")
        )
        assertEquals(
            BlockStats.OTHER,
            AppTrackerMap.statsOwner(data, "com.example.full", "facebook.com")
        )
        assertEquals(
            BlockStats.OTHER,
            AppTrackerMap.statsOwner(data, "com.example.empty", "facebook.com")
        )
    }

    @Test
    fun unknownPackageKeepsAttributionEvenIfListLooksComplete() {
        assertEquals(
            "com.example.unknown",
            AppTrackerMap.statsOwner(data, "com.example.unknown", "facebook.com")
        )
    }

    @Test
    fun exclusiveTrackerMovesToOtherForEveryOtherApp() {
        assertEquals(
            BlockStats.OTHER,
            AppTrackerMap.statsOwner(data, "com.example.partial", "tracker.example.com")
        )
        assertEquals(
            BlockStats.OTHER,
            AppTrackerMap.statsOwner(data, "com.example.unknown", "1.tracker.example.com")
        )
    }

    @Test
    fun companyKeywordMatchesSubdomainsAndNames() {
        assertTrue(AppTrackerMap.matchesCompany("metrics.heytap.com", "heytap"))
        assertTrue(AppTrackerMap.matchesCompany("heytap.com", "heytap.com"))
        assertFalse(AppTrackerMap.matchesCompany("example.com", "heytap"))
    }

    @Test
    fun companyIsKnownOnlyForListedApps() {
        assertTrue(
            AppTrackerMap.isKnownInApp(companyData, "com.heytap.market", "metrics.heytap.com")
        )
        assertFalse(
            AppTrackerMap.isKnownInApp(companyData, "com.example.partial", "metrics.heytap.com")
        )
    }

    @Test
    fun exclusiveCompanyMovesOtherAppsToOther() {
        assertEquals(
            "com.heytap.market",
            AppTrackerMap.statsOwner(companyData, "com.heytap.market", "metrics.heytap.com")
        )
        assertEquals(
            BlockStats.OTHER,
            AppTrackerMap.statsOwner(companyData, "com.example.partial", "metrics.heytap.com")
        )
        assertEquals(
            BlockStats.OTHER,
            AppTrackerMap.statsOwner(companyData, "com.unknown", "heytap.com")
        )
    }

    @Test
    fun nonExclusiveCompanyKeepsAttribution() {
        val shared = companyData.copy(
            companies = listOf(heytap.copy(exclusive = false))
        )
        assertEquals(
            "com.example.partial",
            AppTrackerMap.statsOwner(shared, "com.example.partial", "metrics.heytap.com")
        )
    }
}
