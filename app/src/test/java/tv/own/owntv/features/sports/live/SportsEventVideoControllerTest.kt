package tv.own.owntv.features.sports.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

class SportsEventVideoControllerTest {

    /** Each dwell suspends until the test releases it — no real 3 s waits, full control of timing. */
    private val dwells = ArrayList<CompletableDeferred<Unit>>()
    private val resolutions = ArrayList<String>()
    private val channelsByEvent = HashMap<String, List<ResolvedSportsChannel>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val controller = SportsPreviewController(
        scope = scope,
        source = GameCenterDetailSource.None,
        channels = { e -> resolutions += e.id; channelsByEvent[e.id].orEmpty() },
        channelsEnabled = true,
        dwell = { CompletableDeferred<Unit>().also { dwells += it }.await() },
    )

    @After
    fun tearDown() = scope.cancel()

    private fun ev(id: String, status: SportsEventStatus) = GameCenterTestData.game(id = id, status = status)

    private fun channel(id: Long, name: String) = ChannelEntity(id = id, sourceId = 7, name = name, streamUrl = "built-locally", remoteId = "r$id")

    private fun resolved(vararg ch: ChannelEntity) = ch.map {
        ResolvedSportsChannel(SportsChannelRef("sch_${it.id}", it.remoteId!!, null, it.name, null, null, null, 90, "test"), it)
    }

    private val espn = channel(1, "US: ESPN")
    private val espn4k = channel(2, "US: ESPN 4K")
    private val backup = channel(3, "US: ESPN Backup")

    private fun video(eventId: String, ch: ChannelEntity) = SportsPreviewMode.EventVideo(eventId, ch)
    private fun gc(eventId: String) = SportsPreviewMode.EventGameCenter(eventId)

    @Test
    fun `live + valid channel - Game Center at once, video only after the dwell, highest-ranked channel`() {
        channelsByEvent["live"] = resolved(espn, espn4k)
        controller.onEventFocused(ev("live", SportsEventStatus.LIVE))
        assertEquals(gc("live"), controller.mode.value)
        assertTrue("no resolution / tune before the dwell", resolutions.isEmpty())
        dwells.single().complete(Unit)
        assertEquals(video("live", espn), controller.mode.value) // primary before the 4K alternate
    }

    @Test
    fun `focus change cancels the dwell - the previous event never tunes`() {
        channelsByEvent["a"] = resolved(espn)
        channelsByEvent["b"] = resolved(backup)
        controller.onEventFocused(ev("a", SportsEventStatus.LIVE))
        controller.onEventFocused(ev("b", SportsEventStatus.LIVE))
        dwells[0].complete(Unit) // a's dwell was cancelled: completing it does nothing
        assertEquals(gc("b"), controller.mode.value)
        assertTrue(resolutions.isEmpty())
        dwells[1].complete(Unit)
        assertEquals(video("b", backup), controller.mode.value)
        assertEquals(listOf("b"), resolutions)
    }

    @Test
    fun `rapid D-pad sweep - no timers accumulate, no resolution, no tune`() {
        (1..20).forEach { channelsByEvent["e$it"] = resolved(espn); controller.onEventFocused(ev("e$it", SportsEventStatus.LIVE)) }
        assertEquals(20, dwells.size)
        dwells.dropLast(1).forEach { it.complete(Unit) } // all stale
        assertEquals(gc("e20"), controller.mode.value)
        assertTrue(resolutions.isEmpty())
    }

    @Test
    fun `upcoming, final, postponed, delayed, canceled never auto-preview`() {
        for (status in listOf(SportsEventStatus.SCHEDULED, SportsEventStatus.FINAL, SportsEventStatus.POSTPONED, SportsEventStatus.DELAYED, SportsEventStatus.CANCELED)) {
            val id = "x_$status"
            channelsByEvent[id] = resolved(espn)
            controller.onEventFocused(ev(id, status))
            assertEquals(gc(id), controller.mode.value)
        }
        assertTrue("no dwell is even started", dwells.isEmpty())
    }

    @Test
    fun `live with no valid channel stays Game Center`() {
        channelsByEvent["live"] = emptyList()
        controller.onEventFocused(ev("live", SportsEventStatus.LIVE))
        dwells.single().complete(Unit)
        assertEquals(gc("live"), controller.mode.value)
    }

    @Test
    fun `double OK toggles video and Game Center, and stops the auto dwell from re-switching`() {
        channelsByEvent["live"] = resolved(espn)
        val e = ev("live", SportsEventStatus.LIVE)
        controller.onEventFocused(e)
        dwells.single().complete(Unit)
        assertEquals(video("live", espn), controller.mode.value)
        controller.toggleEventVideo(e)
        assertEquals(gc("live"), controller.mode.value)
        controller.toggleEventVideo(e)
        assertEquals(video("live", espn), controller.mode.value)
        controller.toggleEventVideo(e)
        assertEquals(gc("live"), controller.mode.value)
        assertEquals(1, dwells.size) // toggling never schedules another automatic switch
    }

    @Test
    fun `double OK before the dwell switches immediately, and the dwell no longer matters`() {
        channelsByEvent["live"] = resolved(espn)
        val e = ev("live", SportsEventStatus.LIVE)
        controller.onEventFocused(e)
        controller.toggleEventVideo(e)
        assertEquals(video("live", espn), controller.mode.value)
        dwells.single().complete(Unit)
        assertEquals(video("live", espn), controller.mode.value)
    }

    @Test
    fun `double OK with no valid channel or a non-live event safely stays Game Center`() {
        channelsByEvent["live"] = emptyList()
        controller.onEventFocused(ev("live", SportsEventStatus.LIVE))
        controller.toggleEventVideo(ev("live", SportsEventStatus.LIVE))
        assertEquals(gc("live"), controller.mode.value)
        channelsByEvent["up"] = resolved(espn)
        controller.onEventFocused(ev("up", SportsEventStatus.SCHEDULED))
        controller.toggleEventVideo(ev("up", SportsEventStatus.SCHEDULED))
        assertEquals(gc("up"), controller.mode.value)
    }

    @Test
    fun `selected feed is remembered for the event this session`() {
        channelsByEvent["live"] = resolved(espn, espn4k, backup)
        val e = ev("live", SportsEventStatus.LIVE)
        controller.onEventFocused(e)
        controller.selectFeed(e, backup)
        assertEquals(video("live", backup), controller.mode.value)
        controller.onChannelFocused()
        controller.onEventFocused(e)
        dwells.last().complete(Unit)
        assertEquals(video("live", backup), controller.mode.value) // not the top-ranked feed
    }

    @Test
    fun `remembered feed that no longer verifies falls back to the highest-ranked one`() {
        channelsByEvent["live"] = resolved(espn, backup)
        val e = ev("live", SportsEventStatus.LIVE)
        controller.onEventFocused(e)
        controller.selectFeed(e, backup)
        channelsByEvent["live"] = resolved(espn) // backup removed from the playlist meanwhile
        controller.onChannelFocused()
        controller.onEventFocused(e)
        dwells.last().complete(Unit)
        assertEquals(video("live", espn), controller.mode.value)
    }

    @Test
    fun `tune failure returns to Game Center and is not retried automatically`() {
        channelsByEvent["live"] = resolved(espn)
        val e = ev("live", SportsEventStatus.LIVE)
        controller.onEventFocused(e)
        dwells.single().complete(Unit)
        controller.onEventVideoFailed("live")
        assertEquals(gc("live"), controller.mode.value)
        controller.onChannelFocused()
        controller.onEventFocused(e)
        assertEquals(1, dwells.size) // no new automatic dwell for a failed event
        controller.toggleEventVideo(e) // an explicit action may retry
        assertEquals(video("live", espn), controller.mode.value)
    }

    @Test
    fun `a failure report for another event is ignored`() {
        channelsByEvent["live"] = resolved(espn)
        controller.onEventFocused(ev("live", SportsEventStatus.LIVE))
        dwells.single().complete(Unit)
        controller.onEventVideoFailed("other")
        assertEquals(video("live", espn), controller.mode.value)
    }

    @Test
    fun `moving to a channel card leaves event video`() {
        channelsByEvent["live"] = resolved(espn)
        controller.onEventFocused(ev("live", SportsEventStatus.LIVE))
        dwells.single().complete(Unit)
        controller.onChannelFocused()
        assertEquals(SportsPreviewMode.ChannelVideo, controller.mode.value)
    }

    @Test
    fun `refocusing the same event (back from a dialog) keeps its video`() {
        channelsByEvent["live"] = resolved(espn)
        val e = ev("live", SportsEventStatus.LIVE)
        controller.onEventFocused(e)
        dwells.single().complete(Unit)
        controller.onEventFocused(e)
        assertEquals(video("live", espn), controller.mode.value)
        assertEquals(1, dwells.size)
    }
}
