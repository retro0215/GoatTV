package tv.own.owntv.core.catalog.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.catalog.versions.VersionFixtures.row

/**
 * Versions respect what the profile may see: its playlists only (several at once included), never a hidden
 * item or category, and on a Kids profile never an adult category. End to end through
 * VersionRepository.resolve: scope, matcher, labels and order.
 */
class VersionScopeTest {

    private val anchor = row(1, "Dune (2021) FHD", sourceId = 1, category = "Movies")
    private val uhdOtherPlaylist = row(2, "Dune (2021)", sourceId = 2, category = "4K MOVIES")
    private val dvSamePlaylist = row(3, "Dune (2021) 4K Dolby Vision", sourceId = 1, category = "Movies")
    private val unlinkedPlaylist = row(4, "Dune (2021) 4K", sourceId = 9, category = "4K MOVIES")
    private val adult = row(5, "Dune (2021) 4K", sourceId = 2, category = "XXX Adults 4K")
    private val rows = listOf(anchor, uhdOtherPlaylist, dvSamePlaylist, unlinkedPlaylist, adult)

    private fun scope(
        sourceIds: List<Long> = listOf(1, 2),
        hiddenItems: Set<String> = emptySet(),
        hiddenCategories: Set<String> = emptySet(),
        kids: Boolean = false,
    ) = VersionScope(sourceIds, hiddenItems, hiddenCategories, kids)

    private fun resolvedIds(scope: VersionScope) =
        VersionRepository.resolve(anchor, rows, scope).map { it.row.id }.toSet()

    @Test
    fun `versions come from every playlist of the profile, and only those`() {
        assertEquals(setOf(1L, 2L, 3L, 5L), resolvedIds(scope()))
        assertEquals("an unlinked playlist never leaks in", false, 4L in resolvedIds(scope()))
    }

    @Test
    fun `the active playlist filter narrows versions to that playlist`() {
        assertEquals(setOf(1L, 3L), resolvedIds(scope(sourceIds = listOf(1))))
    }

    @Test
    fun `a hidden item is never offered`() {
        assertEquals(setOf(1L, 3L, 5L), resolvedIds(scope(hiddenItems = setOf("2:r2"))))
    }

    @Test
    fun `an item in a hidden category is never offered`() {
        assertEquals(setOf(1L, 3L, 5L), resolvedIds(scope(hiddenCategories = setOf("2:cat-4k-movies"))))
    }

    @Test
    fun `a Kids profile never sees an adult category's version`() {
        assertEquals(setOf(1L, 2L, 3L), resolvedIds(scope(kids = true)))
    }

    @Test
    fun `the anchor stays even when its own row is outside the visible set`() {
        val versions = VersionRepository.resolve(anchor, rows, scope(sourceIds = listOf(2)))
        assertTrue(versions.any { it.isAnchor && it.row.id == 1L })
    }

    @Test
    fun `labels use the category, and the best version comes first`() {
        val versions = VersionRepository.resolve(anchor, rows, scope(kids = true))
        assertEquals(listOf(3L, 2L, 1L), versions.map { it.row.id })
        val uhd = versions.single { it.row.id == 2L }
        assertEquals("4K from its category, though the title says none", 4, uhd.label.qualityRank)
        assertEquals(AdvertisedPicture.DOLBY_VISION, versions.first().label.advertisedPicture)
        assertEquals(listOf(false, false, true), versions.map { it.isAnchor })
    }

    @Test
    fun `nothing to group gives just the anchor`() {
        val lonely = row(10, "Arrival (2016)")
        val versions = VersionRepository.resolve(lonely, listOf(lonely), scope())
        assertEquals(listOf(10L), versions.map { it.row.id })
        assertTrue(versions.single().isAnchor)
    }
}
