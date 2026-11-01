package ru.tapps.trackerblocker.stable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistRepositoryTest {

    @Test
    fun normalizeKeepsDomainsAndLowercases() {
        assertEquals("example.com", BlocklistRepository.normalize("Example.COM"))
        assertEquals("example.com", BlocklistRepository.normalize("  example.com.  "))
    }

    @Test
    fun normalizeKeepsKeywords() {
        assertEquals("track", BlocklistRepository.normalize("Track"))
        assertEquals("ad-server", BlocklistRepository.normalize("AD-Server"))
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertNull(BlocklistRepository.normalize(""))
        assertNull(BlocklistRepository.normalize("https://example.com/path"))
        assertNull(BlocklistRepository.normalize("some/path"))
        assertNull(BlocklistRepository.normalize("*.example.com"))
        assertNull(BlocklistRepository.normalize("example.*"))
        assertNull(BlocklistRepository.normalize("0.0.0.0"))
        assertNull(BlocklistRepository.normalize("127.0.0.1"))
        assertNull(BlocklistRepository.normalize("localhost"))
        assertNull(BlocklistRepository.normalize("localhost.localdomain"))
        assertNull(BlocklistRepository.normalize("ab"))
        assertNull(BlocklistRepository.normalize("bad!keyword"))
    }

    @Test
    fun parseHandlesHostsCommentsAndAdBlockSyntax() {
        val text = """
            # комментарий
            0.0.0.0 ads.example.com
            127.0.0.1 localhost
            ||tracker.example.net^
            plain.example.org
            https://bad.example.com/path
            *.wild.example.com
            MyKeyword
        """.trimIndent()

        val parsed = BlocklistRepository.parse(text)

        assertTrue("ads.example.com" in parsed)
        assertTrue("tracker.example.net" in parsed)
        assertTrue("plain.example.org" in parsed)
        assertTrue("mykeyword" in parsed)
        assertEquals(4, parsed.size)
    }

    @Test
    fun parseIgnoresInlineCommentsAndDuplicates() {
        val parsed = BlocklistRepository.parse(
            """
            example.com # основной домен
            example.com
            EXAMPLE.COM.
            """.trimIndent()
        )

        assertEquals(setOf("example.com"), parsed)
    }
}
