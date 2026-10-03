package tv.own.owntv.features.sports.live

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.SportsFixtures.event
import tv.own.owntv.features.sports.live.SportsFixtures.team
import tv.own.owntv.features.sports.live.SportsEventPresentation.FinalExtra
import tv.own.owntv.features.sports.live.SportsEventPresentation.Status

/** Phase C1B: Soccer row, competition identity, soccer statuses, extended window, accents, actions. */
class SportsSoccerTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-10-02T12:00:00Z")!!

    /** Leagues exactly as production /sports/home sends them after the first soccer batch. */
    private val leagues = JSONArray(
        listOf(
            """{"id":"nfl","name":"NFL","order":1,"sport":"football","shortName":"NFL","participantKind":"team","competitionType":null,"region":null,"gender":null}""",
            """{"id":"ncaaf","name":"College Football","order":2,"sport":"football","shortName":"NCAAF","participantKind":"team"}""",
            """{"id":"nba","name":"NBA","order":3,"sport":"basketball","shortName":"NBA","participantKind":"team"}""",
            """{"id":"mlb","name":"MLB","order":6,"sport":"baseball","shortName":"MLB","participantKind":"team"}""",
            """{"id":"nhl","name":"NHL","order":7,"sport":"hockey","shortName":"NHL","participantKind":"team"}""",
            """{"id":"epl","name":"Premier League","order":40,"sport":"soccer","shortName":"EPL","participantKind":"team","competitionType":"league","region":"england","gender":"men"}""",
            """{"id":"ucl","name":"UEFA Champions League","order":41,"sport":"soccer","shortName":"UCL","participantKind":"team","competitionType":"continental","region":"europe","gender":"men"}""",
            """{"id":"liga-mx","name":"Liga MX","order":43,"sport":"soccer","shortName":"Liga MX","participantKind":"team","competitionType":"league","region":"mexico","gender":"men"}""",
            """{"id":"mls","name":"MLS","order":44,"sport":"soccer","shortName":"MLS","participantKind":"team","competitionType":"league","region":"usa","gender":"men"}""",
        ).map { JSONObject(it) },
    )

    private fun soccerEvent(id: String, league: String, start: String, status: String = "SCHEDULED", detail: String? = null, title: String? = null,
                            home: JSONObject = team("soccer.arsenal", "Arsenal", "Arsenal", "ARS"),
                            away: JSONObject = team("soccer.leeds-united", "Leeds United", "Leeds", "LEE")) =
        event(id, league = league, status = status, start = start, statusDetail = detail, title = title, home = home, away = away,
            broadcasts = listOf(SportsFixtures.broadcast("Paramount+", type = "STREAMING")))

    private fun homeJson(events: List<JSONObject>, popular: List<String> = emptyList(), leagueArr: JSONArray = leagues) = JSONObject().apply {
        put("generatedAt", "2026-10-02T12:00:30.000Z"); put("cursor", "2026-10-02T12:00:00.000Z")
        put("leagues", leagueArr); put("popular", JSONArray(popular)); put("events", JSONArray(events))
    }.toString()

    private fun slateOf(events: List<JSONObject>, popular: List<String> = emptyList()) =
        SportsSlateLogic.fromHome(SportsApiParser.parseHome(homeJson(events, popular))!!, now)

    // --- metadata ---------------------------------------------------------------------------

    @Test
    fun `league metadata - competitionType, region and gender parsed, null for US leagues`() {
        val parsed = SportsApiParser.parseHome(homeJson(emptyList()))!!.leagues.associateBy { it.id }
        assertEquals(Triple("continental", "europe", "men"), parsed.getValue("ucl").let { Triple(it.competitionType, it.region, it.gender) })
        assertEquals(Triple(null, null, null), parsed.getValue("nfl").let { Triple(it.competitionType, it.region, it.gender) })
        assertEquals("soccer", parsed.getValue("liga-mx").sport)
    }

    // --- sections -----------------------------------------------------------------------------

    @Test
    fun `one SOCCER row combines every enabled competition, placed after NBA and before NHL`() {
        val slate = slateOf(
            listOf(
                event("evt_nfl", league = "nfl"),
                event("evt_nba", league = "nba"),
                event("evt_nhl", league = "nhl"),
                event("evt_mlb", league = "mlb"),
                soccerEvent("evt_epl", "epl", "2026-10-10T11:30:00Z"),
                soccerEvent("evt_ucl", "ucl", "2026-10-13T16:45:00Z"),
                soccerEvent("evt_mls", "mls", "2026-10-07T00:30:00Z"),
                soccerEvent("evt_lmx", "liga-mx", "2026-09-28T03:10:00Z", status = "FINAL", detail = "FT"),
            ),
        )
        val sections = SportsSlateLogic.sections(slate, "", "Popular Events", mapOf("soccer" to "Soccer"))
        assertEquals(listOf("league:nfl", "league:nba", "sport:soccer", "league:nhl", "league:mlb"), sections.map { it.key })
        val soccer = sections.single { it.key == "sport:soccer" }
        assertEquals("Soccer", soccer.title)
        assertNull("mixed row → per-card competition label", soccer.league)
        assertEquals(listOf("epl", "ucl", "liga-mx", "mls"), soccer.competitions.map { it.id })
        // upcoming soonest first, finished last; every event keeps its own competition
        assertEquals(listOf("evt_mls", "evt_epl", "evt_ucl", "evt_lmx"), soccer.events.map { it.id })
        assertEquals(listOf("mls", "epl", "ucl", "liga-mx"), soccer.events.map { it.leagueId })
        assertFalse(sections.any { it.key.startsWith("league:") && it.key.removePrefix("league:") in setOf("epl", "ucl", "mls", "liga-mx") })
    }

    @Test
    fun `a newly enabled competition joins the Soccer row without an app change`() {
        val arr = JSONArray(leagues.toString()).put(JSONObject("""{"id":"laliga","name":"LALIGA","order":42,"sport":"soccer","shortName":"LALIGA","participantKind":"team"}"""))
        val slate = SportsSlateLogic.fromHome(
            SportsApiParser.parseHome(homeJson(listOf(soccerEvent("evt_l", "laliga", "2026-10-04T14:00:00Z")), leagueArr = arr))!!, now,
        )
        val soccer = SportsSlateLogic.sections(slate, "", "P", mapOf("soccer" to "Soccer")).single()
        assertEquals("sport:soccer", soccer.key)
        assertEquals(listOf("laliga"), soccer.competitions.map { it.id })
    }

    @Test
    fun `no soccer events - no Soccer row`() {
        val sections = SportsSlateLogic.sections(slateOf(listOf(event("evt_nfl"))), "", "P", mapOf("soccer" to "Soccer"))
        assertEquals(listOf("league:nfl"), sections.map { it.key })
    }

    @Test
    fun `Popular can contain soccer, in backend popular order`() {
        val slate = slateOf(
            listOf(event("evt_nfl"), soccerEvent("evt_ucl", "ucl", "2026-10-02T19:00:00Z", status = "LIVE", detail = "60'")),
            popular = listOf("evt_ucl", "evt_nfl"),
        )
        val popular = SportsSlateLogic.sections(slate, "", "P").first()
        assertEquals(listOf("evt_ucl", "evt_nfl"), popular.events.map { it.id })
    }

    @Test
    fun `search matches competition name and short name as well as teams`() {
        val slate = slateOf(listOf(soccerEvent("evt_ucl", "ucl", "2026-10-13T16:45:00Z"), event("evt_nfl")))
        val ucl = slate.leagues.single { it.id == "ucl" }
        val e = slate.eventsById.getValue("evt_ucl")
        assertTrue(SportsSlateLogic.matchesQuery(e, "champions", ucl))
        assertTrue(SportsSlateLogic.matchesQuery(e, "ucl arsenal", ucl))
        assertFalse(SportsSlateLogic.matchesQuery(e, "premier", ucl))
        val rows = SportsSlateLogic.sections(slate, "Champions League", "P", mapOf("soccer" to "Soccer"))
        assertEquals(listOf("evt_ucl"), rows.flatMap { it.events }.map { it.id })
    }

    // --- presentation ---------------------------------------------------------------------------

    private fun parsed(o: JSONObject) = SportsFixtures.parsed(o)

    @Test
    fun `soccer statuses - minute, halftime, AET, penalties, postponed, canceled`() {
        val p = SportsEventPresentation
        assertEquals(Status.Live("60'", false), p.status(parsed(soccerEvent("a", "epl", "2026-10-02T11:00:00Z", "LIVE", "60'"))))
        assertEquals(Status.Live(null, true), p.status(parsed(soccerEvent("b", "epl", "2026-10-02T11:00:00Z", "LIVE", "HT"))))
        assertEquals(Status.Final(FinalExtra.ExtraTime), p.status(parsed(soccerEvent("c", "ucl", "2026-10-01T19:00:00Z", "FINAL", "AET"))))
        assertEquals(Status.Final(FinalExtra.Penalties), p.status(parsed(soccerEvent("d", "ucl", "2026-10-01T19:00:00Z", "FINAL", "FT-Pens"))))
        assertEquals(Status.Final(null), p.status(parsed(soccerEvent("e", "mls", "2026-10-02T01:30:00Z", "FINAL", "FT"))))
        assertEquals(Status.Postponed, p.status(parsed(soccerEvent("f", "epl", "2026-10-04T14:00:00Z", "POSTPONED", "Postponed"))))
        assertEquals(Status.Canceled, p.status(parsed(soccerEvent("g", "epl", "2026-10-04T14:00:00Z", "CANCELED"))))
        assertEquals(Status.Scheduled, p.status(parsed(soccerEvent("h", "epl", "2026-10-10T11:30:00Z"))))
        // US overtime keeps the backend text; US halftime also reads as halftime
        assertEquals(Status.Final(FinalExtra.Raw("OT")), p.status(parsed(event("i", status = "FINAL", statusDetail = "Final/OT"))))
        assertEquals(Status.Live(null, true), p.status(parsed(event("j", status = "LIVE", statusDetail = "Halftime"))))
    }

    @Test
    fun `note shows only backend title text - leg or shootout result - never a computed aggregate`() {
        val leg = parsed(soccerEvent("a", "ucl", "2026-10-13T19:00:00Z", title = "1st Leg"))
        assertEquals("1st Leg", SportsEventPresentation.note(leg))
        val pens = parsed(soccerEvent("b", "ucl", "2026-10-01T19:00:00Z", "FINAL", "FT-Pens", title = "Toluca win 6-5 on penalties"))
        assertEquals("Toluca win 6-5 on penalties", SportsEventPresentation.note(pens))
        assertNull(SportsEventPresentation.note(parsed(soccerEvent("c", "epl", "2026-10-10T11:30:00Z"))))
        // title-only events use the title as the headline, not as a note
        assertNull(SportsEventPresentation.note(parsed(event("d", league = "ufc", home = null, away = null, title = "UFC 310"))))
    }

    @Test
    fun `competition label and visual kind come from league metadata`() {
        val ls = SportsApiParser.parseHome(homeJson(emptyList()))!!.leagues.associateBy { it.id }
        assertEquals("EPL", SportsEventPresentation.competitionLabel(ls["epl"]))
        assertEquals("Liga MX", SportsEventPresentation.competitionLabel(ls["liga-mx"]))
        assertNull(SportsEventPresentation.competitionLabel(null))
        assertEquals(SportsVisualKind.SOCCER, SportsEventPresentation.visualKind(ls["ucl"], "ucl"))
        assertEquals(SportsVisualKind.COLLEGE_FOOTBALL, SportsEventPresentation.visualKind(ls["ncaaf"], "ncaaf"))
        assertEquals(SportsVisualKind.FOOTBALL, SportsEventPresentation.visualKind(ls["nfl"], "nfl"))
        assertEquals(SportsVisualKind.HOCKEY, SportsEventPresentation.visualKind(ls["nhl"], "nhl"))
        assertEquals(SportsVisualKind.BASEBALL, SportsEventPresentation.visualKind(ls["mlb"], "mlb"))
        assertEquals(SportsVisualKind.BASKETBALL, SportsEventPresentation.visualKind(ls["nba"], "nba"))
        assertEquals(SportsVisualKind.GENERIC, SportsEventPresentation.visualKind(null, "xfl"))
    }

    @Test
    fun `details actions - Close only while channels are off, even if a payload carried channels`() {
        val channels = JSONArray(listOf(JSONObject("""{"channelId":"sch_000000000000000000000001","remoteId":"1","name":"ESPN","confidence":95,"reason":"network_exact"}""")))
        val e = parsed(event("a", channels = channels))
        assertEquals(1, e.channels.size)
        assertEquals(listOf(SportsEventAction.CLOSE), SportsEventPresentation.detailActions(e, channelsEnabled = false))
        assertEquals(listOf(SportsEventAction.CLOSE), SportsEventPresentation.detailActions(parsed(event("b")), channelsEnabled = true))
    }

    // --- extended window (store) ----------------------------------------------------------------

    private class SoccerApi(val homeBody: String) : SportsApi {
        val sportResults = ArrayDeque<SportsApiResult<SportsEventsPayload>>()
        val calls = mutableListOf<String>()
        var lastWindow: Pair<Long, Long>? = null
        override suspend fun home(): SportsApiResult<SportsHomePayload> { calls += "home"; return SportsApiResult.Success(SportsApiParser.parseHome(homeBody)!!) }
        override suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload> { calls += "since"; return SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK) }
        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> {
            calls += "sport:$sport"
            // Only soccer is scripted here; other extended sports (football) answer with an empty page.
            if (sport != "soccer") return SportsApiResult.Success(SportsEventsPayload("c", emptyList()))
            lastWindow = fromMs to toMs
            return sportResults.removeFirst()
        }
    }

    private fun page(vararg events: JSONObject) =
        SportsApiResult.Success(SportsApiParser.parseEventsPage(SportsFixtures.page(events.toList()))!!)

    @Test
    fun `home reload also loads the soccer window - one request per sport, within the 14-day API range`() = runBlocking {
        var clock = now
        val api = SoccerApi(homeJson(listOf(event("evt_nfl"), soccerEvent("evt_mls_final", "mls", "2026-10-02T01:30:00Z", "FINAL", "FT"))))
        api.sportResults += page(
            soccerEvent("evt_mls_final", "mls", "2026-10-02T01:30:00Z", "FINAL", "FT"),
            soccerEvent("evt_epl", "epl", "2026-10-10T11:30:00Z"),
            soccerEvent("evt_ucl", "ucl", "2026-10-13T16:45:00Z"),
        )
        val store = SportsLiveStore(api) { clock }
        store.refresh()
        assertEquals(listOf("home", "sport:football", "sport:soccer"), api.calls)
        val (from, to) = api.lastWindow!!
        assertEquals(now - SportsSlateLogic.HOME_PAST_MS, from)
        assertTrue(to - from <= 14 * 24 * 3_600_000L + SportsSlateLogic.HOME_PAST_MS)
        val slate = (store.state.value as SportsLiveState.Content).slate
        assertEquals(setOf("evt_nfl", "evt_mls_final", "evt_epl", "evt_ucl"), slate.eventsById.keys)
    }

    @Test
    fun `a failed soccer fetch keeps the last-known-good soccer events and never blanks the US rows`() = runBlocking {
        var clock = now
        val api = SoccerApi(homeJson(listOf(event("evt_nfl"))))
        api.sportResults += page(soccerEvent("evt_epl", "epl", "2026-10-10T11:30:00Z"))
        api.sportResults += SportsApiResult.Unavailable(SportsApiResult.Reason.NOT_JSON)
        val store = SportsLiveStore(api) { clock }
        store.refresh()
        clock += SportsLiveStore.HOME_RELOAD_MS
        store.refresh()
        val s = store.state.value as SportsLiveState.Content
        assertFalse(s.stale)
        assertEquals(setOf("evt_nfl", "evt_epl"), s.slate.eventsById.keys)
    }

    @Test
    fun `no soccer competition enabled - no soccer request`() = runBlocking {
        val usOnly = JSONArray(listOf(JSONObject("""{"id":"nfl","name":"NFL","order":1,"sport":"football","shortName":"NFL"}""")))
        val api = SoccerApi(homeJson(listOf(event("evt_nfl")), leagueArr = usOnly))
        SportsLiveStore(api) { now }.refresh()
        assertEquals(listOf("home", "sport:football"), api.calls)
        assertFalse("no soccer request", "sport:soccer" in api.calls)
    }

    @Test
    fun `sport window URL - encoded sport and UTC instants`() {
        assertEquals(
            "/sports/events?sport=soccer&from=2026-10-01T18%3A00%3A00Z&to=2026-10-15T12%3A00%3A00Z",
            SportsApiClient.eventsForSportPath("soccer", now - SportsSlateLogic.HOME_PAST_MS, now + SportsSlateLogic.EXTENDED_FUTURE_MS),
        )
    }

    // --- team accents ---------------------------------------------------------------------------

    @Test
    fun `team accent - dominant saturated hue, null for monochrome or transparent marks`() {
        val red = 0xFFD50000.toInt(); val white = 0xFFFFFFFF.toInt(); val black = 0xFF000000.toInt(); val clear = 0x00FF0000
        val arsenalLike = IntArray(400) { i -> if (i % 3 == 0) white else red }
        val accent = SportsTeamAccent.dominantAccent(arsenalLike)!!
        assertTrue((accent shr 16 and 0xFF) > 180 && (accent shr 8 and 0xFF) < 40)
        assertNull(SportsTeamAccent.dominantAccent(IntArray(400) { if (it % 2 == 0) white else black }))
        assertNull(SportsTeamAccent.dominantAccent(IntArray(400) { clear }))
        assertNull(SportsTeamAccent.dominantAccent(IntArray(400) { 0xFF808080.toInt() }))
    }
}
