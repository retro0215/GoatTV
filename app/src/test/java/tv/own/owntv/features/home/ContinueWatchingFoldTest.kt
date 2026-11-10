package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.catalog.versions.VersionMatcher

/** Keep Watching and Continue Watching Movies show one card per film (4.6.1 RC2: Keep Watching showed both versions). */
class ContinueWatchingFoldTest {

    private enum class Kind { MOVIE, EPISODE, LIVE }

    private data class Card(val id: Int, val kind: Kind, val signature: String, val year: Int?, val lastUsed: Long)

    private fun fold(cards: List<Card>) = ContinueWatchingFold.fold(
        cards,
        isMovie = { it.kind == Kind.MOVIE },
        lastUsedOf = { it.lastUsed },
        groupKeyOf = { VersionMatcher.certainGroupKey(it.signature, it.year) },
    ).map { it.id }

    @Test fun `two versions of a film keep the one watched last, in its place, with episodes and channels untouched`() {
        // As Keep Watching orders them: newest first, mixed kinds.
        val cards = listOf(
            Card(1, Kind.MOVIE, "dunepart2", 2024, lastUsed = 90), // the 1080p copy, watched last
            Card(2, Kind.EPISODE, "severance", 2022, lastUsed = 80),
            Card(3, Kind.MOVIE, "dunepart2", 2024, lastUsed = 70), // the 4K copy
            Card(4, Kind.LIVE, "", null, lastUsed = 60),
            Card(5, Kind.EPISODE, "severance", 2022, lastUsed = 50), // episodes are never folded here
        )
        assertEquals(listOf(1, 2, 4, 5), fold(cards))
    }

    @Test fun `the most recently watched version wins wherever it sits`() {
        val cards = listOf(Card(1, Kind.MOVIE, "dune", 2021, 10), Card(2, Kind.MOVIE, "dune", 2021, 30))
        assertEquals(listOf(2), fold(cards))
    }

    @Test fun `remakes, yearless and generic titles are never merged`() {
        val cards = listOf(
            Card(1, Kind.MOVIE, "dune", 1984, 5),
            Card(2, Kind.MOVIE, "dune", 2021, 4),
            Card(3, Kind.MOVIE, "thething", null, 3),
            Card(4, Kind.MOVIE, "thething", null, 2),
            Card(5, Kind.MOVIE, "movie", 2020, 1),
            Card(6, Kind.MOVIE, "movie", 2020, 0),
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), fold(cards))
    }

    @Test fun `one film, or none, leaves the list as it is`() {
        assertEquals(listOf(1, 2), fold(listOf(Card(1, Kind.MOVIE, "dune", 2021, 1), Card(2, Kind.EPISODE, "x", 2020, 2))))
        assertEquals(emptyList<Int>(), fold(emptyList()))
    }

    @Test fun `identical cards are compared by identity, so neither copy is lost by equality`() {
        val a = Card(1, Kind.MOVIE, "dune", 2021, 5)
        val b = a.copy() // equal by value, a separate entry
        assertEquals(1, ContinueWatchingFold.fold(listOf(a, b), { true }, { it.lastUsed }, { VersionMatcher.certainGroupKey(it.signature, it.year) }).size)
    }
}
