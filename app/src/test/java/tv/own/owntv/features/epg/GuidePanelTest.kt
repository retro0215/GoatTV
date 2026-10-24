package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.features.epg.GuideNav.Cursor

/** Phase 3F: the details panel's content and update timing, header isolation, and prepared cell texts. */
class GuidePanelTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val t0 = 1_800_000_000_000L - (1_800_000_000_000L % day)
    private val now = t0 + 14 * hour + 10 * min

    private var nextId = 1L
    private fun prog(start: Long, stop: Long, title: String = "P$nextId") =
        EpgProgrammeEntity(id = nextId++, sourceId = 1, epgChannelId = "ch", startMs = start, stopMs = stop, title = title)

    private val strings = GuideNav.PanelStrings(
        nowLabel = "NOW", catchup = "catch-up", reminderSet = "Reminder set", separator = " · ",
        noProgramme = "No program", hint = "hint",
        range = { a, b -> "${(a - t0) / hour}h-${(b - t0) / hour}h" },
        runtime = { m -> "${m}m" },
        nextLabel = { t -> "NEXT ${(t - t0) / hour}h" },
    )

    private fun panel(programmes: List<EpgProgrammeEntity>, cursorTime: Long, reminders: Set<Long> = emptySet(), catchup: Boolean = false) =
        GuideNav.panelText(programmes, cursorTime, now, reminders, { catchup }, synopsis = null, hint = "hint", strings = strings)

    // ---- Details panel content ----------------------------------------------------------------------

    @Test fun `the panel describes the highlighted programme and the one after it`() {
        val a = prog(t0 + 14 * hour, t0 + 15 * hour, "News")
        val b = prog(t0 + 15 * hour, t0 + 16 * hour, "Film")
        val text = panel(listOf(a, b), now)
        assertEquals(a.id, text.programmeId)
        assertEquals("News", text.title)
        assertEquals("NOW · 14h-15h · 60m", text.bits)
        assertEquals("NEXT 15h", text.nextLabel)
        assertEquals("Film", text.nextTitle)
        assertTrue(text.onAir)
    }

    @Test fun `reminder and catch-up flags appear in the details line`() {
        val a = prog(t0 + 16 * hour, t0 + 17 * hour)
        val text = panel(listOf(a), a.startMs, reminders = setOf(a.startMs), catchup = true)
        assertEquals("16h-17h · 60m · catch-up · Reminder set", text.bits)
    }

    @Test fun `no programme under the cursor shows the empty text`() {
        val text = panel(emptyList(), now)
        assertNull(text.programmeId)
        assertEquals("No program", text.title)
    }

    @Test fun `after a burst of moves the panel shows the programme where the cursor stopped`() {
        val row = (0 until 24).map { h -> prog(t0 + h * hour, t0 + (h + 1) * hour, "H$h") }
        var t = now
        repeat(7) { t = GuideNav.stepTime(row, t, 1, t0, t0 + day)!! }
        // Only the final position matters: the collector is cancelled for every intermediate move.
        assertEquals("H21", panel(row, t).title)
    }

    @Test fun `the panel waits for a pause while the remote is held`() {
        assertEquals(200L, GuideNav.panelSettleMs(sinceLastMoveMs = 120))
        assertEquals(40L, GuideNav.panelSettleMs(sinceLastMoveMs = 700))
        assertTrue(GuideNav.panelSettleMs(GuideNav.RAPID_MOVE_MS - 1) > GuideNav.panelSettleMs(GuideNav.RAPID_MOVE_MS))
    }

    @Test fun `a single move updates the panel within a few frames`() {
        assertTrue(GuideNav.panelSettleMs(10_000) <= 50L)
    }

    // ---- Header isolation and midnight ---------------------------------------------------------------

    @Test fun `moving inside one programme never changes the header day`() {
        val late = prog(t0 + 22 * hour, t0 + day + 90 * min)
        val rows = listOf(ChannelEntity(id = 1, sourceId = 1, name = "C", streamUrl = "s"))
        val days = (0..20).map { k -> GuideNav.selectionDay(rows, Cursor(0, t0 + 22 * hour + k * 10 * min)) { listOf(late) } }.toSet()
        assertEquals("one day for the whole programme, before and after midnight", setOf(late.startMs), days)
    }

    @Test fun `stepping onto the next day's programme moves the header day`() {
        val a = prog(t0 + 22 * hour, t0 + day)
        val b = prog(t0 + day, t0 + day + hour)
        val rows = listOf(ChannelEntity(id = 1, sourceId = 1, name = "C", streamUrl = "s"))
        val before = GuideNav.selectionDay(rows, Cursor(0, t0 + 23 * hour)) { listOf(a, b) }
        val after = GuideNav.selectionDay(rows, Cursor(0, t0 + day + 10 * min)) { listOf(a, b) }
        assertNotEquals(before / day, after / day)
    }

    // ---- Prepared cell texts (timeline cache) ---------------------------------------------------------

    @Test fun `prepared texts are redone for a new data revision or a new timeline hour`() {
        val k = GuideNav.textKey(revision = 3, bucket = 10)
        assertEquals(k, GuideNav.textKey(3, 10))
        assertNotEquals(k, GuideNav.textKey(4, 10))
        assertNotEquals(k, GuideNav.textKey(3, 11))
        // Scrolling within the hour keeps the same bucket (no re-preparation).
        assertEquals(GuideNav.timelineBucket(600), GuideNav.timelineBucket(659))
        assertNotEquals(GuideNav.timelineBucket(659), GuideNav.timelineBucket(660))
    }

    @Test fun `the prepared span covers the view with margin either side`() {
        val (from, to) = GuideNav.textSpan(t0, bucket = 10, viewportMinutes = 240)
        assertEquals(t0 + 9 * hour, from)
        assertEquals(t0 + 10 * hour + 240 * min + 2 * hour, to)
    }

    @Test fun `a fully visible cell gets the same text width wherever it sits, so prepared texts are reused`() {
        val widths = listOf(0f, 37f, 512f, 900f).map { x ->
            GuideCellLayout.layout(cellX = x, cellW = 220f, viewW = 1400f, pad = 10f, gap = 6f, bellW = 0f, badgeW = 16f, minText = 28f).textWidth
        }.toSet()
        assertEquals(1, widths.size)
        val prepared = GuideCellLayout.layout(0f, 220f, Float.MAX_VALUE, 10f, 6f, 0f, 16f, 28f).textWidth
        assertEquals(widths.single(), prepared)
    }

    @Test fun `a clipped cell or one with a bell has another width and is measured while drawing`() {
        val full = GuideCellLayout.layout(0f, 220f, Float.MAX_VALUE, 10f, 6f, 0f, 0f, 28f).textWidth
        val clipped = GuideCellLayout.layout(-100f, 220f, 1400f, 10f, 6f, 0f, 0f, 28f).textWidth
        val withBell = GuideCellLayout.layout(0f, 220f, 1400f, 10f, 6f, 24f, 0f, 28f).textWidth
        assertNotEquals(full, clipped)
        assertNotEquals(full, withBell)
    }
}
