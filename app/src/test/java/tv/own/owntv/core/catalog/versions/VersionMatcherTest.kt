package tv.own.owntv.core.catalog.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.catalog.versions.VersionFixtures.grouped
import tv.own.owntv.core.catalog.versions.VersionFixtures.row

/** Which rows are versions of the same title — a synthetic catalogue through the real title parser. */
class VersionMatcherTest {

    @Test
    fun `the same movie in 1080p, 4K and Dolby Vision is one title`() {
        val fhd = row(1, "Dune (2021) FHD")
        val uhd = row(2, "Dune (2021) 4K", sourceId = 2)
        val dv = row(3, "Dune [2021] 2160p Dolby Vision", sourceId = 2)
        val all = listOf(fhd, uhd, dv)
        assertEquals("all share one signature", 1, all.map { it.titleSignature }.toSet().size)
        assertEquals(setOf(1L, 2L, 3L), grouped(fhd, all))
        assertEquals(setOf(1L, 2L, 3L), grouped(dv, all))
    }

    @Test
    fun `remakes with different years never pair`() {
        val new = row(1, "Dune (2021) 4K")
        val old = row(2, "Dune (1984)")
        assertEquals(setOf(1L), grouped(new, listOf(new, old)))
        assertEquals(setOf(2L), grouped(old, listOf(new, old)))
    }

    @Test
    fun `a yearless version joins only when the candidates agree on one year`() {
        val dated = row(1, "Dune (2021) FHD")
        val yearless = row(2, "4K | Dune Dolby Vision")
        assertEquals(setOf(1L, 2L), grouped(dated, listOf(dated, yearless)))
        assertEquals(setOf(1L, 2L), grouped(yearless, listOf(dated, yearless)))

        // A remake in the catalogue makes the yearless row ambiguous: it pairs with neither.
        val remake = row(3, "Dune (1984)")
        val all = listOf(dated, yearless, remake)
        assertEquals(setOf(1L), grouped(dated, all))
        assertEquals(setOf(2L), grouped(yearless, all))
    }

    @Test
    fun `no year anywhere is ambiguous`() {
        val a = row(1, "Arrival 4K")
        val b = row(2, "Arrival FHD")
        assertEquals(setOf(1L), grouped(a, listOf(a, b)))
    }

    @Test
    fun `sequels are different titles`() {
        val first = row(1, "Dune (2021) 4K")
        val second = row(2, "Dune: Part Two (2024) 4K")
        val toy = row(3, "Toy Story (1995)")
        val toy2 = row(4, "Toy Story 2 (1999)")
        assertTrue(first.titleSignature != second.titleSignature)
        assertTrue(toy.titleSignature != toy2.titleSignature)
        assertEquals(setOf(1L), grouped(first, listOf(first, second)))
    }

    @Test
    fun `punctuation and language prefixes do not split one title`() {
        val a = row(1, "EN| Spider-Man: No Way Home (2021) 4K")
        val b = row(2, "Spider Man No Way Home (2021)")
        assertEquals(setOf(1L, 2L), grouped(a, listOf(a, b)))
    }

    @Test
    fun `different languages are versions, labelled with their language`() {
        val en = row(1, "EN| Dune (2021) 4K")
        val de = row(2, "DE| Dune (2021) FHD")
        assertEquals(setOf(1L, 2L), grouped(en, listOf(en, de)))
        assertEquals("EN", en.providerLanguage)
        assertEquals("DE", de.providerLanguage)
    }

    @Test
    fun `short titles need an explicit, equal year on both sides`() {
        val up = row(1, "Up (2009) 4K")
        val upHd = row(2, "Up (2009) FHD")
        val upYearless = row(3, "Up FHD")
        assertTrue(VersionMatcher.needsExplicitYear(up.titleSignature))
        assertEquals(setOf(1L, 2L), grouped(up, listOf(up, upHd, upYearless)))
        assertEquals(setOf(3L), grouped(upYearless, listOf(up, upHd, upYearless)))

        val it2017 = row(4, "It (2017)")
        val it1990 = row(5, "It (1990)")
        assertEquals(setOf(4L), grouped(it2017, listOf(it2017, it1990)))
    }

    @Test
    fun `numeric titles need an explicit, equal year`() {
        val a = row(1, "300 (2006) 4K")
        val b = row(2, "300 (2006)")
        val c = row(3, "300 FHD")
        assertTrue(VersionMatcher.needsExplicitYear(a.titleSignature))
        assertEquals(setOf(1L, 2L), grouped(a, listOf(a, b, c)))
        assertEquals(setOf(3L), grouped(c, listOf(a, b, c)))
    }

    @Test
    fun `a title that is itself a year never groups`() {
        // "1917" reads as a year and cleans away to nothing: such rows are left alone, never mixed up.
        val a = row(1, "1917 (2019) 4K")
        val b = row(2, "1917 (2019)")
        assertFalse(VersionMatcher.isGroupable(a.titleSignature))
        assertEquals(setOf(1L), grouped(a, listOf(a, b)))
    }

    @Test
    fun `generic titles never group`() {
        val a = row(1, "Movie (2020) 4K")
        val b = row(2, "Movie (2020)")
        assertFalse(VersionMatcher.isGroupable(a.titleSignature))
        assertEquals(setOf(1L), grouped(a, listOf(a, b)))
    }

    @Test
    fun `a signature shared by more rows than the lookup allows is not trusted`() {
        val a = row(1, "Dune (2021) 4K")
        val b = row(2, "Dune (2021)")
        assertEquals(listOf(a), VersionMatcher.versionsOf(a, listOf(a, b), limitReached = true))
    }

    @Test
    fun `the same series in several versions is one title`() {
        // Series years come from the panel's own year field as well as the name.
        val plain = row(1, "The Boys", providerYear = 2019)
        val uhd = row(2, "The Boys 4K", providerYear = 2019, sourceId = 2)
        val multi = row(3, "The Boys (MULTI) [UHD]", providerYear = 2019)
        val all = listOf(plain, uhd, multi)
        assertEquals(setOf(1L, 2L, 3L), grouped(plain, all))
    }

    @Test
    fun `two different series with one name are kept apart by their years`() {
        val uk = row(1, "The Office (2001)")
        val us = row(2, "The Office (2005) FHD")
        assertEquals(setOf(1L), grouped(uk, listOf(uk, us)))
    }

    @Test
    fun `each version keeps its own provider id and playlist`() {
        val a = row(1, "Dune (2021) FHD", sourceId = 1, remoteId = "111")
        val b = row(2, "Dune (2021) 4K", sourceId = 2, remoteId = "222")
        val group = VersionMatcher.versionsOf(a, listOf(a, b))
        assertEquals(listOf("111", "222"), group.map { it.remoteId })
        assertEquals(listOf(1L, 2L), group.map { it.sourceId })
    }

    @Test
    fun `versions are ordered best advertised quality first, stably`() {
        val fhd = row(1, "Dune (2021) FHD")
        val dv = row(2, "Dune (2021) 4K Dolby Vision")
        val uhd = row(3, "Dune (2021) 4K")
        val ordered = VersionMatcher.ordered(listOf(fhd, dv, uhd).map { it to VersionLabels.of(it) })
        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.first.id })
    }
}
