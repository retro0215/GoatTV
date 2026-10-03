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
import tv.own.owntv.features.sports.live.SportsPreviewController.Companion.DETAIL_SETTLE_MS
import tv.own.owntv.features.sports.live.SportsPreviewController.Companion.FAILURE_RETRY_MS

/**
 * Game Center request lifecycle in the preview controller: progressive display, stable-focus settle,
 * backend refresh hints, session cache, failures and stale-response protection. Time is fully controlled:
 * every wait the controller asks for is recorded and released by the test.
 */
class SportsGameCenterLifecycleTest {

    /** Records each requested wait; the test releases them (in order) to advance "time". */
    private class TimedWaits {
        val requested = mutableListOf<Long>()
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val wait: suspend (Long) -> Unit = { ms ->
            requested += ms
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            try {
                gate.await()
            } finally {
                gates.remove(gate)
            }
        }
        val waiting: Int get() = gates.size
        fun release() = gates.first().complete(Unit)
    }

    /** Answers each event's requests from a script (last answer repeats) and counts them. */
    private class ScriptedSource : GameCenterDetailSource {
        val scripts = HashMap<String, ArrayDeque<GameCenterFetch>>()
        val requests = mutableListOf<String>()
        override suspend fun fetch(eventId: String): GameCenterFetch {
            requests += eventId
            val q = scripts.getValue(eventId)
            return if (q.size > 1) q.removeFirst() else q.first()
        }
        fun script(eventId: String, vararg answers: GameCenterFetch) { scripts[eventId] = ArrayDeque(answers.toList()) }
    }

    private fun loaded(id: String, availability: GameCenterAvailability, refreshMs: Long?, stats: List<GameCenterTeamStat> = emptyList()) =
        GameCenterFetch.Loaded(GameCenterDetail(id, availability, refreshAfterMs = refreshMs, teamStats = stats))

    private fun available(id: String, refreshMs: Long? = 30_000L, value: String = "1") =
        loaded(id, GameCenterAvailability.AVAILABLE, refreshMs, listOf(GameCenterTestData.stat("shots", value, value)))

    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val waits = TimedWaits()
    private val source = ScriptedSource()
    private val controller = SportsPreviewController(scope, source, detailWait = waits.wait, clock = { now })

    private fun ev(id: String, status: SportsEventStatus = SportsEventStatus.LIVE) = GameCenterTestData.game(id = id, status = status)
    private fun stateValue(): String? = ((controller.detail.value as? GameCenterDetailState.Ready)?.detail?.teamStats?.firstOrNull()?.away)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `focus - basic pane at once, one request only after stable focus`() {
        source.script("evt_a", available("evt_a"))
        controller.onEventFocused(ev("evt_a"))
        assertEquals(SportsPreviewMode.EventGameCenter("evt_a"), controller.mode.value)
        assertEquals(GameCenterDetailState.Pending("evt_a"), controller.detail.value)
        assertEquals(listOf(DETAIL_SETTLE_MS), waits.requested)
        assertTrue("nothing sent before the settle", source.requests.isEmpty())
        waits.release()
        assertEquals(listOf("evt_a"), source.requests)
        assertTrue(controller.detail.value is GameCenterDetailState.Ready)
    }

    @Test
    fun `PENDING then AVAILABLE - polls on the backend hint, then follows the live hint`() {
        source.script("evt_up", loaded("evt_up", GameCenterAvailability.PENDING, 10_000L), loaded("evt_up", GameCenterAvailability.PENDING, 10_000L), available("evt_up", 120_000L))
        controller.onEventFocused(ev("evt_up", SportsEventStatus.SCHEDULED))
        waits.release() // settle
        assertEquals(GameCenterDetailState.Pending("evt_up"), controller.detail.value)
        assertEquals(10_000L, waits.requested.last())
        waits.release()
        assertEquals(GameCenterDetailState.Pending("evt_up"), controller.detail.value)
        waits.release()
        assertTrue(controller.detail.value is GameCenterDetailState.Ready)
        assertEquals(listOf(DETAIL_SETTLE_MS, 10_000L, 10_000L, 120_000L), waits.requested)
        assertEquals(3, source.requests.size)
    }

    @Test
    fun `UNAVAILABLE - basic pane stays, no retry`() {
        source.script("evt_x", loaded("evt_x", GameCenterAvailability.UNAVAILABLE, null))
        controller.onEventFocused(ev("evt_x"))
        waits.release()
        assertEquals(GameCenterDetailState.Unavailable("evt_x"), controller.detail.value)
        assertEquals(0, waits.waiting)
        assertEquals(1, source.requests.size)
    }

    @Test
    fun `refreshAfterSeconds - the next request waits exactly the backend hint`() {
        source.script("evt_live", available("evt_live", 30_000L, "1"), available("evt_live", 30_000L, "2"))
        controller.onEventFocused(ev("evt_live"))
        waits.release()
        assertEquals(30_000L, waits.requested.last())
        assertEquals("1", stateValue())
        waits.release()
        assertEquals("2", stateValue())
        assertEquals(2, source.requests.size)
    }

    @Test
    fun `network failure without cache - basic pane, retry only on the normal schedule`() {
        source.script("evt_f", GameCenterFetch.Failed(), available("evt_f"))
        controller.onEventFocused(ev("evt_f"))
        waits.release()
        assertEquals(GameCenterDetailState.Unavailable("evt_f"), controller.detail.value)
        assertEquals(FAILURE_RETRY_MS, waits.requested.last())
        waits.release()
        assertTrue(controller.detail.value is GameCenterDetailState.Ready)
    }

    @Test
    fun `network failure with last good detail - the detail stays, 429 Retry-After honoured`() {
        source.script("evt_g", available("evt_g", 30_000L, "7"), GameCenterFetch.Failed(), GameCenterFetch.Failed(120_000L))
        controller.onEventFocused(ev("evt_g"))
        waits.release()
        waits.release() // refresh → failure
        assertEquals("7", stateValue())
        assertEquals(FAILURE_RETRY_MS, waits.requested.last()) // max(hint 30 s, 30 s)
        waits.release() // → 429
        assertEquals("7", stateValue())
        assertEquals(120_000L, waits.requested.last())
    }

    @Test
    fun `focus changes before the response - the late response never populates another event`() {
        val gate = CompletableDeferred<GameCenterFetch>()
        val slow = SportsPreviewController(scope, source = { id -> if (id == "evt_a") gate.await() else available(id, null, "B") }, detailWait = waits.wait, clock = { now })
        slow.onEventFocused(ev("evt_a"))
        waits.release() // A's request in flight
        slow.onEventFocused(ev("evt_b"))
        waits.release() // B settled and answered
        gate.complete(available("evt_a", null, "A"))
        assertEquals("evt_b", slow.detail.value.eventId)
        assertEquals("B", (slow.detail.value as GameCenterDetailState.Ready).detail.teamStats.first().away)
    }

    @Test
    fun `rapid D-pad sweep - 15 cards crossed send no request, the card you stop on sends one`() {
        val ids = (1..15).map { "evt_$it" }
        ids.forEach { source.script(it, available(it)) }
        ids.forEach { controller.onEventFocused(ev(it)) } // each crossing cancels the previous settle
        assertTrue(source.requests.isEmpty())
        assertEquals(1, waits.waiting)
        waits.release()
        assertEquals(listOf("evt_15"), source.requests)
    }

    @Test
    fun `stable focus for 30+ seconds on a live game - one request per backend hint`() {
        source.script("evt_live", available("evt_live", 30_000L))
        controller.onEventFocused(ev("evt_live"))
        waits.release() // t = 0.4 s: first request
        waits.release() // t = 30.4 s: refresh
        waits.release() // t = 60.4 s: refresh
        assertEquals(3, source.requests.size)
        assertEquals(listOf(DETAIL_SETTLE_MS, 30_000L, 30_000L, 30_000L), waits.requested)
    }

    @Test
    fun `revisit - fresh cache shows at once and waits out the hint, expired cache shows while refreshing`() {
        source.script("evt_a", available("evt_a", 30_000L, "1"), available("evt_a", 30_000L, "2"))
        source.script("evt_b", available("evt_b"))
        controller.onEventFocused(ev("evt_a"))
        waits.release()
        assertEquals(1, source.requests.size)
        controller.onEventFocused(ev("evt_b"))
        now += 10_000
        controller.onEventFocused(ev("evt_a"))
        assertEquals("1", stateValue()) // instantly, from this session's cache
        assertEquals(20_000L, waits.requested.last()) // only the remainder of the hint
        assertEquals(1, source.requests.count { it == "evt_a" })
        controller.onEventFocused(ev("evt_b"))
        now += 60_000
        controller.onEventFocused(ev("evt_a"))
        assertEquals("1", stateValue()) // expired data still shown…
        assertEquals(DETAIL_SETTLE_MS, waits.requested.last()) // …and refreshed after the settle
        waits.release()
        assertEquals("2", stateValue())
    }

    @Test
    fun `cache - a later PENDING or UNAVAILABLE never replaces data already held`() {
        source.script("evt_a", available("evt_a", 30_000L, "5"), loaded("evt_a", GameCenterAvailability.UNAVAILABLE, null))
        controller.onEventFocused(ev("evt_a"))
        waits.release()
        waits.release()
        assertEquals("5", stateValue())
        assertEquals(0, waits.waiting) // UNAVAILABLE has no refresh hint
    }

    @Test
    fun `leaving the event or Sports closing stops all Game Center work`() {
        source.script("evt_a", available("evt_a"))
        controller.onEventFocused(ev("evt_a"))
        waits.release()
        controller.onChannelFocused()
        assertEquals(0, waits.waiting)
        assertEquals(1, source.requests.size)

        controller.onEventFocused(ev("evt_a"))
        scope.cancel() // Sports leaves (view model cleared)
        assertEquals(0, waits.waiting)
        assertEquals(1, source.requests.size)
    }
}
