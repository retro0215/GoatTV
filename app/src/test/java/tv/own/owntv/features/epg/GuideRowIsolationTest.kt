package tv.own.owntv.features.epg

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Phase 3E: per-channel row state (a write to one channel never invalidates another row's drawing)
 * and overlapping guide entries drawn without stacking.
 */
class GuideRowIsolationTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val t0 = 1_800_000_000_000L - (1_800_000_000_000L % hour)
    private val now = t0 + 30 * min

    private var nextId = 1L
    private fun prog(start: Long, stop: Long, title: String = "P$nextId", id: Long = nextId++) =
        EpgProgrammeEntity(id = id, sourceId = 1, epgChannelId = "ch", startMs = start, stopMs = stop, title = title)

    private fun channel(id: Long, catchup: Boolean = false) =
        ChannelEntity(id = id, sourceId = 1, name = "C$id", streamUrl = "s", catchup = catchup, catchupDays = 2)

    private fun data(id: Long, revision: Int = 0) = GuideNav.prepareRow(channel(id), emptyList(), now, revision)

    /** The snapshot state objects a row reading [read] would be observing. */
    private fun readsOf(read: () -> Unit): Set<Any> {
        val reads = HashSet<Any>()
        val snap = Snapshot.takeSnapshot { reads += it }
        try { snap.enter(read) } finally { snap.dispose() }
        return reads
    }

    /** The state objects a write changes, as Compose's apply observers (and so draw invalidation) see them. */
    private fun changesOf(write: () -> Unit): Set<Any> {
        val changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        try { Snapshot.withMutableSnapshot(write) } finally { handle.dispose() }
        return changed
    }

    // ---- State isolation ----------------------------------------------------------------------------------

    @Test fun `updating channel A's row does not touch anything channel B's row reads`() {
        val store = GuideRowStore()
        store.put(data(1)); store.put(data(2))
        val readsOfB = readsOf { store.state(2L).value }
        val changes = changesOf { store.put(data(1, revision = 1)) }
        assertTrue("A's write is observable", changes.isNotEmpty())
        assertTrue("B's row must not be invalidated", readsOfB.none { it in changes })
    }

    @Test fun `channel A's row is invalidated by its own update`() {
        val store = GuideRowStore()
        store.put(data(1))
        val readsOfA = readsOf { store.state(1L).value }
        val changes = changesOf { store.put(data(1, revision = 1)) }
        assertTrue(readsOfA.any { it in changes })
    }

    @Test fun `a logo landing for one channel does not touch another channel's logo`() {
        val logos = KeyedStates<String, String>(maxLoaded = 10) // same holder type the logo cache uses
        val readsOfB = readsOf { logos.state("http://b/logo.png").value }
        val changes = changesOf { logos.put("http://a/logo.png", "bitmap-a") }
        assertTrue(readsOfB.none { it in changes })
    }

    @Test fun `a row keeps the same state holder across updates and evictions`() {
        val store = GuideRowStore(maxRows = 2)
        val holder = store.state(1L)
        store.put(data(1)); store.put(data(2)); store.put(data(3)) // 1 evicted
        assertSame(holder, store.state(1L))
        assertNull("evicted data is released", holder.value)
        store.put(data(1, revision = 5)) // re-prepared when it scrolls back
        assertEquals(5, holder.value?.revision)
        assertEquals(2, store.size)
    }

    @Test fun `stale data from an older cache revision is replaced, not kept`() {
        val store = GuideRowStore()
        store.put(data(1, revision = 1))
        assertFalse(store.isCurrent(1L, 2))
        store.put(data(1, revision = 2))
        assertTrue(store.isCurrent(1L, 2))
    }

    @Test fun `reminder bells are derived per channel`() {
        val slots = setOf(tv.own.owntv.core.sync.work.ProgramReminders.Key(1, t0 + hour))
        assertEquals(setOf(t0 + hour), GuideNav.reminderStarts(slots, 1))
        // Channel 2's set is unchanged by channel 1's reminder, so its derived state does not notify.
        assertEquals(emptySet<Long>(), GuideNav.reminderStarts(slots, 2))
        assertEquals(GuideNav.reminderStarts(emptySet(), 2), GuideNav.reminderStarts(slots, 2))
    }

    // ---- Overlapping guide entries --------------------------------------------------------------------

    @Test fun `the same entry from two feeds is drawn once`() {
        val a = prog(t0, t0 + hour, "News")
        val dup = a.copy() // same id
        val sameSlot = prog(t0, t0 + hour, "News (feed 2)")
        val drawn = GuideNav.normalizeProgrammes(listOf(a, dup, sameSlot))
        assertEquals(1, drawn.size)
        assertEquals(t0, drawn.single().drawStart)
    }

    @Test fun `at the same start the longer programme is kept`() {
        val short = prog(t0, t0 + 30 * min)
        val long = prog(t0, t0 + 2 * hour)
        assertEquals(listOf(long), GuideNav.normalizeProgrammes(listOf(short, long)).map { it.programme })
    }

    @Test fun `a programme inside another is left out of the drawing`() {
        val outer = prog(t0, t0 + 2 * hour)
        val inner = prog(t0 + 30 * min, t0 + hour)
        assertEquals(listOf(outer), GuideNav.normalizeProgrammes(listOf(inner, outer)).map { it.programme })
    }

    @Test fun `a partly overlapping programme is drawn from where the previous one ends`() {
        val a = prog(t0, t0 + hour)
        val b = prog(t0 + 40 * min, t0 + 2 * hour) // a second feed's slightly different schedule
        val drawn = GuideNav.normalizeProgrammes(listOf(a, b))
        assertEquals(listOf(t0, t0 + hour), drawn.map { it.drawStart })
        // The programme keeps its own times: reminders (keyed on the real start) and the dialog are unchanged.
        assertEquals(t0 + 40 * min, drawn[1].programme.startMs)
    }

    @Test fun `normalized cells never overlap`() {
        val messy = listOf(
            prog(t0, t0 + hour), prog(t0 + 10 * min, t0 + 50 * min), prog(t0 + 50 * min, t0 + 90 * min),
            prog(t0 + hour, t0 + 2 * hour), prog(t0 + 2 * hour, t0 + 3 * hour), prog(t0 + 2 * hour, t0 + 150 * min),
        )
        val drawn = GuideNav.normalizeProgrammes(messy.shuffled())
        drawn.zipWithNext().forEach { (a, b) -> assertTrue("${a.programme.id} overlaps ${b.programme.id}", b.drawStart >= a.programme.stopMs) }
        drawn.forEach { assertTrue(it.drawStart < it.programme.stopMs) }
    }

    @Test fun `normalizing leaves the original list untouched`() {
        val list = listOf(prog(t0, t0 + hour), prog(t0, t0 + hour))
        val copy = list.toList()
        GuideNav.normalizeProgrammes(list)
        assertEquals(copy, list)
    }

    @Test fun `catch-up is kept for the programmes that are drawn`() {
        val aired = prog(now - 2 * hour, now - hour)
        val dupAired = prog(now - 2 * hour, now - hour)
        val row = GuideNav.prepareRow(channel(5, catchup = true), listOf(aired, dupAired), now, 0)
        assertEquals(1, row.programmes.size)
        assertEquals(setOf(row.programmes.single().id), row.catchupIds)
    }

    @Test fun `right steps onto the next cell even when it starts inside the previous one`() {
        val a = prog(t0, t0 + hour)
        val b = prog(t0 + 40 * min, t0 + 2 * hour)
        val row = GuideNav.prepareRow(channel(6), listOf(a, b), now, 0)
        val t = GuideNav.stepTime(row.programmes, t0 + 10 * min, 1, t0 - hour, t0 + 24 * hour)!!
        assertEquals(t0 + hour, t) // b's visible start, not its real start (which is still a's cell)
        assertSame(b, GuideNav.programmeAt(row.programmes, t))
        assertEquals(1, GuideCellLayout.cellAt(row.drawStarts, row.programmes, t))
        // And back.
        assertEquals(t0, GuideNav.stepTime(row.programmes, t, -1, t0 - hour, t0 + 24 * hour))
    }

    @Test fun `exactly one cell is highlighted at any time`() {
        val row = GuideNav.prepareRow(channel(7), listOf(prog(t0, t0 + hour), prog(t0 + 40 * min, t0 + 2 * hour)), now, 0)
        for (m in 0 until 120 step 5) {
            val t = t0 + m * min
            val hits = row.programmes.indices.count { i -> t >= row.drawStarts[i] && t < row.programmes[i].stopMs }
            assertEquals("at minute $m", 1, hits)
        }
    }
}
