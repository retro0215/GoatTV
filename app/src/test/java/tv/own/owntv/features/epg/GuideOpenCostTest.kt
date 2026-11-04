package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.ui.components.ChannelGenre
import java.io.File

/**
 * 4.6.0: the TV Guide's first frame took ~2.9 s on the onn (one frame: ~2.2 s composition, ~1.2 s layout and
 * draw). simpleperf on the main thread: category dot colours ~20 % (the adult-content check compiled its
 * regular expressions on every call, for every live category), the row filter over ~19k channels ~7 %.
 */
class GuideOpenCostTest {

    private fun src(path: String) = File("src/main/java/tv/own/owntv/$path").readText().replace("\r\n", "\n")

    // ---- The adult-content check compiles its patterns once -------------------------------------------

    @Test fun `no regular expression is compiled per call`() {
        val classifier = src("core/content/AdultCategoryClassifier.kt")
        val isAdult = classifier.substringAfter("fun isAdult(").substringBefore("\n    }\n")
        val compact = classifier.substringAfter("private fun compact(").substringBefore("\n\n").substringBefore("\n}")
        assertFalse("isAdult compiles a Regex", "Regex(" in isAdult)
        assertFalse("compact compiles a Regex", "Regex(" in compact)
        assertTrue(".replace(combiningMarks, \"\")" in isAdult)
        assertTrue(".replace(nonWordChars, \" \")" in compact && ".replace(whitespaceRuns, \" \")" in compact)
    }

    @Test fun `the same answers as before for the names a provider sends`() {
        val adult = listOf("XXX Adults", "FOR ADULTS 18+", "Erótica", "ADULTOS", "Für Erwachsene", "Playboy TV", "成人频道", "Rated R")
        val notAdult = listOf("Adult Swim", "USA | Local FOX", "Sports", "Kids", "", "Sussex News", "Adultery Dramas")
        for (n in adult) assertTrue(n, AdultCategoryClassifier.isAdult(n))
        for (n in notAdult) assertFalse(n, AdultCategoryClassifier.isAdult(n))
        assertFalse(AdultCategoryClassifier.isAdult(null))
    }

    @Test fun `genre keyword lists are built once`() {
        val genre = src("ui/components/GenreColor.kt")
        assertTrue("private fun keywords(g: ChannelGenre): List<String> = keywordLists.getValue(g)" in genre)
        assertTrue("by lazy { entries.associateWith { keywordsOf(it) } }" in genre)
        assertEquals(ChannelGenre.SPORT, ChannelGenre.fromCategory("USA | ESPN Sports"))
        assertEquals(ChannelGenre.OTHER, ChannelGenre.fromCategory("USA | Local FOX"))
    }

    // ---- Category dots: off the main thread, once per category list ----------------------------------

    @Test fun `dots by category id, only for categories with a genre`() {
        val dots = GuideCategoryDots.of(
            listOf(
                GuideCategory(key = "c1", name = "US Sports", categoryId = 1L),
                GuideCategory(key = "c2", name = "USA | Local FOX", categoryId = 2L),
                GuideCategory(key = "x", name = "Custom Sports", categoryId = null, customId = "9"),
            ),
        )
        assertEquals(setOf(1L), dots.keys)
        assertEquals(ChannelGenre.SPORT.dot, dots[1L])
    }

    @Test fun `the Guide reads the dots from the view model, worked out on a background dispatcher`() {
        val vm = src("features/epg/EpgViewModel.kt")
        val dots = vm.substringAfter("val guideCategoryDots").substringBefore("fun setCategoryFilter")
        assertTrue(".map { GuideCategoryDots.of(it) }" in dots && ".flowOn(Dispatchers.Default)" in dots)
        val screen = src("features/epg/EpgScreen.kt")
        assertTrue("val categoryDots by vm.guideCategoryDots.collectAsStateWithLifecycle()" in screen)
        assertFalse("the screen classifies no category", "ChannelGenre" in screen || "AdultCategoryClassifier" in screen)
    }

    // ---- The row filter is reused while its inputs are the same --------------------------------------

    private fun channel(id: Long) = ChannelEntity(id = id, sourceId = 1, name = "C$id", streamUrl = "")

    @Test fun `the rows are filtered again only for another list, setting or guide revision`() {
        val cache = GuideScreenCache()
        val list = listOf(channel(1), channel(2), channel(3))
        var runs = 0
        fun rows(l: List<ChannelEntity> = list, all: Boolean = false, rev: Int = 4) = cache.visibleRows(l, all, rev) { runs++; l.take(2) }
        val first = rows()
        assertSame("a reopen on the same guide", first, rows())
        assertEquals(1, runs)
        rows(rev = 5); assertEquals("the guide changed", 2, runs)
        rows(rev = 5, all = true); assertEquals("show all channels", 3, runs)
        val copy = list.toList()
        assertNotSame(first, rows(l = copy, all = true, rev = 5)); assertEquals("a new list, even an equal one", 4, runs)
    }

    @Test fun `the Guide keeps its row filter through the screen cache`() {
        val screen = src("features/epg/EpgScreen.kt")
        val rows = screen.substringAfter("val rows = remember(state.channels, showAllChannels, cacheRevision) {").substringBefore("\n    }\n")
        assertTrue("screenCache.visibleRows(state.channels, showAllChannels, cacheRevision)" in rows)
        assertTrue("GuideNav.visibleChannels(state.channels, showAllChannels) { vm.hasGuideInWindow(it) }" in rows)
    }

    // ---- The single-surface dock is untouched ----------------------------------------------------------

    @Test fun `the Guide still docks the shell's one player view`() {
        val shell = src("features/shell/OwnTVShell.kt")
        assertTrue("GuidePlayback.Surface.EXO -> { m -> tv.own.owntv.player.VideoDockTarget(guideDock, m) }" in shell)
        assertEquals(1, Regex("ExoPreviewSurface\\(").findAll(shell).count())
    }
}
