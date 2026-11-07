package tv.own.owntv.features.sports.live

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.SportsFixtures.broadcast
import tv.own.owntv.features.sports.live.SportsFixtures.event
import tv.own.owntv.features.sports.live.SportsFixtures.team

class SportsApiParserTest {

    @Test
    fun `parses the production home shape - leagues, popular order, events`() {
        val body = SportsFixtures.home(
            events = listOf(
                event("evt_a", status = "FINAL", home = team("nhl.nyr", "New York Rangers", "Rangers", "NYR", "5"), away = team("nhl.tb", "Tampa Bay Lightning", "Lightning", "TB", "1"), league = "nhl", statusDetail = "Final"),
                event("evt_b"),
            ),
            popular = listOf("evt_b", "evt_a"),
        )
        val home = SportsApiParser.parseHome(body)!!
        assertEquals("2026-10-02T12:00:00.000Z", home.cursor)
        assertEquals(listOf("nfl", "ncaaf", "nhl"), home.leagues.map { it.id })
        assertEquals(listOf("evt_b", "evt_a"), home.popular)
        val a = home.events.first { it.id == "evt_a" }
        assertEquals(SportsEventStatus.FINAL, a.status)
        assertEquals("5", a.home!!.score)
        assertEquals("1", a.away!!.score)
        assertEquals("Rangers", a.home.shortName)
        assertEquals("Final", a.statusDetail)
        assertTrue(a.showsScores)
        assertEquals(SportsApiParser.parseIsoUtcMs("2026-10-04T17:00:00Z"), home.events.first { it.id == "evt_b" }.startTimeMs)
    }

    @Test
    fun `scheduled scores are null and never shown, even if a placeholder 0 arrives`() {
        val sched = SportsFixtures.parsed(event("evt_s"))
        assertNull(sched.home!!.score)
        assertFalse(sched.showsScores)
        val placeholder = SportsFixtures.parsed(event("evt_p", home = team("x.h", "Home", "Home", "HOM", "0"), away = team("x.a", "Away", "Away", "AWY", "0")))
        assertEquals("0", placeholder.home!!.score)
        assertFalse("a SCHEDULED event never shows its scores", placeholder.showsScores)
    }

    @Test
    fun `live, delayed, postponed, canceled and unknown statuses`() {
        val live = SportsFixtures.parsed(event("evt_l", status = "LIVE", statusDetail = "7:16 - 1st", home = team("a.h", "H", "H", "H", "2"), away = team("a.a", "A", "A", "A", "1")))
        assertTrue(live.isLive && live.showsScores)
        assertEquals("7:16 - 1st", live.statusDetail)
        assertEquals(SportsEventStatus.DELAYED, SportsFixtures.parsed(event("evt_d", status = "DELAYED")).status)
        assertEquals(SportsEventStatus.POSTPONED, SportsFixtures.parsed(event("evt_pp", status = "POSTPONED")).status)
        assertEquals(SportsEventStatus.CANCELED, SportsFixtures.parsed(event("evt_c", status = "CANCELED")).status)
        assertFalse(SportsFixtures.parsed(event("evt_pp2", status = "POSTPONED", home = team("x.h", "H", "H", "H", "3"), away = team("x.a", "A", "A", "A", "1"))).showsScores)
        val unknown = SportsFixtures.parsed(event("evt_u", status = "SUSPENDED_BY_ALIENS"))
        assertEquals(SportsEventStatus.UNKNOWN, unknown.status)
        assertFalse(unknown.isLive)
    }

    @Test
    fun `null teams (title or athlete-card events) and empty broadcasts are renderable`() {
        val card = SportsFixtures.parsed(event("evt_ufc", league = "ufc", home = null, away = null, title = "UFC 332", broadcasts = emptyList()))
        assertNull(card.home); assertNull(card.away)
        assertEquals("UFC 332", card.title)
        assertFalse(card.isTeamEvent)
        assertTrue(card.broadcasts.isEmpty())
        assertNull(card.primaryBroadcast)
        // no participants and no title: nothing to render → dropped
        assertNull(SportsApiParser.parseEvent(event("evt_empty", home = null, away = null, title = null)))
    }

    @Test
    fun `primary broadcast prefers national TV and skips radio`() {
        val e = SportsFixtures.parsed(
            event("evt_b", broadcasts = listOf(broadcast("ERADM", type = "RADIO"), broadcast("ESPN+", type = "STREAMING"), broadcast("MSG", market = "HOME"), broadcast("ESPN"))),
        )
        assertEquals("ESPN", e.primaryBroadcast!!.name)
        val noTv = SportsFixtures.parsed(event("evt_c", broadcasts = listOf(broadcast("ERADM", type = "RADIO"), broadcast("ESPN+", type = "STREAMING"))))
        assertEquals("ESPN+", noTv.primaryBroadcast!!.name)
    }

    @Test
    fun `malformed events are dropped individually, numeric scores tolerated, bad logo URLs ignored`() {
        val events = JSONArray(
            listOf(
                JSONObject("""{"id":"evt_bad"}"""), // no leagueId/startTime
                event("evt_badtime", start = "yesterday"),
                event("evt_ok", status = "FINAL", home = team("a.h", "H", "H", "H", 3), away = team("a.a", "A", "A", "A", 1, logo = "javascript:alert(1)")),
            ),
        )
        val page = SportsApiParser.parseEventsPage(JSONObject().put("cursor", "2026-10-02T12:00:00Z").put("events", events).toString())!!
        assertEquals(listOf("evt_ok"), page.events.map { it.id })
        assertEquals("3", page.events.single().home!!.score)
        assertNull(page.events.single().away!!.logoUrl)
    }

    @Test
    fun `non-JSON and wrong-shape bodies parse to null`() {
        assertNull(SportsApiParser.parseHome("<html><body>504 Gateway Time-out</body></html>"))
        assertNull(SportsApiParser.parseHome("""{"error":"Sports data is temporarily unavailable."}"""))
        assertNull(SportsApiParser.parseEventsPage("""{"cursor":"x"}"""))
        assertNull(SportsApiParser.parseEventsPage(""))
        assertEquals("since must be an ISO-8601 timestamp.", SportsApiParser.parseError("""{"error":"since must be an ISO-8601 timestamp.","requestId":"r"}"""))
        assertNull(SportsApiParser.parseError("<html/>"))
    }

    @Test
    fun `channels are parsed in backend rank order (future contract)`() {
        val channels = JSONArray(
            listOf(
                JSONObject("""{"channelId":"sch_1","remoteId":"52001","epgChannelId":"ESPN.us","name":"US: ESPN","category":"USA | Sports","logoUrl":null,"networkKey":"espn","confidence":95,"reason":"network_exact"}"""),
                JSONObject("""{"channelId":"sch_2","remoteId":"52099","epgChannelId":"ESPN4K.us","name":"ESPN 4K UHD","category":"4K","logoUrl":null,"networkKey":"espn","confidence":95,"reason":"network_exact"}"""),
                JSONObject("""{"channelId":"sch_3","name":"no remote id"}"""),
            ),
        )
        val e = SportsFixtures.parsed(event("evt_ch", channels = channels))
        assertEquals(listOf("sch_1", "sch_2"), e.channels.map { it.channelId })
        assertEquals("52001", e.channels[0].remoteId)
        assertEquals("ESPN.us", e.channels[0].epgChannelId)
        assertEquals(95, e.channels[0].confidence)
        assertTrue("absent channels[] is simply empty", SportsFixtures.parsed(event("evt_none")).channels.isEmpty())
    }

    @Test
    fun `ISO-8601 instants with and without millis and offsets`() {
        assertEquals(0L, SportsApiParser.parseIsoUtcMs("1970-01-01T00:00:00Z"))
        assertEquals(1_790_982_000_000L, SportsApiParser.parseIsoUtcMs("2026-10-02T23:00:00Z"))
        assertEquals(1_790_982_000_123L, SportsApiParser.parseIsoUtcMs("2026-10-02T23:00:00.123Z"))
        assertEquals(1_790_982_000_000L, SportsApiParser.parseIsoUtcMs("2026-10-02T19:00:00-04:00"))
        assertEquals(1_790_982_000_000L, SportsApiParser.parseIsoUtcMs("2026-10-02T23:00Z"))
        assertEquals(951_782_400_000L, SportsApiParser.parseIsoUtcMs("2000-02-29T00:00:00Z")) // leap day
        assertNull(SportsApiParser.parseIsoUtcMs("2026-13-02T23:00:00Z"))
        assertNull(SportsApiParser.parseIsoUtcMs("tomorrow"))
        assertNull(SportsApiParser.parseIsoUtcMs(null))
        assertNotNull(SportsApiParser.parseIsoUtcMs("2026-10-02T12:10:51.416Z"))
    }
}
