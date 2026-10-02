package tv.own.owntv.features.sports.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.GameCenterTestData.detail

class SportsPreviewControllerTest {

    /** Detail source whose responses the test releases by hand (out of order, late, failing). */
    private class ManualSource : GameCenterDetailSource {
        val pending = LinkedHashMap<String, CompletableDeferred<GameCenterDetail?>>()
        val requested = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override suspend fun detail(eventId: String): GameCenterDetail? {
            requested += eventId
            val d = CompletableDeferred<GameCenterDetail?>().also { pending[eventId] = it }
            try {
                return d.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += eventId
                throw e
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = ManualSource()
    private val controller = SportsPreviewController(scope, source)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `starts on channel video`() {
        assertEquals(SportsPreviewMode.ChannelVideo, controller.mode.value)
        assertEquals(GameCenterDetailState.Idle, controller.detail.value)
    }

    @Test
    fun `event focus shows Game Center immediately, before any detail`() {
        controller.onEventFocused("evt_a")
        assertEquals(SportsPreviewMode.EventGameCenter("evt_a"), controller.mode.value)
        assertEquals(GameCenterDetailState.Pending("evt_a"), controller.detail.value)
        source.pending.getValue("evt_a").complete(detail("evt_a"))
        assertEquals(GameCenterDetailState.Ready("evt_a", detail("evt_a")), controller.detail.value)
    }

    @Test
    fun `channel focus returns to channel video`() {
        controller.onEventFocused("evt_a")
        controller.onChannelFocused()
        assertEquals(SportsPreviewMode.ChannelVideo, controller.mode.value)
        assertEquals(listOf("evt_a"), source.cancelled) // obsolete detail work cancelled
    }

    @Test
    fun `rapid focus changes - only the last event's detail is shown, obsolete work cancelled`() {
        val ids = (1..12).map { "evt_$it" }
        ids.forEach { controller.onEventFocused(it) }
        assertEquals(SportsPreviewMode.EventGameCenter("evt_12"), controller.mode.value)
        assertEquals(ids.dropLast(1), source.cancelled)
        // A late response for an earlier card cannot overwrite the focused one.
        source.pending.getValue("evt_3").complete(detail("evt_3"))
        assertEquals(GameCenterDetailState.Pending("evt_12"), controller.detail.value)
        source.pending.getValue("evt_12").complete(detail("evt_12"))
        assertEquals("evt_12", controller.detail.value.eventId)
        assertTrue(controller.detail.value is GameCenterDetailState.Ready)
    }

    @Test
    fun `refocusing the same event does no new work`() {
        controller.onEventFocused("evt_a")
        controller.onEventFocused("evt_a")
        assertEquals(listOf("evt_a"), source.requested)
    }

    @Test
    fun `returning to an event whose detail is held reuses it`() {
        controller.onEventFocused("evt_a")
        source.pending.getValue("evt_a").complete(detail("evt_a"))
        controller.onChannelFocused()
        controller.onEventFocused("evt_a")
        assertEquals(listOf("evt_a"), source.requested)
        assertTrue(controller.detail.value is GameCenterDetailState.Ready)
    }

    @Test
    fun `no detail or a failing source - unavailable, the matchup still renders`() {
        controller.onEventFocused("evt_a")
        source.pending.getValue("evt_a").complete(null)
        assertEquals(GameCenterDetailState.Unavailable("evt_a"), controller.detail.value)

        val failing = SportsPreviewController(scope) { error("boom") }
        failing.onEventFocused("evt_b")
        assertEquals(GameCenterDetailState.Unavailable("evt_b"), failing.detail.value)
        assertEquals(SportsPreviewMode.EventGameCenter("evt_b"), failing.mode.value)
    }

    @Test
    fun `production source does no work and yields unavailable`() {
        val prod = SportsPreviewController(scope, GameCenterDetailSource.None)
        prod.onEventFocused("evt_a")
        assertEquals(GameCenterDetailState.Unavailable("evt_a"), prod.detail.value)
    }

    @Test
    fun `event video is reserved and never produced in this phase`() {
        controller.onEventFocused("evt_live")
        controller.onChannelFocused()
        controller.onEventFocused("evt_live2")
        assertFalse(controller.mode.value is SportsPreviewMode.EventVideo)
        assertEquals(600L, SportsPreviewMode.EVENT_VIDEO_SETTLE_MS)
    }
}
