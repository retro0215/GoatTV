package tv.own.owntv.features.sports.live

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.R

/**
 * GoatTV Game Center contract → Android domain → preview, on sanitized REAL production responses
 * (`GET /sports/events/{id}/game-center`, captured 2026-10-03; URL fields removed) plus small synthetic
 * edge cases. Android never sees provider data: GoatTV ids/keys/labels only.
 */
class GameCenterApiTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("sports/gamecenter/$name.json")) { name }.readText()

    private fun eventId(body: String) = JSONObject(body).getJSONObject("event").getString("id")

    private fun map(name: String): GameCenterDetail {
        val body = resource(name)
        return GameCenterApi.toDomain(requireNotNull(GameCenterApi.parse(body, eventId(body))))
    }

    private fun event(name: String): SportsEvent = requireNotNull(SportsApiParser.parseEvent(JSONObject(resource(name)).getJSONObject("event")))

    private val ncaaf = SportsLeague("ncaaf", "College Football", "NCAAF", 2, "football", "team")
    private val wnba = SportsLeague("wnba", "WNBA", "WNBA", 4, "basketball", "team")
    private val nhl = SportsLeague("nhl", "NHL", "NHL", 7, "hockey", "team")

    // ---------------------------------------------------------------- real captures

    @Test
    fun `AVAILABLE live NCAAF - possession, down and distance, last play, stats, one game leader per category`() {
        val d = map("ncaaf_live_possession")
        assertEquals(GameCenterAvailability.AVAILABLE, d.availability)
        assertEquals(30_000L, d.refreshAfterMs)
        assertFalse(d.stale)
        val live = requireNotNull(d.live)
        assertNotNull(live.possession)
        assertTrue(live.downDistance!!.isNotBlank())
        assertTrue(live.lastPlay!!.isNotBlank())
        assertNull("no baseball fields for football", live.outs ?: live.balls ?: live.onFirst)
        assertNull("clock comes from the event's own status line", live.clock ?: live.periodLabel)
        assertEquals("totalYards", d.teamStats.first().key)
        assertEquals(listOf("passingYards", "rushingYards", "receivingYards", "sacks", "totalTackles"), d.leaders.map { it.key })
        assertTrue(d.leaders.all { it.athleteName.isNotBlank() && it.side != null && it.statParts.isNotEmpty() })
        assertTrue(d.leaders.all { it.headshotUrl == null }) // production: no GoatTV image base configured
        assertTrue(d.playerGroups.isNotEmpty())
        assertTrue(d.scoring.isNotEmpty())
    }

    @Test
    fun `AVAILABLE live NCAAF - the preview shows the useful subset and nothing invented`() {
        val e = event("ncaaf_live_possession")
        val p = GameCenterPresentation.preview(e, ncaaf, GameCenterDetailState.Ready(e.id, map("ncaaf_live_possession")))
        assertEquals(GameCenterPhase.LIVE, p.phase)
        assertEquals(3, p.teamStats.size)
        assertTrue(p.teamStats.all { !it.away.isNullOrBlank() && !it.home.isNullOrBlank() })
        assertEquals(3, p.leaders.size)
        assertNotNull(p.possession)
        assertNotNull(p.downDistance)
        assertNotNull(p.lastPlay)
        assertNull(p.situation) // baseball-only block
        val shown = (p.teamStats.flatMap { listOf(it.label, it.away, it.home) } + p.leaders.flatMap { listOf(it.athleteName) + it.statParts } + p.clockParts)
        assertTrue(shown.none { it == null || it == "null" || it == "--" || it == "0:00" })
    }

    @Test
    fun `AVAILABLE live NHL - hockey context only, no football or baseball fields`() {
        val d = map("nhl_live")
        val live = requireNotNull(d.live)
        assertNotNull(live.lastPlay)
        assertNull(live.possession)
        assertNull(live.downDistance)
        assertNull(live.balls)
        val e = event("nhl_live")
        val p = GameCenterPresentation.preview(e, nhl, GameCenterDetailState.Ready(e.id, d))
        assertEquals(listOf("shots", "powerPlay", "faceoffPct"), p.teamStats.map { it.key })
        assertEquals(listOf("goals", "assists", "points"), p.leaders.map { it.key })
        assertNull(p.possession)
    }

    @Test
    fun `AVAILABLE live WNBA - basketball glance stats by GoatTV key, missing pieces collapse`() {
        val e = event("wnba_live")
        val p = GameCenterPresentation.preview(e, wnba, GameCenterDetailState.Ready(e.id, map("wnba_live")))
        assertEquals(listOf("fieldGoalPct", "rebounds", "assists"), p.teamStats.map { it.key })
        assertEquals(listOf("points", "assists", "rebounds"), p.leaders.map { it.key })
        assertNull(p.downDistance)
        assertNull(p.possession)
    }

    @Test
    fun `AVAILABLE final NHL - final stats and leaders, no live situation, long refresh`() {
        val d = map("nhl_final")
        assertEquals(600_000L, d.refreshAfterMs)
        assertNull(d.live)
        val e = event("nhl_final")
        val p = GameCenterPresentation.preview(e, nhl, GameCenterDetailState.Ready(e.id, d))
        assertEquals(GameCenterPhase.FINAL, p.phase)
        assertTrue(p.hasStats && p.hasLeaders)
        assertNull(p.lastPlay)
        assertTrue(p.clockParts.isEmpty())
    }

    @Test
    fun `pre-game snapshot - SEASON leaders are never shown as this game's leaders`() {
        val d = map("nhl_pregame_season")
        assertEquals(GameCenterAvailability.AVAILABLE, d.availability)
        assertTrue(d.leaders.isEmpty())
        assertTrue(d.teamStats.isEmpty())
    }

    @Test
    fun `PENDING - no sections, 10 s refresh hint`() {
        val d = map("nfl_pending")
        assertEquals(GameCenterAvailability.PENDING, d.availability)
        assertEquals(10_000L, d.refreshAfterMs)
        assertTrue(d.teamStats.isEmpty() && d.leaders.isEmpty() && d.live == null)
    }

    @Test
    fun `UNAVAILABLE (exact production off-state shape) - nothing to show, nothing to refresh`() {
        val body = JSONObject(resource("nfl_pending")).apply {
            put("gameCenter", JSONObject("""{"availability":"UNAVAILABLE","phase":null,"updatedAt":null,"stale":false}"""))
            put("refreshAfterSeconds", JSONObject.NULL)
        }.toString()
        val d = GameCenterApi.toDomain(GameCenterApi.parse(body, eventId(body))!!)
        assertEquals(GameCenterAvailability.UNAVAILABLE, d.availability)
        assertNull(d.refreshAfterMs)
    }

    @Test
    fun `stale AVAILABLE - data kept and flagged`() {
        val body = JSONObject(resource("wnba_live")).apply { getJSONObject("gameCenter").put("stale", true) }.toString()
        val d = GameCenterApi.toDomain(GameCenterApi.parse(body, eventId(body))!!)
        assertTrue(d.stale)
        assertTrue(d.teamStats.isNotEmpty())
        val e = event("wnba_live")
        assertTrue(GameCenterPresentation.preview(e, wnba, GameCenterDetailState.Ready(e.id, d)).stale)
    }

    // ---------------------------------------------------------------- contract edges

    private fun synthetic(gameCenter: String, refresh: String = "30", id: String = "evt_00000000000000000001") =
        """{"event":{"id":"$id","leagueId":"nfl"},"gameCenter":$gameCenter,"refreshAfterSeconds":$refresh}"""

    private fun mapSynthetic(gc: String, refresh: String = "30") = GameCenterApi.toDomain(GameCenterApi.parse(synthetic(gc, refresh), "evt_00000000000000000001")!!)

    @Test
    fun `refreshAfterSeconds - honoured, clamped, absent means no refresh`() {
        assertEquals(30_000L, mapSynthetic("""{"availability":"AVAILABLE"}""").refreshAfterMs)
        assertEquals(5_000L, mapSynthetic("""{"availability":"PENDING"}""", "1").refreshAfterMs)
        assertEquals(3_600_000L, mapSynthetic("""{"availability":"AVAILABLE"}""", "999999").refreshAfterMs)
        assertNull(mapSynthetic("""{"availability":"AVAILABLE"}""", "null").refreshAfterMs)
    }

    @Test
    fun `null fields, missing leaders, missing stats, missing situation - empty, never invented`() {
        val d = mapSynthetic(
            """{"availability":"AVAILABLE","phase":"LIVE","stale":false,
               "situation":{"kind":"football","possession":null,"downDistance":null,"isRedZone":null,"inningHalf":null,
                            "balls":null,"strikes":null,"outs":null,"onFirst":null,"onSecond":null,"onThird":null,"lastPlay":null},
               "teamStats":[{"key":"totalYards","label":"Total Yards","group":null,"home":null,"away":null}],
               "leaders":null,"boxScore":null,"scoring":[]}""",
        )
        assertNull("an all-null situation is no situation", d.live)
        assertTrue(d.leaders.isEmpty())
        assertTrue(d.playerGroups.isEmpty())
        val e = GameCenterTestData.game(id = "evt_00000000000000000001")
        val p = GameCenterPresentation.preview(e, GameCenterTestData.NFL, GameCenterDetailState.Ready(e.id, d))
        assertFalse(p.hasStats)
        assertFalse(p.hasLeaders)
        assertTrue(p.showsInfoPanel)
        assertNull(p.lastPlay)
        // Missing sections entirely.
        assertTrue(mapSynthetic("""{"availability":"AVAILABLE"}""").let { it.teamStats.isEmpty() && it.leaders.isEmpty() && it.live == null })
    }

    @Test
    fun `baseball - count and bases only when stated, errors row from the line score`() {
        val d = mapSynthetic(
            """{"availability":"AVAILABLE","situation":{"kind":"baseball","balls":2,"strikes":1,"outs":null,"onFirst":true,"onSecond":null,"onThird":false,"lastPlay":null,"possession":"home","downDistance":"x"},
               "teamStats":[{"key":"hits","label":"Hits","group":"batting","home":"7","away":"5"}],
               "lineScore":{"labels":["1"],"home":["0"],"away":["1"],"extras":[{"key":"hits","label":"H","home":"7","away":"5"},{"key":"errors","label":"E","home":"0","away":"1"}]}}""",
        )
        val live = d.live!!
        assertEquals(listOf(2, 1, null), listOf(live.balls, live.strikes, live.outs))
        assertEquals(listOf(true, null, false), listOf(live.onFirst, live.onSecond, live.onThird))
        assertNull("football fields ignored for baseball", live.possession ?: live.downDistance)
        assertEquals(listOf("hits", "errors"), d.teamStats.map { it.key }) // hits not duplicated from extras
        assertEquals(R.string.sports_gc_stat_errors, d.teamStats.last().labelRes)
    }

    @Test
    fun `leader per category - the larger headline value wins, one side alone is used as is`() {
        fun cat(away: String?, home: String?) = """{"key":"k","label":"K","away":${away?.let { """{"player":{"id":"ply_a","name":"Away P","headshotUrl":null},"value":"$it","summary":"s"}""" } ?: "null"},
            "home":${home?.let { """{"player":{"id":"ply_h","name":"Home P","headshotUrl":null},"value":"$it","summary":null}""" } ?: "null"}}"""
        fun leader(away: String?, home: String?) = mapSynthetic("""{"availability":"AVAILABLE","leaders":{"scope":"GAME","categories":[${cat(away, home)}]}}""").leaders.single()
        assertEquals(GameCenterSide.AWAY, leader("299", "268").side)
        assertEquals(GameCenterSide.HOME, leader("1-3", "2-4").side)
        assertEquals(GameCenterSide.AWAY, leader("4.0 IP", "3.1 IP").side)
        assertEquals(GameCenterSide.HOME, leader(null, "7").side)
        assertEquals(listOf("7"), leader(null, "7").statParts) // null summary collapses, value stays
        assertEquals(listOf("299", "s"), leader("299", "268").statParts)
    }

    @Test
    fun `headshots - only GoatTV https images, provider or plain-http URLs are dropped`() {
        assertEquals("https://img.goattv.example/players/ply_1.png", GameCenterApi.safeHeadshot("https://img.goattv.example/players/ply_1.png"))
        assertNull(GameCenterApi.safeHeadshot("https://a.espncdn.com/i/headshots/nfl/players/full/1.png"))
        assertNull(GameCenterApi.safeHeadshot("http://img.goattv.example/players/ply_1.png"))
        assertNull(GameCenterApi.safeHeadshot(null))
    }

    @Test
    fun `malformed answers - another event's body, non-JSON, unknown availability`() {
        assertNull(GameCenterApi.parse(synthetic("""{"availability":"AVAILABLE"}"""), "evt_ffffffffffffffffffff"))
        assertNull(GameCenterApi.parse("<html>504</html>", "evt_00000000000000000001"))
        assertNull(GameCenterApi.parse("""{"event":{"id":"evt_00000000000000000001"}}""", "evt_00000000000000000001"))
        assertEquals(GameCenterAvailability.UNAVAILABLE, mapSynthetic("""{"availability":"SOMETHING_NEW"}""").availability)
    }

    // ---------------------------------------------------------------- production source

    @Test
    fun `production source - non-GoatTV ids never reach the network, results map to fetch outcomes`() = runBlocking {
        val asked = mutableListOf<String>()
        fun source(result: SportsApiResult<GameCenterResponseDto>) = GameCenterApiSource { id -> asked += id; result }
        val ok = GameCenterApi.parse(resource("nfl_pending"), eventId(resource("nfl_pending")))!!
        // Debug fixture ids and anything malformed: UNAVAILABLE without I/O.
        val fixture = source(SportsApiResult.Success(ok)).fetch(SportsGameCenterFixtures.FIXTURE_ID_PREFIX + "nfl_live")
        assertEquals(GameCenterAvailability.UNAVAILABLE, (fixture as GameCenterFetch.Loaded).detail.availability)
        assertTrue(asked.isEmpty())
        val id = ok.eventId
        assertEquals(GameCenterAvailability.PENDING, (source(SportsApiResult.Success(ok)).fetch(id) as GameCenterFetch.Loaded).detail.availability)
        assertEquals(GameCenterFetch.Failed(), source(SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)).fetch(id))
        assertEquals(GameCenterFetch.Failed(120_000L), source(SportsApiResult.RateLimited(120)).fetch(id))
        assertEquals(GameCenterAvailability.UNAVAILABLE, (source(SportsApiResult.Rejected(404, "Event not found.")).fetch(id) as GameCenterFetch.Loaded).detail.availability)
    }
}
