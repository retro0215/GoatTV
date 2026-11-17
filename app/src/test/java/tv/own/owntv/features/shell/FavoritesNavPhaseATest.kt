package tv.own.owntv.features.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.ui.components.NavGlyph
import tv.own.owntv.ui.components.navGlyphFor
import java.io.File

/** Search/Favorites project, Phase A: Favorites first in the rail, Home stays home, football Sports icon. */
class FavoritesNavPhaseATest {

    private val before = listOf(
        MainSection.HOME, MainSection.LIVE_TV, MainSection.MOVIES, MainSection.SERIES,
        MainSection.SPORTS, MainSection.SOCIAL_ROOMS, MainSection.DOWNLOADS, MainSection.EPG,
    )

    // --- Order ---------------------------------------------------------------------------------------

    @Test fun `favorites is first and home is directly after it`() {
        val order = MainSection.browseOrderFor(favoritesEnabled = true)
        assertEquals(MainSection.FAVORITES, order[0])
        assertEquals(MainSection.HOME, order[1])
    }

    @Test fun `every existing entry below home keeps its order`() {
        assertEquals(before, MainSection.browseOrderFor(favoritesEnabled = true).drop(1))
    }

    @Test fun `the GoatTV build under test has it in the rail`() {
        assertTrue(tv.own.owntv.BuildConfig.NAV_FAVORITES_SEARCH_TABS)
        assertEquals(MainSection.FAVORITES, MainSection.browseOrder.first())
        assertTrue(MainSection.FAVORITES in MainSection.allBrowse)
    }

    // --- Home stays the launch destination and the fallback ------------------------------------------

    @Test fun `home is the launch destination`() {
        assertEquals(MainSection.HOME, MainSection.LAUNCH)
    }

    @Test fun `fallback goes to home, not the first rail item`() {
        val visible = MainSection.browseOrderFor(true).toSet()
        assertEquals(MainSection.HOME, MainSection.fallback(visible, MainSection.browseOrderFor(true)))
    }

    @Test fun `fallback without home takes the first visible item, then settings`() {
        val order = MainSection.browseOrderFor(true)
        assertEquals(MainSection.FAVORITES, MainSection.fallback(setOf(MainSection.FAVORITES, MainSection.MOVIES), order))
        assertEquals(MainSection.MOVIES, MainSection.fallback(setOf(MainSection.MOVIES), order))
        assertEquals(MainSection.SETTINGS, MainSection.fallback(emptySet(), order))
    }

    // --- STATIC / DYNAMIC visibility ------------------------------------------------------------------

    @Test fun `static mode shows favorites unless the customer hides it`() {
        val all = MainSection.browseOrderFor(true).toSet()
        assertTrue(MainSection.FAVORITES in all - emptySet())                      // default: nothing hidden
        assertFalse(MainSection.FAVORITES in all - setOf(MainSection.FAVORITES))   // hidden in the editor
        assertTrue("an old hidden set from before Favorites existed hides nothing new",
            MainSection.FAVORITES in all - setOf(MainSection.SPORTS, MainSection.DOWNLOADS))
    }

    @Test fun `dynamic mode always shows favorites, even with no content and no favorites`() {
        assertTrue(MainSection.FAVORITES in MainSection.dynamicVisible(false, false, false, favoritesEnabled = true))
        assertTrue(MainSection.FAVORITES in MainSection.dynamicVisible(true, true, true, favoritesEnabled = true))
        assertEquals(setOf(MainSection.FAVORITES, MainSection.HOME), MainSection.alwaysVisible(favoritesEnabled = true))
    }

    @Test fun `dynamic content rules are otherwise unchanged`() {
        val v = MainSection.dynamicVisible(hasLive = true, hasMovies = false, hasSeries = false, favoritesEnabled = true)
        assertEquals(
            setOf(MainSection.FAVORITES, MainSection.HOME, MainSection.SOCIAL_ROOMS, MainSection.LIVE_TV, MainSection.EPG, MainSection.SPORTS),
            v,
        )
    }

    // --- Flag off: the other three brands ------------------------------------------------------------

    @Test fun `with the flag off the rail is exactly as before`() {
        assertEquals(before, MainSection.browseOrderFor(favoritesEnabled = false))
        assertFalse(MainSection.FAVORITES in MainSection.dynamicVisible(false, false, false, favoritesEnabled = false))
        assertFalse(MainSection.FAVORITES in MainSection.dynamicVisible(true, true, true, favoritesEnabled = false))
        assertEquals(setOf(MainSection.HOME), MainSection.alwaysVisible(favoritesEnabled = false))
    }

    @Test fun `with the flag off sports keeps its star`() {
        assertEquals(NavGlyph.STAR, navGlyphFor(MainSection.SPORTS, favoritesNavEnabled = false))
    }

    @Test fun `only the goat flavor enables the flag`() {
        val text = (File("build.gradle.kts").takeIf { it.exists() } ?: File("app/build.gradle.kts")).readText()
        val on = Regex("""buildConfigField\("boolean", "NAV_FAVORITES_SEARCH_TABS", "true"\)""").findAll(text).toList()
        assertEquals(1, on.size)
        val goat = text.indexOf("create(\"goat\")", text.indexOf("productFlavors {"))
        val next = listOf("create(\"fivestar\")", "create(\"supreme\")", "create(\"allaccess\")")
            .map { text.indexOf(it, goat + 1) }.filter { it > 0 }.minOrNull() ?: text.length
        assertTrue(on.single().range.first in goat until next)
        assertTrue(text.contains("""buildConfigField("boolean", "NAV_FAVORITES_SEARCH_TABS", "false")"""))
    }

    // --- Icons ---------------------------------------------------------------------------------------

    @Test fun `favorites uses the star and sports the football`() {
        assertEquals(NavGlyph.STAR, navGlyphFor(MainSection.FAVORITES, favoritesNavEnabled = true))
        assertEquals(NavGlyph.FOOTBALL, navGlyphFor(MainSection.SPORTS, favoritesNavEnabled = true))
        assertEquals(null, navGlyphFor(MainSection.HOME, favoritesNavEnabled = true))
    }

    // --- Search entry point and Settings editor ------------------------------------------------------

    @Test fun `search stays out of the rail - it lives in the top bar`() {
        assertFalse(MainSection.SEARCH.isBrowse)
        assertFalse(MainSection.SEARCH in MainSection.browseOrderFor(true))
        assertTrue("favorites is a rail item", MainSection.FAVORITES.isBrowse)
    }

    @Test fun `the settings navigation editor lists favorites first`() {
        // NavMenuSettingsScreen iterates MainSection.browseOrder, so the new row appears at the top of the
        // icon list and toggles through the same hidden set as every other row.
        val src = File("src/main/java/tv/own/owntv/features/settings/NavMenuSettingsScreen.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/tv/own/owntv/features/settings/NavMenuSettingsScreen.kt")
        assertTrue(src.readText().contains("MainSection.browseOrder.forEach"))
        assertEquals(MainSection.FAVORITES, MainSection.browseOrder.first())
    }
}
