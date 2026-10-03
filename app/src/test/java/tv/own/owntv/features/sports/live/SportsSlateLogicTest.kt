package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.SportsFixtures.event
import tv.own.owntv.features.sports.live.SportsFixtures.team

class SportsSlateLogicTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-10-02T12:00:00Z")!!
    private val title = "Popular Events"

    private fun home(events: List<org.json.JSONObject>, popular: List<String>) =
        SportsApiParser.parseHome(SportsFixtures.home(events, popular))!!

    private fun page(vararg events: org.json.JSONObject, cursor: String = "2026-10-02T12:05:00.000Z") =
        SportsApiParser.parseEventsPage(SportsFixtures.page(events.toList(), cursor))!!

    @Test
    fun `incremental update of an existing event replaces it, the cursor advances`() {
        val slate = SportsSlateLogic.fromHome(home(listOf(event("evt_a", status = "SCHEDULED")), listOf("evt_a")), now)
        val live = event("evt_a", status = "LIVE", updated = "2026-10-02T12:04:00.000Z", statusDetail = "Q1 12:00",
            home = team("nfl.phi", "Philadelphia Eagles", "Eagles", "PHI", "7"), away = team("nfl.lar", "Los Angeles Rams", "Rams", "LAR", "0"))
        val merged = SportsSlateLogic.mergeDelta(slate, page(live), now + 60_000)
        val a = merged.eventsById.getValue("evt_a")
        assertEquals(SportsEventStatus.LIVE, a.status)
        assertEquals("7", a.home!!.score)
        assertEquals("2026-10-02T12:05:00.000Z", merged.cursor)
        assertEquals(now + 60_000, merged.lastSuccessMs)
        assertEquals("home reload time is unchanged by a delta", now, merged.homeLoadedAtMs)
    }

    @Test
    fun `overlapping cursor - duplicates are de-duplicated and an older copy never overwrites a newer one`() {
        val newer = event("evt_a", status = "LIVE", updated = "2026-10-02T12:04:00.000Z")
        val slate = SportsSlateLogic.mergeDelta(SportsSlateLogic.fromHome(home(listOf(event("evt_a")), listOf()), now), page(newer), now)
        val stale = event("evt_a", status = "SCHEDULED", updated = "2026-10-02T11:00:00.000Z")
        val again = SportsSlateLogic.mergeDelta(slate, page(newer, stale, newer), now)
        assertEquals(1, again.eventsById.size)
        assertEquals(SportsEventStatus.LIVE, again.eventsById.getValue("evt_a").status)
    }

    @Test
    fun `a new event appearing in the delta is added`() {
        val slate = SportsSlateLogic.fromHome(home(listOf(event("evt_a")), listOf("evt_a")), now)
        val merged = SportsSlateLogic.mergeDelta(slate, page(event("evt_new", league = "nhl", start = "2026-10-02T23:00:00Z")), now)
        assertEquals(setOf("evt_a", "evt_new"), merged.eventsById.keys)
        assertTrue(SportsSlateLogic.sections(merged, "", title).any { s -> s.events.any { it.id == "evt_new" } })
    }

    @Test
    fun `events that aged out of the 18h window disappear, live ones are kept`() {
        val slate = SportsSlateLogic.fromHome(
            home(
                listOf(
                    event("evt_old", status = "FINAL", start = "2026-10-01T10:00:00Z"),
                    event("evt_old_live", status = "LIVE", start = "2026-10-01T10:00:00Z"),
                    event("evt_recent", status = "FINAL", start = "2026-10-01T20:00:00Z"),
                ),
                listOf(),
            ),
            now,
        )
        val merged = SportsSlateLogic.mergeDelta(slate, page(), now)
        assertEquals(setOf("evt_old_live", "evt_recent"), merged.eventsById.keys)
    }

    @Test
    fun `Popular Events follow backend popular order, leagues follow backend league order, empty leagues omitted`() {
        val slate = SportsSlateLogic.fromHome(
            home(
                listOf(
                    event("evt_nfl1", league = "nfl"),
                    event("evt_nhl1", league = "nhl", start = "2026-10-02T23:00:00Z"),
                    event("evt_cfb1", league = "ncaaf", start = "2026-10-03T16:00:00Z"),
                    event("evt_x", league = "xfl", start = "2026-10-03T18:00:00Z"),
                ),
                popular = listOf("evt_nhl1", "evt_missing", "evt_nfl1"),
            ),
            now,
        )
        val sections = SportsSlateLogic.sections(slate, "", title)
        assertEquals(listOf("popular", "league:nfl", "league:ncaaf", "league:nhl", "league:xfl"), sections.map { it.key })
        assertEquals(listOf("evt_nhl1", "evt_nfl1"), sections.first().events.map { it.id })
        assertEquals("Popular Events", sections.first().title)
        assertEquals("NCAAF", sections[2].title)
        assertNull(sections.first().league)
    }

    @Test
    fun `league rows - live first, then upcoming soonest first, then finished most recent first`() {
        val slate = SportsSlateLogic.fromHome(
            home(
                listOf(
                    event("evt_final_early", status = "FINAL", start = "2026-10-01T20:00:00Z"),
                    event("evt_final_late", status = "FINAL", start = "2026-10-02T01:00:00Z"),
                    event("evt_later", start = "2026-10-03T20:00:00Z"),
                    event("evt_sooner", start = "2026-10-02T20:00:00Z"),
                    event("evt_live", status = "LIVE", start = "2026-10-02T11:00:00Z"),
                    event("evt_pp", status = "POSTPONED", start = "2026-10-02T21:00:00Z"),
                ),
                listOf(),
            ),
            now,
        )
        val nfl = SportsSlateLogic.sections(slate, "", title).single { it.key == "league:nfl" }
        assertEquals(listOf("evt_live", "evt_sooner", "evt_later", "evt_pp", "evt_final_late", "evt_final_early"), nfl.events.map { it.id })
    }

    @Test
    fun `focus identity - section keys and event ids are stable across a refresh`() {
        val slate = SportsSlateLogic.fromHome(home(listOf(event("evt_a"), event("evt_b", league = "nhl")), listOf("evt_a")), now)
        val before = SportsSlateLogic.sections(slate, "", title)
        val after = SportsSlateLogic.sections(
            SportsSlateLogic.mergeDelta(slate, page(event("evt_a", status = "LIVE", updated = "2026-10-02T12:09:00.000Z")), now),
            "", title,
        )
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals(before.map { s -> s.events.map { it.id }.toSet() }, after.map { s -> s.events.map { it.id }.toSet() })
    }

    @Test
    fun `search across team name, short name, abbreviation and title`() {
        val eagles = SportsFixtures.parsed(event("evt_a"))
        val ufc = SportsFixtures.parsed(event("evt_u", league = "ufc", home = null, away = null, title = "UFC 332: Main Card"))
        assertTrue(SportsSlateLogic.matchesQuery(eagles, "eagles"))
        assertTrue(SportsSlateLogic.matchesQuery(eagles, "PHI"))
        assertTrue(SportsSlateLogic.matchesQuery(eagles, "los angeles rams"))
        assertTrue(SportsSlateLogic.matchesQuery(eagles, "  "))
        assertFalse(SportsSlateLogic.matchesQuery(eagles, "cowboys"))
        assertTrue(SportsSlateLogic.matchesQuery(ufc, "ufc 332"))
        val slate = SportsSlateLogic.fromHome(home(listOf(event("evt_a"), event("evt_n", league = "nhl", home = team("nhl.nyr", "New York Rangers", "Rangers", "NYR"), away = team("nhl.tb", "Tampa Bay Lightning", "Lightning", "TB"))), listOf("evt_a", "evt_n")), now)
        val filtered = SportsSlateLogic.sections(slate, "rangers", title)
        assertEquals(listOf("popular", "league:nhl"), filtered.map { it.key })
        assertEquals(listOf("evt_n"), filtered.first().events.map { it.id })
    }
}
