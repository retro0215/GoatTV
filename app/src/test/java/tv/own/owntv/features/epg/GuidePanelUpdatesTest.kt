package tv.own.owntv.features.epg

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.features.epg.GuideNav.Cursor
import tv.own.owntv.features.epg.GuideNav.PanelState
import tv.own.owntv.features.epg.GuideNav.PanelText

/**
 * Phase 3F hotfix: the details panel's update pipeline ([GuideNav.panelRequests] + [GuideNav.showPanel])
 * on virtual time. On the onn, 3F re-laid out the panel ~50 times a second while idle (its strings were
 * rebuilt on every composition, and each layout recomposed the panel), which also cancelled every
 * synopsis load and kept running behind the home screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidePanelUpdatesTest {

    private val hour = 3_600_000L
    private val t0 = 1_800_000_000_000L
    private val synopsisSettle = 150L

    /** 30 rows of hourly programmes; ids are row * 100 + hour. */
    private val rows: List<List<EpgProgrammeEntity>> = List(30) { r ->
        List(12) { h ->
            EpgProgrammeEntity(id = r * 100L + h, sourceId = 1, epgChannelId = "c$r", startMs = t0 + h * hour, stopMs = t0 + (h + 1) * hour, title = "R$r H$h")
        }
    }

    /** A new instance each call, like 3F's per-composition strings: equal wording, different identity. */
    private fun newStrings() = GuideNav.PanelStrings(
        nowLabel = "NOW", catchup = "catch-up", reminderSet = "Reminder set", separator = " · ",
        noProgramme = "No program", hint = "hint",
        range = { a, b -> "${(a - t0) / hour}-${(b - t0) / hour}" },
        runtime = { m -> "${m}m" },
        nextLabel = { t -> "NEXT ${(t - t0) / hour}" },
    )

    private data class Inputs(val now: Long, val strings: GuideNav.PanelStrings)

    private fun describe(c: Cursor?, inp: Inputs): PanelText {
        val programmes = c?.let { rows[it.row] }.orEmpty()
        return GuideNav.panelText(programmes, c?.timeMs ?: inp.now, inp.now, emptySet(), { false }, null, "hint", inp.strings)
    }

    private fun at(row: Int, h: Int) = Cursor(row, t0 + h * hour + 10 * 60_000L)

    private class Recorder {
        val layouts = mutableListOf<PanelText>()
        val loads = mutableListOf<Long>()
        val last: PanelText get() = layouts.last()
    }

    /** Runs the panel over [states] until the returned job is cancelled; [onLayout] can feed back into the states. */
    private fun TestScope.runPanel(
        states: MutableStateFlow<PanelState<Inputs>>,
        rec: Recorder,
        onLayout: () -> Unit = {},
    ) = launch {
        val requests = GuideNav.panelRequests(states, clock = { testScheduler.currentTime }, describe = ::describe)
        GuideNav.showPanel(requests, synopsisSettle, loadSynopsis = { id -> rec.loads += id; "Synopsis $id" }) { text, _ ->
            rec.layouts += text
            onLayout()
        }
    }

    // ---- Stable idle state ----------------------------------------------------------------------

    @Test fun `an idle panel stops laying out even when every layout rebuilds the strings`() = runTest {
        // The 3F loop: each layout recomposed the panel, which produced inputs with new strings.
        val states = MutableStateFlow(PanelState(at(3, 2), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec) { states.value = states.value.copy(inputs = states.value.inputs.copy(strings = newStrings())) }
        advanceTimeBy(10_000)
        runCurrent()
        // Once without the synopsis, once with it; nothing after.
        assertEquals(2, rec.layouts.size)
        assertEquals(listOf(302L), rec.loads)
        job.cancel()
    }

    @Test fun `repeated equal states while idle cause no further layout`() = runTest {
        val states = MutableStateFlow(PanelState(at(1, 1), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        advanceTimeBy(1_000); runCurrent()
        val settled = rec.layouts.size
        repeat(300) { states.value = states.value.copy(inputs = Inputs(t0, newStrings())); advanceTimeBy(20); runCurrent() }
        assertEquals(settled, rec.layouts.size)
        assertEquals(1, rec.loads.size)
        job.cancel()
    }

    // ---- Synopsis loading -----------------------------------------------------------------------

    @Test fun `the synopsis loads once the cursor rests and is shown`() = runTest {
        val states = MutableStateFlow(PanelState(at(0, 4), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        runCurrent()
        assertNull(rec.last.synopsis)
        advanceTimeBy(synopsisSettle - 10); runCurrent()
        assertTrue(rec.loads.isEmpty())
        advanceTimeBy(20); runCurrent()
        assertEquals(listOf(4L), rec.loads)
        assertEquals("Synopsis 4", rec.last.synopsis)
        job.cancel()
    }

    @Test fun `equal states during the synopsis wait do not cancel it`() = runTest {
        val states = MutableStateFlow(PanelState(at(0, 4), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        runCurrent()
        repeat(10) { advanceTimeBy(20); states.value = states.value.copy(inputs = Inputs(t0, newStrings())); runCurrent() }
        advanceTimeBy(synopsisSettle); runCurrent()
        assertEquals("Synopsis 4", rec.last.synopsis)
        job.cancel()
    }

    @Test fun `an update that keeps the programme shows its synopsis without reading it again`() = runTest {
        val states = MutableStateFlow(PanelState(at(0, 4), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        advanceTimeBy(1_000); runCurrent()
        // The clock reaches the programme: its details line gains NOW, the programme stays.
        states.value = states.value.copy(inputs = Inputs(t0 + 4 * hour + 5 * 60_000L, newStrings()))
        advanceTimeBy(1_000); runCurrent()
        assertTrue(rec.last.bits.startsWith("NOW"))
        assertEquals("Synopsis 4", rec.last.synopsis)
        assertEquals(listOf(4L), rec.loads)
        job.cancel()
    }

    @Test fun `moving on before the synopsis wait ends skips that read`() = runTest {
        val states = MutableStateFlow(PanelState(at(0, 4), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        runCurrent()
        advanceTimeBy(100)
        states.value = states.value.copy(cursor = at(1, 4))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(104L), rec.loads)
        assertEquals("R1 H4", rec.last.title)
        job.cancel()
    }

    // ---- Rapid navigation ---------------------------------------------------------------------

    @Test fun `rapid moves end in one update for the programme the cursor stopped on`() = runTest {
        val states = MutableStateFlow(PanelState(at(0, 2), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        advanceTimeBy(1_000); runCurrent()
        // The first press after a pause can't know more follow: it shows after the short settle.
        states.value = states.value.copy(cursor = at(1, 2)); advanceTimeBy(120); runCurrent()
        assertEquals("R1 H2", rec.last.title)
        val before = rec.layouts.size
        for (r in 2..20) { states.value = states.value.copy(cursor = at(r, 2)); advanceTimeBy(120); runCurrent() }
        // No update while the remote is held.
        assertEquals(before, rec.layouts.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("R20 H2", rec.last.title)
        assertEquals(before + 2, rec.layouts.size) // the programme, then its synopsis
        // Row 1's synopsis read was dropped when the next press came: only the rest stops are read.
        assertEquals(listOf(2L, 2002L), rec.loads)
        job.cancel()
    }

    @Test fun `a single move after a pause updates within its short settle`() = runTest {
        val states = MutableStateFlow(PanelState(at(0, 2), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        advanceTimeBy(1_000); runCurrent()
        states.value = states.value.copy(cursor = at(0, 3))
        advanceTimeBy(GuideNav.panelSettleMs(1_000) + 1); runCurrent()
        assertEquals("R0 H3", rec.last.title)
        job.cancel()
    }

    @Test fun `the first showing does not wait`() = runTest {
        val states = MutableStateFlow(PanelState(at(5, 1), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        runCurrent()
        assertEquals("R5 H1", rec.last.title)
        job.cancel()
    }

    @Test fun `nothing is laid out before the panel has a width`() = runTest {
        val states = MutableStateFlow(PanelState(at(5, 1), 0, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(rec.layouts.isEmpty())
        states.value = states.value.copy(widthPx = 900)
        runCurrent()
        assertEquals(1, rec.layouts.size)
        job.cancel()
    }

    // ---- Leaving the guide --------------------------------------------------------------------

    @Test fun `no layout work runs once the panel stops collecting`() = runTest {
        val states = MutableStateFlow(PanelState(at(0, 2), 1000, Inputs(t0, newStrings())))
        val rec = Recorder()
        val job = runPanel(states, rec) { states.value = states.value.copy(inputs = Inputs(t0, newStrings())) }
        advanceTimeBy(1_000); runCurrent()
        job.cancel() // the screen stopped (repeatOnLifecycle) or left the composition
        val count = rec.layouts.size
        repeat(50) { states.value = states.value.copy(cursor = at(it % 30, 3)); advanceTimeBy(50); runCurrent() }
        assertEquals(count, rec.layouts.size)
        // Back on screen: the programme and its synopsis once each, not a loop.
        val again = runPanel(states, rec) { states.value = states.value.copy(inputs = Inputs(t0, newStrings())) }
        advanceTimeBy(5_000); runCurrent()
        assertEquals(count + 2, rec.layouts.size)
        again.cancel()
    }
}
