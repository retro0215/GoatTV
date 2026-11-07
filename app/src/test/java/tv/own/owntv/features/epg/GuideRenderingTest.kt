package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.sync.work.ProgramReminders
import tv.own.owntv.features.epg.GuideNav.Cursor

/** Phase 3D: prepared row data, bounded caches, focus restoration, the midnight date and paging. */
class GuideRenderingTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val t0 = 1_800_000_000_000L - (1_800_000_000_000L % day) // midnight
    private val now = t0 + 14 * hour

    private var nextId = 1L
    private fun prog(start: Long, stop: Long) =
        EpgProgrammeEntity(id = nextId++, sourceId = 1, epgChannelId = "ch", startMs = start, stopMs = stop, title = "P$nextId")

    private fun channel(id: Long, catchup: Boolean = false, days: Int = 0) =
        ChannelEntity(id = id, sourceId = 1, name = "C$id", streamUrl = "s", catchup = catchup, catchupDays = days)

    // ---- Row data preparation -------------------------------------------------------------------------

    @Test fun `a catch-up row marks the programmes inside its archive`() {
        val aired = prog(now - 3 * hour, now - 2 * hour)
        val onAir = prog(now - 10 * min, now + hour)
        val upcoming = prog(now + hour, now + 2 * hour)
        val tooOld = prog(now - 3 * day, now - 3 * day + hour)
        val data = GuideNav.prepareRow(channel(7, catchup = true, days = 2), listOf(tooOld, aired, onAir, upcoming), now, revision = 4)
        assertEquals(7L, data.channelId)
        assertEquals(setOf(aired.id, onAir.id), data.catchupIds)
        assertEquals(4, data.revision)
        assertEquals(4, data.programmes.size)
    }

    @Test fun `a channel without an archive has no catch-up badges`() {
        val data = GuideNav.prepareRow(channel(8), listOf(prog(now - hour, now)), now, revision = 0)
        assertTrue(data.catchupIds.isEmpty())
    }

    @Test fun `reminder bells and catch-up badges are independent`() {
        val ch = channel(9, catchup = true, days = 1)
        val aired = prog(now - 2 * hour, now - hour)
        val upcoming = prog(now + hour, now + 2 * hour)
        val data = GuideNav.prepareRow(ch, listOf(aired, upcoming), now, revision = 0)
        val reminders = setOf(ProgramReminders.Key(ch.id, upcoming.startMs))
        assertTrue(aired.id in data.catchupIds && ProgramReminders.Key(ch.id, aired.startMs) !in reminders)
        assertTrue(upcoming.id !in data.catchupIds && ProgramReminders.Key(ch.id, upcoming.startMs) in reminders)
    }

    // ---- Stable row identity and bounded stores -------------------------------------------------------

    @Test fun `row data is keyed by channel id, so a re-sorted list keeps each row's data`() {
        val store = GuideRowStore()
        val a = GuideNav.prepareRow(channel(1), emptyList(), now, 0)
        val b = GuideNav.prepareRow(channel(2), emptyList(), now, 0)
        store.put(a); store.put(b)
        // The list re-sorts (2 before 1): lookups are by id, not position.
        assertSame(b, store[2L])
        assertSame(a, store[1L])
    }

    @Test fun `stale data is refreshed in place when the cache revision moves on`() {
        val store = GuideRowStore()
        store.put(GuideNav.prepareRow(channel(1), emptyList(), now, revision = 1))
        assertTrue(store.isCurrent(1L, 1))
        assertFalse(store.isCurrent(1L, 2))
        store.put(GuideNav.prepareRow(channel(1), listOf(prog(now, now + hour)), now, revision = 2))
        assertTrue(store.isCurrent(1L, 2))
        assertEquals(1, store.size)
    }

    @Test fun `browsing thousands of channels keeps the row store bounded`() {
        val store = GuideRowStore(maxRows = 50)
        for (id in 1L..5_000L) store.put(GuideNav.prepareRow(channel(id), emptyList(), now, 0))
        assertEquals(50, store.size)
        assertNull(store[1L])
        assertTrue(store[5_000L] != null)
    }

    @Test fun `the label cache keeps only the most recent entries`() {
        val lru = BoundedLru<Long, String>(3)
        for (i in 1L..10L) lru[i] = "L$i"
        assertEquals(3, lru.size)
        assertNull(lru[1L])
        assertEquals("L10", lru[10L])
        assertEquals("L9", lru.getOrPut(9L) { "new" })
    }

    @Test fun `rows are prepared on screen first, then outward`() {
        assertEquals(listOf(10, 11, 12, 13, 9, 14, 8), GuideNav.prepOrder(firstVisible = 10, visible = 3, margin = 2, rowCount = 100))
        assertEquals(listOf(0, 1, 2), GuideNav.prepOrder(firstVisible = 0, visible = 2, margin = 1, rowCount = 3))
        assertTrue(GuideNav.prepOrder(0, 4, 6, 0).isEmpty())
    }

    // ---- Focus restoration ------------------------------------------------------------------------------

    @Test fun `a menu's pending focus is gone once its request has run`() {
        val pending = GuideNav.PendingFocus<String>()
        pending.arm("category")
        // Category closed and focus went straight to its button; the entry rule never consumed it.
        pending.disarm("category")
        // Later the channel-options dialog closes and asks for the grid.
        pending.arm("grid")
        assertEquals("grid", pending.take())
        assertNull(pending.take())
    }

    @Test fun `a newer request replaces an older one, and disarming the older leaves it`() {
        val pending = GuideNav.PendingFocus<String>()
        pending.arm("more")
        pending.arm("grid")
        pending.disarm("more")
        assertEquals("grid", pending.armed)
    }

    // ---- Midnight -------------------------------------------------------------------------------------

    @Test fun `a programme from 10 PM to 1-30 AM keeps its own day after midnight`() {
        val cw = channel(8690)
        val other = channel(8687)
        val game = prog(t0 + 22 * hour, t0 + day + hour + 30 * min)
        val lateShow = prog(t0 + day, t0 + day + 30 * min)
        val programmes = mapOf(cw.id to listOf(game), other.id to listOf(lateShow))
        val rows = listOf(other, channel(8688), channel(8689), cw)
        val cursor = Cursor(row = 3, timeMs = t0 + day) // 12:00 AM, on CW East's game
        assertEquals(game.startMs, GuideNav.selectionDay(rows, cursor) { programmes[it.id].orEmpty() })
    }

    @Test fun `the selection reads the current list, not an empty one from before loading`() {
        val cursor = Cursor(row = 3, timeMs = t0 + day)
        // Phase 3C's header captured the list from before the guide loaded: no row, so it showed the cursor's day.
        assertEquals(cursor.timeMs, GuideNav.selectionDay(emptyList(), cursor) { emptyList() })
    }

    // ---- 4.6.0: header date and time ------------------------------------------------------------------

    @Test fun `browsing, the header's date and time are both the highlighted programme's start`() {
        val cw = channel(8690)
        val game = prog(t0 + 22 * hour, t0 + day + hour + 30 * min)
        val cursor = Cursor(row = 0, timeMs = t0 + day + 15 * min) // after midnight, still on the 10 PM game
        val wallClock = t0 + 2 * day + 9 * hour
        // One instant for both texts: never the wall clock beside the programme's date.
        assertEquals(game.startMs, GuideNav.headerInstant(listOf(cw), cursor, wallClock) { listOf(game) })
    }

    @Test fun `not browsing, the header shows the current date and clock`() {
        val now = t0 + 9 * hour + 41 * min
        assertEquals(now, GuideNav.headerInstant(listOf(channel(1)), null, now) { emptyList() })
    }

    @Test fun `browsing a row without guide data, the header shows the cursor's own time`() {
        val cursor = Cursor(row = 0, timeMs = t0 + 20 * hour)
        assertEquals(cursor.timeMs, GuideNav.headerInstant(listOf(channel(1)), cursor, t0) { emptyList() })
    }

    @Test fun `the header formats one instant, with the system 12-24 hour time and a locale date`() {
        val screen = java.io.File("src/main/java/tv/own/owntv/features/epg/EpgScreen.kt").readText().replace("\r\n", "\n")
        val header = screen.substringAfter("// ---- Header:").substringBefore("Spacer(Modifier.weight(1f))")
        assertTrue("formatHeaderDate(headerInstant)" in header)
        assertTrue("formatTime(headerInstant)" in header)
        assertFalse("the clock beside a programme's date", "formatTime(now)" in header)
        assertTrue("val formatTime = rememberSystemTimeFormatter()" in header)
        assertTrue("rememberBestDateFormatter(\"EEEdMMM\")" in header)
    }

    // ---- Paging and rapid movement --------------------------------------------------------------------

    @Test fun `channel down flips a full page and keeps the row's place on screen`() {
        val move = GuideNav.pageMove(row = 12, pages = 1, visibleRows = 4, firstVisible = 10, rowCount = 11_250)!!
        assertEquals(16, move.row)
        assertEquals(14, move.firstVisible) // the row stays third from the top
    }

    @Test fun `channel up flips back`() {
        val move = GuideNav.pageMove(row = 16, pages = -1, visibleRows = 4, firstVisible = 14, rowCount = 11_250)!!
        assertEquals(12, move.row)
        assertEquals(10, move.firstVisible)
    }

    @Test fun `paging stops at the ends with the row still on screen`() {
        val top = GuideNav.pageMove(row = 2, pages = -1, visibleRows = 4, firstVisible = 1, rowCount = 100)!!
        assertEquals(0, top.row)
        assertEquals(0, top.firstVisible)
        val bottom = GuideNav.pageMove(row = 97, pages = 1, visibleRows = 4, firstVisible = 95, rowCount = 100)!!
        assertEquals(99, bottom.row)
        assertTrue(bottom.row in bottom.firstVisible until bottom.firstVisible + 4)
        assertNull(GuideNav.pageMove(row = 0, pages = -1, visibleRows = 4, firstVisible = 0, rowCount = 100))
    }

    @Test fun `a burst of channel downs never leaves the focused row off screen`() {
        var row = 0
        var first = 0
        repeat(3_000) {
            GuideNav.pageMove(row, 1, 4, first, 11_250)?.let { row = it.row; first = it.firstVisible }
            assertTrue(row in first until first + 4)
        }
        assertEquals(11_249, row)
    }
}
