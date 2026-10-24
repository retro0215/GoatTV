package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.sync.work.ProgramReminders
import tv.own.owntv.features.epg.GuideNav.Cursor
import tv.own.owntv.features.epg.GuideNav.DetailFocus

/** The full TV Guide's cursor, scrolling, OK behaviour, row filters and catch-up rule. */
class GuideNavTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val windowStart = 1_800_000_000_000L - (1_800_000_000_000L % (30 * min)) // half-hour aligned
    private val windowEnd = windowStart + 24 * hour
    private val now = windowStart + 2 * hour + 10 * min

    private var nextId = 1L
    private fun prog(start: Long, stop: Long, title: String = "P$nextId") =
        EpgProgrammeEntity(id = nextId++, sourceId = 1, epgChannelId = "ch", startMs = start, stopMs = stop, title = title)

    /** Hourly programmes from an hour before the window to its end. */
    private val hourly = (-1 until 24).map { h -> prog(windowStart + h * hour, windowStart + (h + 1) * hour) }

    private fun channel(id: Long, categoryId: Long? = null, catchup: Boolean = false, days: Int = 0) =
        ChannelEntity(id = id, sourceId = 1, name = "C$id", streamUrl = "s", categoryId = categoryId, catchup = catchup, catchupDays = days)

    // ---- Timeline positioning ------------------------------------------------------------------------

    @Test fun `opens at the current time with the now slot at the left edge`() {
        assertEquals(120, GuideNav.openingScrollMinutes(now, windowStart)) // 2h10 → the 2h00 slot
        assertEquals(0, GuideNav.openingScrollMinutes(windowStart - hour, windowStart))
    }

    @Test fun `the opening position is not undone by the keep-in-view rule`() {
        val scroll = GuideNav.openingScrollMinutes(now, windowStart)
        assertNull(GuideNav.timelineScrollTarget(now, windowStart, scroll, viewportMinutes = 240))
    }

    @Test fun `the timeline scrolls only when the cursor leaves the view`() {
        // Cursor beyond the right edge (minus one slot) → bring it to one slot from the left.
        assertEquals(330, GuideNav.timelineScrollTarget(windowStart + 6 * hour, windowStart, 120, 240))
        // Cursor left of the view → same placement.
        assertEquals(30, GuideNav.timelineScrollTarget(windowStart + hour, windowStart, 120, 240))
        // Inside → no scroll.
        assertNull(GuideNav.timelineScrollTarget(windowStart + 3 * hour, windowStart, 120, 240))
    }

    // ---- Current channel focus -----------------------------------------------------------------------

    @Test fun `opens on the channel being watched`() {
        assertEquals(Cursor(2, now), GuideNav.openingCursor(listOf(10L, 11L, 12L), 12L, now))
    }

    @Test fun `a watched channel not in this view opens on the first row`() {
        assertEquals(Cursor(0, now), GuideNav.openingCursor(listOf(10L, 11L), 99L, now))
        assertEquals(Cursor(0, now), GuideNav.openingCursor(listOf(10L, 11L), null, now))
    }

    @Test fun `an empty guide has no cursor`() {
        assertNull(GuideNav.openingCursor(emptyList(), 1L, now))
    }

    // ---- Program selection ---------------------------------------------------------------------------

    @Test fun `the cursor highlights the programme airing now`() {
        val p = GuideNav.programmeAt(hourly, now)!!
        assertTrue(now >= p.startMs && now < p.stopMs)
    }

    @Test fun `in a gap the latest earlier programme is highlighted`() {
        val a = prog(windowStart, windowStart + hour)
        val b = prog(windowStart + 3 * hour, windowStart + 4 * hour)
        assertEquals(a, GuideNav.programmeAt(listOf(a, b), windowStart + 2 * hour))
        assertNull(GuideNav.programmeAt(listOf(b), windowStart))
    }

    @Test fun `next after the highlighted programme`() {
        val current = GuideNav.programmeAt(hourly, now)
        assertEquals(current!!.stopMs, GuideNav.nextAfter(hourly, current)!!.startMs)
        assertNull(GuideNav.nextAfter(hourly, null))
    }

    // ---- Remote navigation ---------------------------------------------------------------------------

    @Test fun `right moves to the next programme and left back to the previous one`() {
        val right = GuideNav.stepTime(hourly, now, 1, windowStart, windowEnd)!!
        assertEquals(windowStart + 3 * hour, right)
        // From mid-programme, Left is the previous programme, not the start of the current one.
        assertEquals(windowStart + hour, GuideNav.stepTime(hourly, now, -1, windowStart, windowEnd))
        assertEquals(windowStart + 2 * hour, GuideNav.stepTime(hourly, right, -1, windowStart, windowEnd))
    }

    @Test fun `left stops at the window start and then reports the edge`() {
        // The programme before the window straddles it: entered at the window start, then the edge.
        val atStart = GuideNav.stepTime(hourly, windowStart + 30 * min, -1, windowStart, windowEnd)
        assertNull(atStart) // the 0h programme starts exactly at the window start: nothing further left
        val straddling = listOf(prog(windowStart - 30 * min, windowStart + 30 * min), prog(windowStart + 30 * min, windowStart + hour))
        assertEquals(windowStart, GuideNav.stepTime(straddling, windowStart + 40 * min, -1, windowStart, windowEnd))
        assertNull(GuideNav.stepTime(straddling, windowStart, -1, windowStart, windowEnd))
    }

    @Test fun `right stops at the last programme in the window`() {
        assertNull(GuideNav.stepTime(hourly, windowEnd - 10 * min, 1, windowStart, windowEnd))
    }

    @Test fun `a row without guide data moves by half-hour slots`() {
        assertEquals(now + 30 * min, GuideNav.stepTime(emptyList(), now, 1, windowStart, windowEnd))
        assertEquals(now - 30 * min, GuideNav.stepTime(emptyList(), now, -1, windowStart, windowEnd))
        assertNull(GuideNav.stepTime(emptyList(), windowStart + 10 * min, -1, windowStart, windowEnd))
    }

    @Test fun `up and down change channel and stop at the ends`() {
        assertEquals(4, GuideNav.stepRow(3, 1, 5))
        assertEquals(2, GuideNav.stepRow(3, -1, 5))
        assertNull("Up from the first row goes to the header", GuideNav.stepRow(0, -1, 5))
        assertNull(GuideNav.stepRow(4, 1, 5))
    }

    @Test fun `the selected row is scrolled into view by the least amount`() {
        assertNull(GuideNav.rowScrollTarget(row = 5, firstVisible = 3, visibleCount = 6))
        assertEquals(4, GuideNav.rowScrollTarget(row = 9, firstVisible = 3, visibleCount = 6)) // one row down
        assertEquals(2, GuideNav.rowScrollTarget(row = 2, firstVisible = 3, visibleCount = 6))
    }

    @Test fun `ok never tunes - it focuses the right action in the details`() {
        val onAir = GuideNav.programmeAt(hourly, now)!!
        val upcoming = GuideNav.nextAfter(hourly, onAir)!!
        val aired = hourly.first { it.stopMs <= now }
        assertEquals(DetailFocus.WATCH, GuideNav.detailFocus(onAir, now, canCatchup = false))
        assertEquals(DetailFocus.WATCH, GuideNav.detailFocus(onAir, now, canCatchup = true))
        assertEquals(DetailFocus.REMINDER, GuideNav.detailFocus(upcoming, now, canCatchup = false))
        assertEquals(DetailFocus.CATCHUP, GuideNav.detailFocus(aired, now, canCatchup = true))
        assertEquals(DetailFocus.WATCH, GuideNav.detailFocus(aired, now, canCatchup = false))
    }

    // ---- Reminder indicators -------------------------------------------------------------------------

    @Test fun `bells show only the row's own reminders`() {
        val slots = setOf(
            ProgramReminders.Key(10, windowStart + hour),
            ProgramReminders.Key(10, windowStart + 3 * hour),
            ProgramReminders.Key(11, windowStart + hour),
        )
        assertEquals(setOf(windowStart + hour, windowStart + 3 * hour), GuideNav.reminderStarts(slots, 10))
        assertEquals(emptySet<Long>(), GuideNav.reminderStarts(slots, 12))
    }

    // ---- Category filtering --------------------------------------------------------------------------

    @Test fun `a provider category keeps its channels except ones moved out of it`() {
        val list = listOf(channel(1, categoryId = 5), channel(2, categoryId = 6), channel(3, categoryId = 5))
        val filtered = GuideNav.filterByCategory(list, null, 5, "cat:5") { if (it.id == 3L) "cat:5" else null }
        assertEquals(listOf(1L), filtered.map { it.id })
    }

    @Test fun `a custom category keeps its members in guide order`() {
        val list = listOf(channel(1), channel(2), channel(3))
        assertEquals(listOf(1L, 3L), GuideNav.filterByCategory(list, setOf(3L, 1L), null, "custom:1") { null }.map { it.id })
    }

    @Test fun `no category keeps every channel`() {
        val list = listOf(channel(1), channel(2))
        assertEquals(list, GuideNav.filterByCategory(list, null, null, null) { null })
    }

    // ---- Empty guide behaviour -----------------------------------------------------------------------

    @Test fun `channels with an empty window are hidden unless show all`() {
        val list = listOf(channel(1), channel(2), channel(3))
        val known = mapOf(1L to true, 2L to false) // 3 not read yet
        val visible = GuideNav.visibleChannels(list, showAll = false) { known[it.id] }
        assertEquals("unknown rows are never hidden early", listOf(1L, 3L), visible.map { it.id })
        assertEquals(list, GuideNav.visibleChannels(list, showAll = true) { known[it.id] })
    }

    @Test fun `a view where nothing has guide data is empty, and show all brings the rows back`() {
        val list = listOf(channel(1), channel(2))
        assertTrue(GuideNav.visibleChannels(list, showAll = false) { false }.isEmpty())
        assertEquals(2, GuideNav.visibleChannels(list, showAll = true) { false }.size)
    }

    // ---- Catch-up compatibility ----------------------------------------------------------------------

    @Test fun `catch-up replays started programmes inside the archive window only`() {
        val ch = channel(1, catchup = true, days = 2)
        val aired = prog(now - 3 * hour, now - 2 * hour)
        val tooOld = prog(now - 3 * 24 * hour, now - 3 * 24 * hour + hour)
        val upcoming = prog(now + hour, now + 2 * hour)
        assertTrue(GuideNav.canCatchup(ch, aired, now))
        assertTrue("on air can be watched from the start", GuideNav.canCatchup(ch, prog(now - 10 * min, now + hour), now))
        assertFalse(GuideNav.canCatchup(ch, tooOld, now))
        assertFalse(GuideNav.canCatchup(ch, upcoming, now))
        assertFalse(GuideNav.canCatchup(channel(2, catchup = false), aired, now))
    }

    @Test fun `a catch-up channel with no day count keeps a one-day archive`() {
        val ch = channel(1, catchup = true, days = 0)
        assertTrue(GuideNav.canCatchup(ch, prog(now - 20 * hour, now - 19 * hour), now))
        assertFalse(GuideNav.canCatchup(ch, prog(now - 26 * hour, now - 25 * hour), now))
    }
}
