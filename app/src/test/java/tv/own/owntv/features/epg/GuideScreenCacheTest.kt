package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.6.0: back from full screen the Guide showed empty rows for 1–2 s on the onn: every visit started with
 * new row and text stores, so the rows on screen were prepared and their texts measured again although the
 * guide had not changed. The stores now live in the ViewModel and are reused while still valid.
 */
class GuideScreenCacheTest {

    private fun row(id: Long, revision: Int) = GuideRowData(id, emptyList(), LongArray(0), emptySet(), revision)

    @Test fun `prepared rows survive a visit and are redone only for a newer revision`() {
        val cache = GuideScreenCache()
        cache.rows.put(row(7, revision = 3))
        // Next visit, same guide (revision 3): the row is current, nothing to prepare.
        val next = cache.rows
        assertTrue(next.isCurrent(7, 3))
        assertFalse("a refreshed guide re-prepares it", next.isCurrent(7, 4))
        assertSame("the state a drawn row reads is the same object", cache.rows.state(7), next.state(7))
    }

    @Test fun `cell texts are kept for the same text style only`() {
        val cache = GuideScreenCache()
        val style = listOf(2f, 1f, "colors", "type", java.util.Locale.US, false)
        val texts = cache.texts(style, maxRows = 24)
        texts.put(7L, GuideRowTexts(key = 1L, cells = emptyMap()))
        assertSame(texts, cache.texts(listOf(2f, 1f, "colors", "type", java.util.Locale.US, false), 24))
        assertEquals(1L, cache.texts(style, 24)[7L]?.key)
        val bigger = cache.texts(listOf(2f, 1.15f, "colors", "type", java.util.Locale.US, false), 24) // font size changed
        assertNotSame(texts, bigger)
        assertNull(bigger[7L])
    }

    @Test fun `the scroll position comes back only for the same rows`() {
        val memory = GuideScrollMemory()
        val list = listOf("a", "b")
        memory.save(list, revision = 5, position = GuideScrollMemory.Position(firstRow = 40, firstRowOffset = 0, timelinePx = 1_200))
        assertEquals(40, memory.restore(list, 5)?.firstRow)
        assertEquals(1_200, memory.restore(list, 5)?.timelinePx)
        assertNull("another list (a reload, a filter)", memory.restore(listOf("a", "b"), 5))
        assertNull("the same list, a newer guide", memory.restore(list, 6))
    }

    @Test fun `a value is made once per key`() {
        val kept = KeptFor<Int, Any>()
        var made = 0
        val a = kept.get(96) { made++; Any() }
        assertSame(a, kept.get(96) { made++; Any() })
        assertNotSame(a, kept.get(120) { made++; Any() })
        assertEquals(2, made)
    }

    // ---- Wiring --------------------------------------------------------------------------------------

    private val screen = File("src/main/java/tv/own/owntv/features/epg/EpgScreen.kt").readText().replace("\r\n", "\n")

    @Test fun `the Guide screen takes its stores from the view model, not a new remember`() {
        assertTrue("internal val screenCache = GuideScreenCache()" in File("src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt").readText())
        assertTrue("val rowStore = screenCache.rows" in screen)
        assertTrue("val textStore = screenCache.texts(textStyle, ROW_TEXT_ROWS)" in screen)
        assertTrue("screenCache.logos(logoSizePx)" in screen)
        assertFalse("remember { GuideRowStore() }" in screen)
        assertFalse("remember { KeyedStates<Long, GuideRowTexts>" in screen)
        // The text style covers what measuring depends on.
        val style = screen.substringAfter("val textStyle = listOf(").substringBefore(")\n")
        for (part in listOf("density.density", "density.fontScale", "colors", "MaterialTheme.typography", "locale", "is24HourFormat")) {
            assertTrue(part, part in style)
        }
    }

    @Test fun `the Guide reopens where it was left and saves the position when it leaves`() {
        assertTrue("screenCache.scroll.restore(vm.state.value.channels, vm.cacheRevision.value)" in screen)
        assertTrue("rememberScrollState(keptScroll?.timelinePx ?: 0)" in screen)
        assertTrue("rememberLazyListState(keptScroll?.firstRow ?: 0, keptScroll?.firstRowOffset ?: 0)" in screen)
        assertTrue("screenCache.scroll.save(" in screen.substringAfter("onDispose {"))
    }

    @Test fun `kept rows count for the first-programmes timing too`() {
        val prep = screen.substringAfter("if (!rowStore.isCurrent(ch.id, cacheRevision)) {")
        assertTrue(prep.indexOf("vm.timing.firstProgrammes()") > prep.indexOf("rowStore.put(data)\n                    }"))
    }
}
