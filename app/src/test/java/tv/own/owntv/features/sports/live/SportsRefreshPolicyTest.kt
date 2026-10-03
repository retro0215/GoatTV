package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.features.sports.live.SportsFixtures.event

class SportsRefreshPolicyTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-10-02T12:00:00Z")!!

    private fun slate(vararg events: org.json.JSONObject) =
        SportsSlateLogic.fromHome(SportsApiParser.parseHome(SportsFixtures.home(events.toList(), emptyList()))!!, now)

    @Test
    fun `cadence - live 30s, starting soon 60s, otherwise 5 min`() {
        assertEquals(SportsRefreshPolicy.LIVE_MS, SportsRefreshPolicy.baseDelayMs(slate(event("e1", status = "LIVE"), event("e2")), now))
        assertEquals(SportsRefreshPolicy.LIVE_MS, SportsRefreshPolicy.baseDelayMs(slate(event("e1", status = "DELAYED")), now))
        assertEquals(SportsRefreshPolicy.STARTING_SOON_MS, SportsRefreshPolicy.baseDelayMs(slate(event("e1", start = "2026-10-02T12:20:00Z")), now))
        assertEquals("a scheduled game that should have started is watched closely", SportsRefreshPolicy.STARTING_SOON_MS,
            SportsRefreshPolicy.baseDelayMs(slate(event("e1", start = "2026-10-02T11:50:00Z")), now))
        assertEquals(SportsRefreshPolicy.IDLE_MS, SportsRefreshPolicy.baseDelayMs(slate(event("e1", start = "2026-10-03T12:00:00Z"), event("e2", status = "FINAL", start = "2026-10-02T02:00:00Z")), now))
        assertEquals(SportsRefreshPolicy.STARTING_SOON_MS, SportsRefreshPolicy.baseDelayMs(null, now))
    }

    @Test
    fun `failures back off exponentially, capped at 10 minutes, never below 30s`() {
        val live = slate(event("e1", status = "LIVE"))
        assertEquals(30_000L, SportsRefreshPolicy.nextDelayMs(live, 0, 0, now))
        assertEquals(60_000L, SportsRefreshPolicy.nextDelayMs(live, 1, 0, now))
        assertEquals(120_000L, SportsRefreshPolicy.nextDelayMs(live, 2, 0, now))
        assertEquals(480_000L, SportsRefreshPolicy.nextDelayMs(live, 9, 0, now))
        val idle = slate(event("e1", start = "2026-10-05T12:00:00Z"))
        assertEquals(SportsRefreshPolicy.MAX_BACKOFF_MS, SportsRefreshPolicy.nextDelayMs(idle, 3, 0, now))
    }

    @Test
    fun `Retry-After is always honoured`() {
        val live = slate(event("e1", status = "LIVE"))
        assertEquals(300_000L, SportsRefreshPolicy.nextDelayMs(live, 1, 300_000, now))
    }
}
