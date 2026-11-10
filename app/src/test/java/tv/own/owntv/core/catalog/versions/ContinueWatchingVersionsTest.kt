package tv.own.owntv.core.catalog.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.catalog.versions.VersionFixtures.row

/** Continue Watching folds two part-watched versions of one film into the one watched last — only when certain. */
class ContinueWatchingVersionsTest {

    private data class Card(val id: Long, val signature: String, val year: Int?, val lastUsed: Long)

    private fun fold(cards: List<Card>) = VersionMatcher.withoutRepeatedVersions(
        cards,
        lastUsedOf = { it.lastUsed },
        groupKeyOf = { VersionMatcher.certainGroupKey(it.signature, it.year) },
    ).map { it.id }

    @Test fun `two versions of one film keep the one watched last, in place`() {
        val hd = row(1, "Dune (2021) 1080p")
        val uhd = row(2, "Dune (2021) 4K")
        val cards = listOf(
            Card(10, "other", 2020, 50),
            Card(hd.id, hd.titleSignature, hd.parsedYear, 10),
            Card(uhd.id, uhd.titleSignature, uhd.parsedYear, 30),
        )
        assertEquals(listOf(10L, 2L), fold(cards))
    }

    @Test fun `different years and missing years are never folded`() {
        val cards = listOf(
            Card(1, "dune", 1984, 5),
            Card(2, "dune", 2021, 4),
            Card(3, "dune", null, 3),
            Card(4, "dune", null, 2),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), fold(cards))
    }

    @Test fun `generic or unparsed titles are never folded`() {
        assertNull(VersionMatcher.certainGroupKey("movie", 2020))
        assertNull(VersionMatcher.certainGroupKey("", 2020))
        assertNull(VersionMatcher.certainGroupKey("__unmatchable_7", 2020))
        assertEquals(listOf(1L, 2L), fold(listOf(Card(1, "movie", 2020, 1), Card(2, "movie", 2020, 2))))
    }

    @Test fun `a single card and an empty row are unchanged`() {
        assertEquals(listOf(1L), fold(listOf(Card(1, "dune", 2021, 1))))
        assertEquals(emptyList<Long>(), fold(emptyList()))
    }
}
