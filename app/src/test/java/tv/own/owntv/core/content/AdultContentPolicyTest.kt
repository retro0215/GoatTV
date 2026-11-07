package tv.own.owntv.core.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.model.MediaType

class AdultContentPolicyTest {

    private fun category(id: Long, name: String) = CategoryEntity(id = id, sourceId = 1, mediaType = MediaType.MOVIE, name = name)
    private fun movie(id: Long, categoryId: Long?, name: String = "Movie $id") =
        MovieEntity(id = id, sourceId = 1, categoryId = categoryId, name = name, streamUrl = "")

    /** The real provider's categories (2026-10): platform buckets plus one adult VOD category. */
    private val providerCategories = listOf(
        category(1, "2026 Releases"),
        category(2, "4K Movies"),
        category(3, "Netflix"),
        category(4, "Amazon Prime Video"),
        category(35, "Unlabeled Movies"),
        category(36, "18+ | Nutflix 🔞"),
    )

    // --- category evidence ---

    @Test
    fun `the provider's adult category is excluded and every ordinary category is retained`() {
        assertEquals(setOf(36L), AdultContentPolicy.adultCategoryIds(providerCategories))
    }

    @Test
    fun `mixed-case and decorated adult category names are recognised`() {
        listOf("XXX", "xXx Adults", "ADULT movies", "Adults 18+", "18+ | Nutflix 🔞", "VOD | PoRn").forEach { name ->
            assertEquals(name, setOf(9L), AdultContentPolicy.adultCategoryIds(listOf(category(9, name))))
        }
    }

    @Test
    fun `category-id evidence - items are judged by their category id, not their title`() {
        val adultIds = AdultContentPolicy.adultCategoryIds(providerCategories)
        assertTrue(AdultContentPolicy.isAdultContent(36, adultIds))
        assertFalse(AdultContentPolicy.isAdultContent(3, adultIds))
    }

    @Test
    fun `missing category metadata is not treated as adult`() {
        val adultIds = AdultContentPolicy.adultCategoryIds(providerCategories)
        assertFalse(AdultContentPolicy.isAdultContent(null, adultIds))
        assertFalse(AdultContentPolicy.isAdultContent(null, emptySet()))
        assertFalse(AdultContentPolicy.isAdultContent(999, adultIds)) // category no longer listed
    }

    @Test
    fun `mainstream titles containing adult words in normal categories are not excluded`() {
        val adultIds = AdultContentPolicy.adultCategoryIds(providerCategories)
        listOf(
            movie(1, 4, "xXx: Return of Xander Cage (2017)"),
            movie(2, 3, "Money Shot: The Pornhub Story (2023)"),
            movie(3, 35, "Sex Education"),
            movie(4, 3, "Adult Swim Presents"),
        ).forEach { assertFalse(it.name, AdultContentPolicy.isAdultContent(it.categoryId, adultIds)) }
    }

    @Test
    fun `explicit provider adult flag counts, but a false flag never clears an adult category`() {
        val adultIds = AdultContentPolicy.adultCategoryIds(providerCategories)
        assertTrue(AdultContentPolicy.isAdultContent(3, adultIds, providerAdultFlag = true))
        // Measured: the real provider sends is_adult = "0" for all 692 items in its adult category.
        assertTrue(AdultContentPolicy.isAdultContent(36, adultIds, providerAdultFlag = false))
        assertFalse(AdultContentPolicy.isAdultContent(3, adultIds, providerAdultFlag = false))
    }

    // --- rail filling ---

    /** Newest-first catalogue like the real one after its sync: 692 adult films newer than everything else. */
    private val catalogue = (1L..692L).map { movie(100_000 + it, 36) } + (1L..300L).map { movie(it, (it % 5) + 1) }

    private class Pager(private val items: List<MovieEntity>) {
        val calls = mutableListOf<Pair<Int, Int>>()
        suspend fun page(offset: Int, count: Int): List<MovieEntity> {
            calls += offset to count
            return items.drop(offset).take(count)
        }
    }

    private val adultIds = AdultContentPolicy.adultCategoryIds(providerCategories)
    private val isAdult = { m: MovieEntity -> AdultContentPolicy.isAdultContent(m.categoryId, adultIds) }

    @Test
    fun `recently added backfills past 692 adult items - still 20 titles, in order, none adult`() = runBlocking {
        val pager = Pager(catalogue)
        val rail = AdultContentPolicy.fillRail(20, isAdult, fetchPage = pager::page)
        assertEquals(20, rail.size)
        assertTrue(rail.none(isAdult))
        assertEquals(catalogue.filterNot(isAdult).take(20), rail) // same order as the newest-first source
        assertEquals(20, pager.calls.first().second) // first page is the rail size
    }

    @Test
    fun `8 adult among the newest 20 - the rail still holds 20, the next eligible titles fill the gaps`() = runBlocking {
        val mixed = (1L..40L).map { movie(it, if (it in setOf(2L, 3L, 5L, 7L, 11L, 13L, 17L, 19L)) 36 else 3) }
        val rail = AdultContentPolicy.fillRail(20, isAdult, fetchPage = Pager(mixed)::page)
        assertEquals(20, rail.size)
        assertTrue(rail.none(isAdult))
        assertEquals(mixed.filterNot(isAdult).take(20).map { it.id }, rail.map { it.id })
    }

    @Test
    fun `nothing to exclude costs a single query of the rail size, exactly as before`() = runBlocking {
        val pager = Pager(catalogue.filterNot(isAdult))
        val rail = AdultContentPolicy.fillRail(20, { _: MovieEntity -> false }, fetchPage = pager::page)
        assertEquals(20, rail.size)
        assertEquals(listOf(0 to 20), pager.calls)
    }

    @Test
    fun `fewer eligible titles than the rail size - returns what exists, no blanks`() = runBlocking {
        val small = (1L..30L).map { movie(it, 36) } + (1L..7L).map { movie(1_000 + it, 3) }
        val rail = AdultContentPolicy.fillRail(20, isAdult, fetchPage = Pager(small)::page)
        assertEquals(7, rail.size)
        assertTrue(rail.none(isAdult))
    }

    @Test
    fun `an all-adult source is scanned at most maxScan items`() = runBlocking {
        val pager = Pager((1L..50_000L).map { movie(it, 36) })
        val rail = AdultContentPolicy.fillRail(20, isAdult, maxScan = 1_000, fetchPage = pager::page)
        assertTrue(rail.isEmpty())
        assertTrue(pager.calls.sumOf { it.second } <= 1_000)
    }

    @Test
    fun `home rails built from the real-shaped catalogue contain zero adult entries`() = runBlocking {
        // Recently Added (20) and the Top 10 candidate pools (50 top rated, 50 recently added) all page
        // through the same policy.
        val recent = AdultContentPolicy.fillRail(20, isAdult, fetchPage = Pager(catalogue)::page)
        val topRatedPool = AdultContentPolicy.fillRail(50, isAdult, fetchPage = Pager(catalogue.reversed())::page)
        val recentPool = AdultContentPolicy.fillRail(50, isAdult, fetchPage = Pager(catalogue)::page)
        listOf(recent, topRatedPool, recentPool).forEach { rail ->
            assertTrue(rail.isNotEmpty())
            assertEquals(0, rail.count(isAdult))
        }
    }

    @Test
    fun `favorites and history are read, never modified - filtering is a pure view`() = runBlocking {
        val favorites = mutableListOf(movie(1, 36), movie(2, 3))
        val history = mutableListOf(movie(3, 36), movie(4, 4))
        val favoritesBefore = favorites.toList()
        val historyBefore = history.toList()
        val watchedForTop = history.filterNot(isAdult)
        AdultContentPolicy.fillRail(10, isAdult) { offset, count -> favorites.drop(offset).take(count) }
        assertEquals(listOf(4L), watchedForTop.map { it.id })
        assertEquals(favoritesBefore, favorites)
        assertEquals(historyBefore, history)
    }
}
