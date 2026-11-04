package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.GuideChannelCandidate
import java.io.File

/** Phase 4C: the Guide's row playlist ids, cold and warm loads, and the guide key cache. */
class Phase4cLoadingTest {

    private fun key(category: String? = null, windowStart: Long = 1_000L, sources: List<Long> = listOf(2L, 7L)) = GuideLoad.Key(
        profileId = 1, sourceIds = sources, query = "", category = category,
        sortLive = "PLAYLIST", sortGuide = "LIVE_TV", favoriteIds = emptySet(), customizations = "c", windowStart = windowStart,
    )
    private val signal = GuideLoad.Signal(epgCounts = 650_000, maxProgrammeId = 2_125_801, channelCount = 40_000)

    // ---- Playlist ids for the rows -------------------------------------------------------------------------

    @Test fun `before a load the rows have no ids and their reads are not cached`() {
        val rows = GuideRowSources()
        assertFalse(rows.canQuery())
        assertEquals(emptyList<Long>(), rows.ids)
    }

    @Test fun `prepare gives the rows their ids and says when the sources changed`() {
        val rows = GuideRowSources()
        assertTrue("first load", rows.prepare(listOf(2L, 7L)))
        assertTrue(rows.canQuery())
        assertEquals(listOf(2L, 7L), rows.ids)
        assertFalse("same sources", rows.prepare(listOf(2L, 7L)))
        assertTrue("other sources", rows.prepare(listOf(2L)))
    }

    @Test fun `the load sets the row ids before it shows any channel`() {
        val vm = File("src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt").readText().replace("\r\n", "\n")
        val load = vm.substringAfter("    fun load() {")
        val prepared = load.indexOf("rowSources.prepare(ids)")
        assertTrue(prepared > 0)
        assertTrue("before the first publish", prepared < load.indexOf("first channel data published"))
        assertTrue("before the reuse checks", prepared < load.indexOf("GuideLoad.reuse("))
        // A row read made without ids is never cached.
        val rowRead = vm.substringAfter("suspend fun programmesFor(").substringBefore("\n    }\n")
        assertTrue(rowRead.indexOf("if (!rowSources.canQuery()) return emptyList()") in 1 until rowRead.indexOf("programmeSummariesForChannel"))
        assertFalse("loadedSourceIds" in vm)
    }

    // ---- Cold and warm loads -----------------------------------------------------------------------------

    @Test fun `a warm open with nothing changed reuses everything`() {
        assertTrue(GuideLoad.reuse(key(), key(), signal, signal, hasList = true, importRunning = false))
        assertFalse(GuideLoad.reuseList(key(), key(), signal, signal, hasList = true, importRunning = false))
    }

    @Test fun `a new half hour keeps the channel list and reloads only the programmes`() {
        val moved = key(windowStart = 2_800_000L)
        assertFalse(GuideLoad.reuse(key(), moved, signal, signal, hasList = true, importRunning = false))
        assertTrue(GuideLoad.reuseList(key(), moved, signal, signal, hasList = true, importRunning = false))
    }

    @Test fun `changed data, inputs or an import rebuild the channel list`() {
        val moved = key(windowStart = 2_800_000L)
        assertFalse("new programmes", GuideLoad.reuseList(key(), moved, signal, signal.copy(maxProgrammeId = 2_200_000), true, false))
        assertFalse("feed counts", GuideLoad.reuseList(key(), moved, signal, signal.copy(epgCounts = 1), true, false))
        assertFalse("playlist sync", GuideLoad.reuseList(key(), moved, signal, signal.copy(channelCount = 40_100), true, false))
        assertFalse("category", GuideLoad.reuseList(key(), key(category = "news", windowStart = 2_800_000L), signal, signal, true, false))
        assertFalse("import", GuideLoad.reuseList(key(), moved, signal, signal, true, importRunning = true))
        assertFalse("cold", GuideLoad.reuseList(null, moved, null, signal, false, false))
        assertFalse("nothing on screen", GuideLoad.reuseList(key(), moved, signal, signal, hasList = false, importRunning = false))
    }

    // ---- Guide keys kept between loads ------------------------------------------------------------------

    @Test fun `guide keys are read once and kept while the guide is unchanged`() {
        val cache = GuideKeyCache()
        assertNull("cold", cache.get(listOf(2L), signal, importRunning = false))
        cache.put(listOf(2L), signal, importRunning = false, setOf("fox.us"))
        assertEquals(setOf("fox.us"), cache.get(listOf(2L), signal, importRunning = false))
        assertNull("new programmes", cache.get(listOf(2L), signal.copy(maxProgrammeId = 1), importRunning = false))
        assertNull("other sources", cache.get(listOf(2L, 7L), signal, importRunning = false))
        assertNull("during an import", cache.get(listOf(2L), signal, importRunning = true))
        cache.put(listOf(2L), signal, importRunning = true, setOf("x"))
        assertNull("an import clears it", cache.get(listOf(2L), signal, importRunning = false))
    }

    // ---- Channel selection -------------------------------------------------------------------------------

    @Test fun `selection keeps the query order, skips unmatched and stops at the limit`() {
        val c = listOf(
            GuideChannelCandidate(5, "fox.us"), GuideChannelCandidate(3, null), GuideChannelCandidate(9, "nbc.us"),
            GuideChannelCandidate(1, "cnn.us"), GuideChannelCandidate(4, "fox.us"),
        )
        assertEquals(listOf(5L, 1L, 4L), GuideChannelQuery.select(c, setOf("fox.us", "cnn.us"), limit = 10))
        assertEquals(listOf(5L, 1L), GuideChannelQuery.select(c, setOf("fox.us", "cnn.us"), limit = 2))
        assertEquals(emptyList<Long>(), GuideChannelQuery.select(c, emptySet(), limit = 10))
    }

    @Test fun `rows loaded in any order come back in the guide order, missing ones dropped`() {
        assertEquals(listOf(5L, 1L), GuideChannelQuery.inOrder(listOf(5L, 7L, 1L), listOf(1L, 5L)) { it })
    }

    // ---- Timing ------------------------------------------------------------------------------------------

    @Test fun `the first programme cells are marked once per open`() {
        var t = 0L
        val timing = GuideTiming { t }
        timing.open()
        t = 900; timing.firstProgrammes()
        t = 1_500; timing.firstProgrammes()
        assertEquals(listOf("guide open requested", "first programme cells ready"), timing.steps.toList())
        timing.open()
        timing.firstProgrammes()
        assertEquals(2, timing.steps.size)
    }
}
