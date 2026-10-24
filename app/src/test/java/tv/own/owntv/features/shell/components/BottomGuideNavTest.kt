package tv.own.owntv.features.shell.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.sync.work.ReminderScheduler

/** Live TV bottom guide: opening position, Left/Right, programme boundaries and the Back key pair. */
class BottomGuideNavTest {

    private val hour = 3_600_000L
    private val min = 60_000L
    private val day0 = 1_800_000_000_000L - (1_800_000_000_000L % hour) // on the hour

    private var nextId = 1L
    private fun prog(startMs: Long, stopMs: Long, title: String = "P$nextId") =
        EpgProgrammeEntity(id = nextId++, sourceId = 1, epgChannelId = "ch", startMs = startMs, stopMs = stopMs, title = title)

    /** A day of past schedule then five ahead, hourly — the window the strip loads. */
    private fun schedule(): List<EpgProgrammeEntity> =
        (-24 until 5 * 24).map { h -> prog(day0 + h * hour, day0 + (h + 1) * hour) }

    // ---- Opening at Now ----------------------------------------------------------------------------

    @Test fun `opens on the programme airing now, not the first entry a day ago`() {
        val list = schedule()
        val now = day0 + 20 * min
        val index = BottomGuideNav.openingIndex(list, now)
        assertEquals(24, index)
        assertTrue(now >= list[index].startMs && now < list[index].stopMs)
    }

    @Test fun `opening repeatedly lands on the same programme`() {
        val list = schedule()
        val now = day0 + 45 * min
        val first = BottomGuideNav.openingIndex(list, now)
        repeat(5) { assertEquals(first, BottomGuideNav.openingIndex(list, now)) }
        assertEquals(first, BottomGuideNav.openingIndex(BottomGuideNav.normalize(list.shuffled()), now))
    }

    @Test fun `a gap in the guide opens on the next programme`() {
        val list = listOf(prog(day0 - 2 * hour, day0 - hour), prog(day0 + hour, day0 + 2 * hour))
        assertEquals(1, BottomGuideNav.openingIndex(list, day0))
    }

    @Test fun `a guide that has run out opens on its latest programme`() {
        val list = listOf(prog(day0 - 3 * hour, day0 - 2 * hour), prog(day0 - 2 * hour, day0 - hour))
        assertEquals(1, BottomGuideNav.openingIndex(list, day0))
    }

    @Test fun `no guide has no selection`() {
        assertEquals(-1, BottomGuideNav.openingIndex(emptyList(), day0))
        assertEquals(-1, BottomGuideNav.step(0, 1, 0))
    }

    @Test fun `normalize orders by start and drops a second feed's copy of a slot`() {
        val a = prog(day0, day0 + hour, "A")
        val b = prog(day0 + hour, day0 + 2 * hour, "B")
        val copyOfA = prog(day0, day0 + hour, "A (feed 2)")
        assertEquals(listOf(a, b), BottomGuideNav.normalize(listOf(b, a, copyOfA)))
    }

    // ---- Left / Right ------------------------------------------------------------------------------

    @Test fun `left and right move one programme and stop at the ends`() {
        assertEquals(5, BottomGuideNav.step(4, 1, 10))
        assertEquals(3, BottomGuideNav.step(4, -1, 10))
        assertEquals(0, BottomGuideNav.step(0, -1, 10))
        assertEquals(9, BottomGuideNav.step(9, 1, 10))
    }

    // ---- Programme boundaries ----------------------------------------------------------------------

    @Test fun `a selection on the live programme follows it across the boundary`() {
        val list = schedule()
        val before = day0 + 59 * min
        val after = day0 + hour + 1_000
        val live = BottomGuideNav.openingIndex(list, before)
        assertEquals(live + 1, BottomGuideNav.followLive(list, live, before, after))
    }

    @Test fun `a selection the viewer moved elsewhere stays put at the boundary`() {
        val list = schedule()
        val before = day0 + 59 * min
        val after = day0 + hour + 1_000
        val browsed = BottomGuideNav.openingIndex(list, before) + 3
        assertEquals(browsed, BottomGuideNav.followLive(list, browsed, before, after))
    }

    @Test fun `ticks within a programme leave the selection alone`() {
        val list = schedule()
        val live = BottomGuideNav.openingIndex(list, day0 + 10 * min)
        assertEquals(live, BottomGuideNav.followLive(list, live, day0 + 10 * min, day0 + 30 * min))
    }

    @Test fun `the clock wakes just after the next boundary`() {
        val list = schedule()
        assertEquals(10_500L, BottomGuideNav.nextTickDelay(list, day0 + hour - 10_000))
    }

    @Test fun `the clock never sleeps longer than the progress bar cadence`() {
        assertEquals(BottomGuideNav.MAX_TICK_MS, BottomGuideNav.nextTickDelay(schedule(), day0 + 5 * min))
        assertEquals(BottomGuideNav.MAX_TICK_MS, BottomGuideNav.nextTickDelay(emptyList(), day0))
    }

    // ---- Back navigation / reminder dialog ---------------------------------------------------------

    @Test fun `back closes the reminder dialog first, then the guide`() {
        val keys = GuideBackKeys()
        var dialogOpen = true
        var guideOpen = true
        fun press() {
            keys.onPress()
            when (keys.onRelease(reminderDialogOpen = dialogOpen)) {
                GuideBackKeys.Step.CLOSE_REMINDER_DIALOG -> dialogOpen = false
                GuideBackKeys.Step.CLOSE_GUIDE -> guideOpen = false
                GuideBackKeys.Step.NONE -> Unit
            }
        }
        press()
        assertEquals(false, dialogOpen)
        assertEquals("one press must not also close the guide", true, guideOpen)
        press()
        assertEquals(false, guideOpen)
    }

    @Test fun `the press does nothing until its release`() {
        val keys = GuideBackKeys()
        keys.onPress()
        assertEquals(GuideBackKeys.Step.CLOSE_REMINDER_DIALOG, keys.onRelease(reminderDialogOpen = true))
        assertEquals("the same release is not counted twice", GuideBackKeys.Step.NONE, keys.onRelease(reminderDialogOpen = false))
    }

    @Test fun `a release whose press went elsewhere is ignored`() {
        assertEquals(GuideBackKeys.Step.NONE, GuideBackKeys().onRelease(reminderDialogOpen = false))
    }

    // ---- Reminders set from the strip --------------------------------------------------------------

    @Test fun `set reminder saves the 4_5_0 row with a 60-second warning`() {
        val ch = ChannelEntity(id = 42, sourceId = 7, name = "News", streamUrl = "s", number = 101, remoteId = "9001")
        val p = prog(day0 + 2 * hour, day0 + 3 * hour, "Evening News")
        val r = BottomGuideNav.reminderFor(ch, p, createdAtMs = day0)
        assertEquals(7L, r.sourceId)
        assertEquals(42L, r.channelId)
        assertEquals("News", r.channelName)
        assertEquals(101, r.channelNumber)
        assertEquals("Evening News", r.programTitle)
        assertEquals(p.startMs, r.programStartMs)
        assertEquals(p.stopMs, r.programEndMs)
        assertEquals(p.startMs - 60_000L, r.triggerAtMs)
        assertEquals(day0, r.createdAtMs)
    }

    @Test fun `reminder work names are unchanged, so 4_5_0 jobs are still found`() {
        assertEquals("epg_warning_42_1700", ReminderScheduler.warningWorkName(42, 1700))
        assertEquals("epg_autotune_42_1700", ReminderScheduler.autotuneWorkName(42, 1700))
    }
}
