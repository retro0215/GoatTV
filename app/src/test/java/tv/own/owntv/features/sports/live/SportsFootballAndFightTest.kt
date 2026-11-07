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

/** Weekly football slates (7-day extended window) + MMA/Boxing fight cards. */
class SportsFootballAndFightTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-10-02T19:10:00Z")!! // a Friday

    private val leagues = JSONArray(
        listOf(
            """{"id":"nfl","name":"NFL","order":1,"sport":"football","shortName":"NFL"}""",
            """{"id":"ncaaf","name":"College Football","order":2,"sport":"football","shortName":"NCAAF"}""",
            """{"id":"nhl","name":"NHL","order":7,"sport":"hockey","shortName":"NHL"}""",
            """{"id":"epl","name":"Premier League","order":40,"sport":"soccer","shortName":"EPL"}""",
            """{"id":"ufc","name":"UFC","order":30,"sport":"mma","shortName":"UFC","participantKind":"athlete"}""",
        ).map { JSONObject(it) },
    )

    private fun nfl(id: String, start: String, home: String, away: String) =
        event(id, league = "nfl", start = start, home = team("nfl.$home", home, home, home.uppercase()), away = team("nfl.$away", away, away, away.uppercase()))

    // Real Sunday 2026-10-04 slate shape: London 13:30Z, 1 PM ET 17:00Z, late window 20:05/20:25Z, SNF 00:20Z.
    private val inHome = listOf(nfl("evt_nfl_london", "2026-10-04T13:30:00Z", "wsh", "ind"), nfl("evt_nfl_1pm", "2026-10-04T17:00:00Z", "phi", "lar"))
    private val lateSunday = listOf(
        nfl("evt_nfl_405", "2026-10-04T20:05:00Z", "min", "mia"),
        nfl("evt_nfl_425", "2026-10-04T20:25:00Z", "sea", "lac"),
        nfl("evt_nfl_snf", "2026-10-05T00:20:00Z", "car", "det"),
    )

    private fun homeJson(events: List<JSONObject>, popular: List<String>) = JSONObject().apply {
        put("generatedAt", "2026-10-02T19:10:00.000Z"); put("cursor", "2026-10-02T19:09:30.000Z")
        put("leagues", leagues); put("popular", JSONArray(popular)); put("events", JSONArray(events))
    }.toString()

    private class Api(val home: String) : SportsApi {
        val bySport = HashMap<String, ArrayDeque<SportsApiResult<SportsEventsPayload>>>()
        val windows = HashMap<String, Pair<Long, Long>>()
        override suspend fun home() = SportsApiResult.Success(SportsApiParser.parseHome(home)!!)
        override suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload> = SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> {
            windows[sport] = fromMs to toMs
            return bySport[sport]?.removeFirstOrNull() ?: SportsApiResult.Success(SportsEventsPayload("c", emptyList()))
        }
    }

    private fun page(vararg events: JSONObject) = SportsApiResult.Success(SportsApiParser.parseEventsPage(SportsFixtures.page(events.toList()))!!)

    @Test
    fun `football 7-day window completes the weekly NFL row on a Friday - Popular stays backend-ordered`() = runBlocking {
        val api = Api(homeJson(inHome, popular = listOf("evt_nfl_1pm")))
        api.bySport["football"] = ArrayDeque(listOf(page(*(inHome + lateSunday).toTypedArray())))
        val store = SportsLiveStore(api) { now }
        store.refresh()
        val (from, to) = api.windows.getValue("football")
        assertEquals(now - SportsSlateLogic.HOME_PAST_MS, from)
        assertEquals(now + 7 * SportsSlateLogic.DAY_MS, to)
        assertEquals(now + 13 * SportsSlateLogic.DAY_MS, api.windows.getValue("soccer").second) // soccer unchanged
        assertEquals(now + 13 * SportsSlateLogic.DAY_MS, api.windows.getValue("mma").second)
        val slate = (store.state.value as SportsLiveState.Content).slate
        val sections = SportsSlateLogic.sections(slate, "", "Popular Events", mapOf("soccer" to "Soccer", "mma" to "MMA"))
        val nflRow = sections.single { it.key == "league:nfl" }
        assertEquals(5, nflRow.events.size)
        assertTrue("late-window games present", nflRow.events.map { it.id }.containsAll(listOf("evt_nfl_425", "evt_nfl_snf")))
        assertEquals("one card per evt id (overlap merged)", nflRow.events.map { it.id }.distinct().size, nflRow.events.size)
        assertEquals(listOf("evt_nfl_1pm"), sections.first { it.key == SportsSlateLogic.POPULAR_KEY }.events.map { it.id })
    }

    @Test
    fun `failed football fetch keeps the last good football slate and the rest of Sports`() = runBlocking {
        var clock = now
        val api = Api(homeJson(inHome, popular = emptyList()))
        api.bySport["football"] = ArrayDeque(listOf(page(*lateSunday.toTypedArray()), SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER)))
        val store = SportsLiveStore(api) { clock }
        store.refresh()
        clock += SportsLiveStore.HOME_RELOAD_MS
        store.refresh()
        val s = store.state.value as SportsLiveState.Content
        assertFalse(s.stale)
        assertTrue(s.slate.eventsById.keys.containsAll(listOf("evt_nfl_london", "evt_nfl_snf")))
    }

    @Test
    fun `daily sports are not extended`() {
        val sports = SportsSlateLogic.extendedSports(SportsApiParser.parseHome(homeJson(emptyList(), emptyList()))!!.leagues)
        assertEquals(setOf("football", "soccer", "mma"), sports.toSet())
        assertFalse("hockey" in SportsSlateLogic.EXTENDED_WINDOWS)
    }

    // --- fights ---------------------------------------------------------------------------

    private fun fighter(id: String, name: String, record: String?, flag: String?) = JSONObject().apply {
        put("id", id); put("name", name); put("shortName", JSONObject.NULL); put("record", record ?: JSONObject.NULL)
        put("country", "USA"); put("flagUrl", flag ?: JSONObject.NULL)
    }

    private fun bout(order: Int, start: String, a: JSONObject, b: JSONObject, status: String = "SCHEDULED", main: Boolean = false,
                     winner: Int? = null, method: String? = null, round: Int? = null, clock: String? = null) = JSONObject().apply {
        put("order", order); put("weightClass", "Welterweight"); put("scheduledRounds", if (main) 5 else 3); put("startTime", start)
        put("status", status); put("round", round ?: JSONObject.NULL); put("clock", clock ?: JSONObject.NULL); put("mainEvent", main)
        put("fighters", JSONArray(listOf(a, b))); put("winner", winner ?: JSONObject.NULL); put("method", method ?: JSONObject.NULL)
    }

    /** Shape returned by the backend (`fight.bouts`) — values from the real UFC Fight Night 2026-09-26 card. */
    private fun ufcCard(id: String = "evt_ufc_fn") = event(id, league = "ufc", status = "FINAL", start = "2026-09-26T21:00:00Z",
        home = null, away = null, title = "UFC Fight Night: Rosas Jr. vs. Barcelos").apply {
        put("fight", JSONObject().put("bouts", JSONArray(listOf(
            bout(1, "2026-09-26T21:00:00Z", fighter("ftr_00000000000000000001", "Vanessa Demopoulos", "11-9-0", null), fighter("ftr_00000000000000000002", "Yazmin Jauregui", "12-2-0", null),
                status = "FINAL", winner = 1, method = "KO/TKO", round = 1, clock = "1:02"),
            bout(2, "2026-09-26T21:00:00Z", fighter("ftr_00000000000000000003", "Robert Bryczek", "18-8-0", null), fighter("ftr_00000000000000000004", "Rodolfo Vieira", "12-5-0", null),
                status = "FINAL", winner = 1, method = null, round = 3, clock = "5:00"),
            bout(3, "2026-09-27T00:00:00Z", fighter("ftr_00000000000000000005", "Raul Rosas Jr.", "13-1-0", "https://a.espncdn.com/i/teamlogos/countries/500/mex.png"),
                fighter("ftr_00000000000000000006", "Raoni Barcelos", "22-6-0", null), status = "FINAL", main = true, winner = 0, method = "KO/TKO", round = 5, clock = "1:38"),
        ))))
    }

    @Test
    fun `fight card parses bouts, main event, winners and provider-stated methods only`() {
        val e = SportsFixtures.parsed(ufcCard())
        val fight = e.fight!!
        assertEquals(3, fight.bouts.size)
        assertEquals("Raul Rosas Jr.", fight.mainEvent!!.fighters[fight.mainEvent!!.winner!!].name)
        assertEquals(listOf("KO/TKO", null, "KO/TKO"), fight.bouts.map { it.method })
        assertNull("no team semantics", e.home)
        assertTrue(SportsEventPresentation.isFightCard(e, null))
        assertFalse(SportsEventPresentation.isFightCard(SportsFixtures.parsed(event("evt_nfl")), null))
        // Details: main event first, then groups by the provider's start times (latest first).
        assertEquals(listOf(3, 2, 1), SportsEventPresentation.boutsForDetails(fight).map { it.order })
        assertEquals(listOf(listOf(3), listOf(2, 1)), SportsEventPresentation.boutGroups(fight).map { g -> g.second.map { it.order } })
    }

    @Test
    fun `main event falls back to the last bout - malformed bouts dropped - fighter fallback initials`() {
        val raw = ufcCard().apply {
            val bouts = getJSONObject("fight").getJSONArray("bouts")
            bouts.getJSONObject(2).put("mainEvent", false)
            bouts.put(JSONObject().put("order", 9).put("fighters", JSONArray(listOf(fighter("ftr_x", "Solo", null, null))))) // one fighter → dropped
        }
        val fight = SportsFixtures.parsed(raw).fight!!
        assertEquals(3, fight.bouts.size)
        assertEquals(3, fight.mainEvent!!.order)
        assertEquals("NS", SportsEventPresentation.initials("Natalia Silva"))
        assertEquals("A", SportsEventPresentation.initials("Alatengheili"))
        assertNull("no flag → initials badge", fight.bouts[0].fighters[0].flagUrl)
    }

    @Test
    fun `MMA row groups fight cards after Soccer - Boxing row hidden without events - visual kinds`() {
        val slate = SportsSlateLogic.fromHome(
            SportsApiParser.parseHome(homeJson(listOf(ufcCard(), event("evt_nhl", league = "nhl"), event("evt_epl", league = "epl")), emptyList()))!!,
            now - 3 * 3_600_000L,
        )
        val sections = SportsSlateLogic.sections(slate, "", "P", mapOf("soccer" to "Soccer", "mma" to "MMA", "boxing" to "Boxing"))
        assertEquals(listOf("sport:soccer", "sport:mma", "league:nhl"), sections.map { it.key })
        assertEquals("MMA", sections.single { it.key == "sport:mma" }.title)
        assertFalse(sections.any { it.key == "sport:boxing" })
        val ufc = slate.leagues.single { it.id == "ufc" }
        assertEquals(SportsVisualKind.MMA, SportsEventPresentation.visualKind(ufc, "ufc"))
        assertEquals(SportsVisualKind.BOXING, SportsEventPresentation.visualKind(ufc.copy(sport = "boxing"), "x"))
    }

    @Test
    fun `fight details expose Close only while channels are off`() {
        assertEquals(listOf(SportsEventAction.CLOSE), SportsEventPresentation.detailActions(SportsFixtures.parsed(ufcCard())))
    }

    @Test
    fun `focus identity - rows and items keep stable keys across a football merge`() {
        val home = SportsSlateLogic.fromHome(SportsApiParser.parseHome(homeJson(inHome, emptyList()))!!, now)
        val merged = SportsSlateLogic.mergeEvents(home, (inHome + lateSunday).map { SportsFixtures.parsed(it) }, now)
        val before = SportsSlateLogic.sections(home, "", "P").single { it.key == "league:nfl" }
        val after = SportsSlateLogic.sections(merged, "", "P").single { it.key == "league:nfl" }
        assertTrue(after.events.map { it.id }.containsAll(before.events.map { it.id }))
    }
}
