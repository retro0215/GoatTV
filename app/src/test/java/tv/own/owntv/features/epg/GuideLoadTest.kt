package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4B: cached-first and progressive Guide opening (GuideLoad, GuideTiming, visibleChannels). */
class GuideLoadTest {

    private fun key(category: String? = null, windowStart: Long = 1_000L, query: String = "") = GuideLoad.Key(
        profileId = 1, sourceIds = listOf(2L, 7L), query = query, category = category,
        sortLive = "PLAYLIST", sortGuide = "LIVE_TV", favoriteIds = setOf(5L), customizations = "c", windowStart = windowStart,
    )

    // ---- Warm opening: reuse ---------------------------------------------------------------------------

    @Test fun `a warm open with nothing changed reuses the list without a query`() {
        assertTrue(GuideLoad.reuse(key(), key(), lastSignal = 650_000, signal = 650_000, hasList = true, importRunning = false))
    }

    @Test fun `new guide data, other inputs or a new half hour load again`() {
        assertFalse("data changed", GuideLoad.reuse(key(), key(), 650_000, 650_100, hasList = true, importRunning = false))
        assertFalse("category", GuideLoad.reuse(key(), key(category = "news"), 1, 1, hasList = true, importRunning = false))
        assertFalse("search", GuideLoad.reuse(key(), key(query = "fox"), 1, 1, hasList = true, importRunning = false))
        assertFalse("window moved", GuideLoad.reuse(key(), key(windowStart = 2_000L), 1, 1, hasList = true, importRunning = false))
    }

    // ---- Cold opening ------------------------------------------------------------------------------------

    @Test fun `a cold open always loads`() {
        assertFalse("no previous load", GuideLoad.reuse(null, key(), null, 1, hasList = false, importRunning = false))
        assertFalse("nothing on screen", GuideLoad.reuse(key(), key(), 1, 1, hasList = false, importRunning = false))
        assertFalse("signal unknown yet", GuideLoad.reuse(key(), key(), null, null, hasList = true, importRunning = false))
    }

    @Test fun `a running guide import never reuses`() {
        assertFalse(GuideLoad.reuse(key(), key(), 1, 1, hasList = true, importRunning = true))
    }

    // ---- Progressive: rows are shown before the window preload ------------------------------------------

    @Test fun `while the window preload runs, rows with unknown guide stay visible`() {
        // hasGuideInWindow answers null (unknown) until the preload completes: nothing is hidden early.
        val channels = (1L..5L).map { ch(it) }
        assertEquals(channels, GuideNav.visibleChannels(channels, showAll = false) { null })
    }

    @Test fun `after the preload, empty rows are hidden as before`() {
        val channels = (1L..5L).map { ch(it) }
        val visible = GuideNav.visibleChannels(channels, showAll = false) { it.id % 2L == 0L }
        assertEquals(listOf(2L, 4L), visible.map { it.id })
    }

    // ---- Timing instrumentation ----------------------------------------------------------------------------

    @Test fun `timing marks each step once per open and the first usable frame once`() {
        var t = 0L
        val timing = GuideTiming { t }
        timing.open()
        t = 300; timing.mark("channel query end")
        t = 320; timing.firstUsableFrame()
        t = 900; timing.firstUsableFrame()
        assertEquals(listOf("guide open requested", "channel query end", "first usable guide frame"), timing.steps.toList())
        timing.open()
        timing.firstUsableFrame()
        assertEquals(listOf("guide open requested", "first usable guide frame"), timing.steps.toList())
    }

    private fun ch(id: Long) = tv.own.owntv.core.database.entity.ChannelEntity(
        id = id, sourceId = 2, name = "C$id", streamUrl = "http://x/$id", epgChannelId = "c$id",
    )
}
