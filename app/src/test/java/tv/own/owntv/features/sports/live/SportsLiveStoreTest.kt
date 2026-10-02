package tv.own.owntv.features.sports.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.SportsFixtures.event

class SportsLiveStoreTest {

    private class FakeApi : SportsApi {
        val homeResults = ArrayDeque<SportsApiResult<SportsHomePayload>>()
        val deltaResults = ArrayDeque<SportsApiResult<SportsEventsPayload>>()
        val calls = mutableListOf<String>()
        override suspend fun home(): SportsApiResult<SportsHomePayload> { calls += "home"; return homeResults.removeFirst() }
        override suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload> { calls += "since:$cursor"; return deltaResults.removeFirst() }
        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> {
            calls += "sport:$sport"
            return SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
        }
    }

    private var clock = SportsApiParser.parseIsoUtcMs("2026-10-02T12:00:00Z")!!
    private val api = FakeApi()
    private val store = SportsLiveStore(api) { clock }

    private fun homeOk(vararg ids: String) = SportsApiResult.Success(
        SportsApiParser.parseHome(SportsFixtures.home(ids.map { event(it) }, ids.toList()))!!,
    )
    private fun deltaOk(vararg events: org.json.JSONObject) = SportsApiResult.Success(SportsApiParser.parseEventsPage(SportsFixtures.page(events.toList()))!!)
    private val unavailable = SportsApiResult.Unavailable(SportsApiResult.Reason.NOT_JSON)

    @Test
    fun `first load - Loading then Content from home`() = runBlocking {
        assertEquals(SportsLiveState.Loading, store.state.value)
        api.homeResults += homeOk("evt_a")
        store.refresh()
        val s = store.state.value as SportsLiveState.Content
        assertFalse(s.stale)
        assertEquals(setOf("evt_a"), s.slate.eventsById.keys)
    }

    @Test
    fun `no data and the API is down - TemporarilyUnavailable, then recovers`() = runBlocking {
        api.homeResults += unavailable
        store.refresh()
        assertEquals(SportsLiveState.TemporarilyUnavailable, store.state.value)
        api.homeResults += homeOk("evt_a")
        store.refresh()
        assertTrue(store.state.value is SportsLiveState.Content)
    }

    @Test
    fun `a failed incremental refresh keeps the last-known-good slate (marked stale)`() = runBlocking {
        api.homeResults += homeOk("evt_a", "evt_b")
        store.refresh()
        clock += 30_000
        api.deltaResults += unavailable
        store.refresh()
        val s = store.state.value as SportsLiveState.Content
        assertTrue(s.stale)
        assertEquals(setOf("evt_a", "evt_b"), s.slate.eventsById.keys)
        // and recovers on the next success
        clock += 60_000
        api.deltaResults += deltaOk(event("evt_a", status = "LIVE", updated = "2026-10-02T12:01:00.000Z"))
        store.refresh()
        val r = store.state.value as SportsLiveState.Content
        assertFalse(r.stale)
        assertEquals(SportsEventStatus.LIVE, r.slate.eventsById.getValue("evt_a").status)
    }

    @Test
    fun `incremental refresh uses the cursor, home is reloaded every 10 minutes`() = runBlocking {
        api.homeResults += homeOk("evt_a")
        store.refresh()
        clock += 60_000
        api.deltaResults += deltaOk()
        store.refresh()
        assertEquals("since:2026-10-02T12:00:00.000Z", api.calls[1])
        clock += SportsLiveStore.HOME_RELOAD_MS
        api.homeResults += homeOk("evt_a", "evt_z")
        store.refresh()
        assertEquals("home", api.calls.last())
    }

    @Test
    fun `a rejected since (cursor too old) falls back to home`() = runBlocking {
        api.homeResults += homeOk("evt_a")
        store.refresh()
        clock += 30_000
        api.deltaResults += SportsApiResult.Rejected(400, "since is too old; reload /home.")
        api.homeResults += homeOk("evt_b")
        store.refresh()
        assertEquals(listOf("home", "since:2026-10-02T12:00:00.000Z", "home"), api.calls)
        assertEquals(setOf("evt_b"), (store.state.value as SportsLiveState.Content).slate.eventsById.keys)
    }

    @Test
    fun `rate limiting waits at least Retry-After and keeps data`() = runBlocking {
        api.homeResults += homeOk("evt_a")
        store.refresh()
        clock += 30_000
        api.deltaResults += SportsApiResult.RateLimited(420)
        val delay = store.refresh()
        assertTrue(delay >= 420_000)
        assertTrue((store.state.value as SportsLiveState.Content).stale)
        assertEquals(delay, store.millisUntilDue())
    }

    @Test
    fun `returning to the screen does not refetch before the next refresh is due`() = runBlocking {
        api.homeResults += homeOk("evt_a")
        val delay = store.refresh()
        clock += 10_000
        assertEquals(delay - 10_000, store.millisUntilDue())
        clock += delay
        assertEquals(0L, store.millisUntilDue())
    }
}
