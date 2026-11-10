package tv.own.owntv.core.catalog.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.catalog.versions.VersionFixtures.row

/** Version labels from item titles and category names. Advertised picture formats are claims, not checks. */
class VersionLabelsTest {

    private fun label(name: String, category: String? = null) = VersionLabels.of(row(1, name, category = category))

    @Test
    fun `resolution labels from titles`() {
        assertEquals(3, label("Dune (2021) 1080p").qualityRank)
        assertEquals(3, label("Dune (2021) FHD").qualityRank)
        assertEquals(4, label("Dune (2021) 4K").qualityRank)
        assertEquals(4, label("Dune (2021) UHD").qualityRank)
        assertEquals(4, label("Dune (2021) 2160p").qualityRank)
        assertEquals(0, label("Dune (2021)").qualityRank)
    }

    @Test
    fun `picture labels from titles`() {
        assertEquals(AdvertisedPicture.DOLBY_VISION, label("Dune (2021) 4K Dolby Vision").advertisedPicture)
        assertEquals(AdvertisedPicture.DOLBY_VISION, label("Dune (2021) 4K DV").advertisedPicture)
        assertEquals(AdvertisedPicture.HDR10_PLUS, label("Dune (2021) 4K HDR10+").advertisedPicture)
        assertEquals(AdvertisedPicture.HDR10, label("Dune (2021) 4K HDR10").advertisedPicture)
        assertEquals(AdvertisedPicture.HDR, label("Dune (2021) 4K HDR").advertisedPicture)
        assertNull(label("Dune (2021) 4K").advertisedPicture)
        // Dolby Atmos is sound, not picture.
        assertNull(label("Dune (2021) 4K Dolby Atmos").advertisedPicture)
    }

    @Test
    fun `category names label every title in them`() {
        assertEquals(4, label("Dune (2021)", category = "4K MOVIES").qualityRank)
        assertEquals(4, label("Dune (2021)", category = "UHD | Action").qualityRank)
        assertEquals(3, label("Dune (2021)", category = "MOVIES FHD").qualityRank)
        assertEquals(AdvertisedPicture.DOLBY_VISION, label("Dune (2021)", category = "4K DOLBY VISION").advertisedPicture)
        assertEquals(AdvertisedPicture.HDR, label("Dune (2021)", category = "HDR MOVIES").advertisedPicture)
        assertEquals(0, label("Dune (2021)", category = "Action").qualityRank)
    }

    @Test
    fun `the stronger of title and category wins`() {
        assertEquals(4, label("Dune (2021) FHD", category = "4K MOVIES").qualityRank)
        assertEquals(4, label("Dune (2021) 4K", category = "MOVIES FHD").qualityRank)
        assertEquals(AdvertisedPicture.DOLBY_VISION, label("Dune (2021) 4K HDR", category = "DOLBY VISION").advertisedPicture)
    }

    @Test
    fun `languages from titles and category names`() {
        assertEquals("EN", label("EN| Dune (2021)").language)
        assertEquals("DE", label("Dune (2021)", category = "DE | Movies").language)
        assertEquals("FR", label("Dune (2021)", category = "FRENCH MOVIES").language)
        assertEquals("ES", label("Dune (2021)", category = "Películas Español").language)
        // The title's language wins over the category's.
        assertEquals("EN", label("EN| Dune (2021)", category = "FRENCH MOVIES").language)
        assertNull(label("Dune (2021)", category = "Action").language)
    }

    @Test
    fun `words that merely contain a language name are not languages`() {
        assertNull(VersionLabels.categoryLanguage("FRENCHIE COLLECTION"))
        assertNull(VersionLabels.categoryLanguage("ENGLISHMAN SPECIALS"))
    }
}
