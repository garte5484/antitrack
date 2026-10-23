package ru.tapps.trackerblocker.stable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerPatch() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.10", "1.0.9"))
    }

    @Test
    fun newerMinorAndMajor() {
        assertTrue(UpdateChecker.isNewer("1.1", "1.0"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("v3.2.1", "3.2.0"))
    }

    @Test
    fun sameOrOlder() {
        assertFalse(UpdateChecker.isNewer("1.0", "1.0"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.1"))
    }

    @Test
    fun ignoresNonNumericSuffixes() {
        assertTrue(UpdateChecker.isNewer("1.2.0-beta", "1.1"))
        assertFalse(UpdateChecker.isNewer("1.2.0-beta", "1.2.0"))
    }
}
