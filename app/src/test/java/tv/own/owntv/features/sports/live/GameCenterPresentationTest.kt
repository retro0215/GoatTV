package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.GameCenterTestData.EPL
import tv.own.owntv.features.sports.live.GameCenterTestData.MLB
import tv.own.owntv.features.sports.live.GameCenterTestData.NBA
import tv.own.owntv.features.sports.live.GameCenterTestData.NCAAF
import tv.own.owntv.features.sports.live.GameCenterTestData.NFL
import tv.own.owntv.features.sports.live.GameCenterTestData.NHL
import tv.own.owntv.features.sports.live.GameCenterTestData.UFC
import tv.own.owntv.features.sports.live.GameCenterTestData.BOXING
import tv.own.owntv.features.sports.live.GameCenterTestData.detail
import tv.own.owntv.features.sports.live.GameCenterTestData.fightCard
import tv.own.owntv.features.sports.live.GameCenterTestData.game
import tv.own.owntv.features.sports.live.GameCenterTestData.leader
import tv.own.owntv.features.sports.live.GameCenterTestData.ready
import tv.own.owntv.features.sports.live.GameCenterTestData.stat

class GameCenterPresentationTest {

    private val football = detail(
        stats = listOf(stat("totalYards", "327", "281"), stat("turnovers", "0", "2"), stat("thirdDown", "5/9", "3/10"), stat("top", "18:02", "14:26")),
        leaders = listOf(leader("passing", "J. Hurts", "224 YDS · 2 TD"), leader("rushing", "S. Barkley", "86 YDS · 1 TD"), leader("receiving"), leader("extra")),
        live = GameCenterLiveSituation(periodLabel = "3rd", clock = "4:32"),
    )

    @Test
    fun `upcoming event - matchup and info panel, never stats, leaders or a clock`() {
        val event = game(status = SportsEventStatus.SCHEDULED, awayScore = null, homeScore = null, detail = null)
        // Even if a backend sent stats for a scheduled game, the preview shows none.
        val p = GameCenterPresentation.preview(event, NFL, ready(football))
        assertEquals(GameCenterPhase.UPCOMING, p.phase)
        assertEquals(GameCenterLayout.MATCHUP, p.layout)
        assertTrue(p.teamStats.isEmpty())
        assertTrue(p.leaders.isEmpty())
        assertTrue(p.clockParts.isEmpty())
        assertTrue(p.showsInfoPanel)
        assertFalse(event.showsScores) // no fake 0 - 0
    }

    @Test
    fun `live football - at most three stats and three leaders, clock from detail`() {
        val p = GameCenterPresentation.preview(game(), NFL, ready(football))
        assertEquals(GameCenterPhase.LIVE, p.phase)
        assertEquals(listOf("totalYards", "turnovers", "thirdDown"), p.teamStats.map { it.key })
        assertEquals(listOf("passing", "rushing", "receiving"), p.leaders.map { it.key })
        assertEquals(listOf("3rd", "4:32"), p.clockParts)
        assertEquals(SportsVisualKind.FOOTBALL, p.kind)
        assertFalse(p.showsInfoPanel)
    }

    @Test
    fun `live without detail clock falls back to the event status detail`() {
        assertEquals(listOf("Q3 4:32"), GameCenterPresentation.preview(game(), NFL, GameCenterDetailState.Pending("evt_1")).clockParts)
        assertEquals(listOf("72'"), GameCenterPresentation.preview(game(league = "epl", detail = "72'"), EPL, GameCenterDetailState.Idle).clockParts)
    }

    @Test
    fun `halftime never duplicates into the clock line`() {
        assertTrue(GameCenterPresentation.preview(game(detail = "Halftime"), NFL, GameCenterDetailState.Idle).clockParts.isEmpty())
    }

    @Test
    fun `final event - stats and leaders kept, no clock`() {
        val p = GameCenterPresentation.preview(game(status = SportsEventStatus.FINAL, detail = "Final"), NFL, ready(football))
        assertEquals(GameCenterPhase.FINAL, p.phase)
        assertEquals(3, p.teamStats.size)
        assertEquals(3, p.leaders.size)
        assertTrue(p.clockParts.isEmpty())
        assertNull(p.situation)
    }

    @Test
    fun `final without detail is still intentional - info panel instead of an empty table`() {
        val p = GameCenterPresentation.preview(game(status = SportsEventStatus.FINAL, detail = "Final"), NFL, GameCenterDetailState.Unavailable("evt_1"))
        assertTrue(p.showsInfoPanel)
        assertFalse(p.detailPending)
    }

    @Test
    fun `missing stats - rows without values are dropped, all-empty collapses the section`() {
        val d = detail(stats = listOf(stat("a", null, null), stat("b", "", " "), stat("c", "3", null)), leaders = listOf(leader("points")))
        val p = GameCenterPresentation.preview(game(), NBA, ready(d))
        assertEquals(listOf("c"), p.teamStats.map { it.key })
        val none = GameCenterPresentation.preview(game(), NBA, ready(detail(stats = listOf(stat("a", null, null)), leaders = listOf(leader("points")))))
        assertFalse(none.hasStats)
        assertTrue(none.hasLeaders)
        assertFalse(none.showsInfoPanel)
    }

    @Test
    fun `missing leaders - leaders without a name or stat line are dropped and the section collapses`() {
        val d = detail(stats = listOf(stat("shots")), leaders = listOf(leader("a", name = " "), leader("b", summary = null), leader("c", summary = "")))
        val p = GameCenterPresentation.preview(game(league = "nhl"), NHL, ready(d))
        assertFalse(p.hasLeaders)
        assertTrue(p.hasStats)
    }

    @Test
    fun `missing headshots - initials fallback, headshot optional`() {
        val noPhoto = leader("passing", name = "Jalen Hurts")
        assertNull(noPhoto.headshotUrl)
        assertEquals("JH", GameCenterPresentation.leaderInitials(noPhoto))
        val p = GameCenterPresentation.preview(game(), NFL, ready(detail(leaders = listOf(noPhoto, leader("rushing", headshot = "file:///x.png")))))
        assertEquals(2, p.leaders.size)
    }

    @Test
    fun `leader team comes from its side`() {
        val e = game()
        assertEquals("PHI", GameCenterPresentation.leaderTeam(e, leader("a", side = GameCenterSide.AWAY))?.abbreviation)
        assertEquals("DAL", GameCenterPresentation.leaderTeam(e, leader("a", side = GameCenterSide.HOME))?.abbreviation)
        assertNull(GameCenterPresentation.leaderTeam(e, leader("a", side = null)))
    }

    @Test
    fun `a detail for another event is never shown`() {
        val other = ready(football.copy(eventId = "evt_other"))
        val p = GameCenterPresentation.preview(game(), NFL, other)
        assertTrue(p.teamStats.isEmpty())
        assertTrue(p.leaders.isEmpty())
        assertFalse(p.detailPending)
        assertTrue(GameCenterPresentation.preview(game(), NFL, GameCenterDetailState.Pending("evt_1")).detailPending)
        assertFalse(GameCenterPresentation.preview(game(), NFL, GameCenterDetailState.Pending("evt_other")).detailPending)
    }

    @Test
    fun `basketball stats`() {
        val d = detail(
            stats = listOf(stat("fgPct", "48%", "44%"), stat("rebounds", "38", "34"), stat("assists", "24", "19")),
            leaders = listOf(leader("points", summary = "27 PTS"), leader("rebounds", summary = "11 REB"), leader("assists", summary = "8 AST")),
            live = GameCenterLiveSituation(periodLabel = "4th", clock = "6:21"),
        )
        val p = GameCenterPresentation.preview(game(league = "nba", awayScore = "88", homeScore = "84"), NBA, ready(d))
        assertEquals(listOf("48%", "38", "24"), p.teamStats.map { it.away })
        assertEquals(listOf("27 PTS", "11 REB", "8 AST"), p.leaders.map { it.summary })
        assertEquals(listOf("4th", "6:21"), p.clockParts)
        assertEquals(SportsVisualKind.BASKETBALL, p.kind)
    }

    @Test
    fun `hockey stats`() {
        val d = detail(stats = listOf(stat("shots", "31", "27"), stat("powerPlay", "1/3", "0/2")), leaders = listOf(leader("goals", summary = "2"), leader("saves", summary = "25 SV")))
        val p = GameCenterPresentation.preview(game(league = "nhl"), NHL, ready(d))
        assertEquals(listOf("1/3", "0/2"), p.teamStats.last().let { listOf(it.away, it.home) })
        assertEquals(2, p.leaders.size)
        assertEquals(SportsVisualKind.HOCKEY, p.kind)
    }

    @Test
    fun `baseball - inning label, situation only when stated`() {
        val base = detail(stats = listOf(stat("hits", "8", "5"), stat("errors", "0", "1")), live = GameCenterLiveSituation(periodLabel = "Top 7th"))
        val p = GameCenterPresentation.preview(game(league = "mlb", detail = "Top 7th"), MLB, ready(base))
        assertEquals(listOf("Top 7th"), p.clockParts)
        assertNull(p.situation) // no outs / count / bases supplied → nothing drawn
        assertEquals(SportsVisualKind.BASEBALL, p.kind)

        val withSituation = base.copy(live = GameCenterLiveSituation(periodLabel = "Top 7th", outs = 2, balls = 1, strikes = 2, onFirst = true))
        val s = GameCenterPresentation.preview(game(league = "mlb"), MLB, ready(withSituation)).situation!!
        assertEquals(2, s.outs)
        assertTrue(s.hasBaseballCount)
        assertTrue(s.hasBases)
    }

    @Test
    fun `soccer - possession and shots, no player leaders collapses the section`() {
        val d = detail(stats = listOf(stat("possession", "57%", "43%"), stat("shots", "14", "8"), stat("shotsOnTarget", "6", "3")))
        val p = GameCenterPresentation.preview(game(league = "epl", detail = "72'"), EPL, ready(d))
        assertEquals(listOf("possession", "shots", "shotsOnTarget"), p.teamStats.map { it.key })
        assertFalse(p.hasLeaders)
        assertFalse(p.showsInfoPanel)
        assertEquals(SportsVisualKind.SOCCER, p.kind)
    }

    @Test
    fun `college football uses its own backdrop`() {
        assertEquals(SportsVisualKind.COLLEGE_FOOTBALL, GameCenterPresentation.preview(game(league = "ncaaf"), NCAAF, GameCenterDetailState.Idle).kind)
    }

    @Test
    fun `MMA card - fight layout with the main event, never team stats`() {
        val p = GameCenterPresentation.preview(fightCard(), UFC, ready(detail(eventId = "evt_ufc", stats = listOf(stat("x")))))
        assertEquals(GameCenterLayout.FIGHT, p.layout)
        assertEquals("Fighter A", p.bout?.fighters?.first()?.name)
        assertTrue(p.teamStats.isEmpty())
        assertTrue(p.showsInfoPanel)
        assertEquals(SportsVisualKind.MMA, p.kind)
    }

    @Test
    fun `completed bout - winner and method only as provided`() {
        val bout = SportsBout(1, "Welterweight", 5, 1_000L, SportsEventStatus.FINAL, 2, "3:14", true, listOf(GameCenterTestData.fighter("A"), GameCenterTestData.fighter("B")), winner = 0, method = "KO/TKO")
        val p = GameCenterPresentation.preview(fightCard(status = SportsEventStatus.FINAL, bout = bout), UFC, GameCenterDetailState.Idle)
        assertEquals(GameCenterPhase.FINAL, p.phase)
        assertEquals(0, p.bout?.winner)
        assertEquals("KO/TKO", p.bout?.method)
        val unstated = bout.copy(winner = null, method = null, round = null, clock = null)
        val q = GameCenterPresentation.preview(fightCard(status = SportsEventStatus.FINAL, bout = unstated), UFC, GameCenterDetailState.Idle)
        assertNull(q.bout?.winner)
        assertNull(q.bout?.method)
    }

    @Test
    fun `boxing uses the generic fight layout`() {
        val p = GameCenterPresentation.preview(fightCard(id = "evt_box", league = "boxing"), BOXING, GameCenterDetailState.Idle)
        assertEquals(GameCenterLayout.FIGHT, p.layout)
        assertEquals(SportsVisualKind.BOXING, p.kind)
    }

    @Test
    fun `stale detail is flagged`() {
        assertTrue(GameCenterPresentation.preview(game(), NFL, ready(football.copy(stale = true))).stale)
    }

    @Test
    fun `postponed and delayed phases`() {
        assertEquals(GameCenterPhase.INTERRUPTED, GameCenterPresentation.phase(game(status = SportsEventStatus.POSTPONED)))
        assertEquals(GameCenterPhase.INTERRUPTED, GameCenterPresentation.phase(game(status = SportsEventStatus.CANCELED)))
        assertEquals(GameCenterPhase.LIVE, GameCenterPresentation.phase(game(status = SportsEventStatus.DELAYED)))
        assertEquals(GameCenterPhase.UPCOMING, GameCenterPresentation.phase(game(status = SportsEventStatus.UNKNOWN)))
    }
}
