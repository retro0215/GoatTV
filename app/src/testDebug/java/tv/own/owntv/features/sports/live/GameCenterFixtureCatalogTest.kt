package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.GameCenterFixtureCatalog as C

/** Debug-only: the Shield inspection fixtures cover every required Game Center state. */
class GameCenterFixtureCatalogTest {

    private val now = 1_800_000_000_000L
    private val events = C.events(now).associateBy { it.id }
    private val leagues = C.leagues.associateBy { it.id }

    private fun preview(id: String): GameCenterPreview {
        val e = events.getValue(id)
        val d = C.detail(id)
        return GameCenterPresentation.preview(e, leagues[e.leagueId], if (d != null) GameCenterDetailState.Ready(id, d) else GameCenterDetailState.Unavailable(id))
    }

    @Test
    fun `every required fixture exists and is prefixed`() {
        val required = listOf(C.NFL_UPCOMING, C.NFL_LIVE, C.NFL_FINAL, C.NBA_LIVE, C.NHL_LIVE, C.MLB_LIVE, C.SOCCER_LIVE, C.UFC_UPCOMING, C.UFC_FINAL)
        assertEquals(required.toSet(), events.keys)
        assertTrue(events.keys.all { it.startsWith(SportsGameCenterFixtures.FIXTURE_ID_PREFIX) })
        assertTrue(events.values.all { it.leagueId in leagues })
    }

    @Test
    fun `fixtures never load images - no logos, flags or headshots`() {
        assertTrue(events.values.all { it.home?.logoUrl == null && it.away?.logoUrl == null })
        assertTrue(events.values.flatMap { it.fight?.bouts.orEmpty() }.flatMap { it.fighters }.all { it.flagUrl == null })
        assertTrue(events.keys.mapNotNull { C.detail(it) }.flatMap { it.leaders }.all { it.headshotUrl == null })
    }

    @Test
    fun `upcoming NFL - no stats, info panel`() {
        val p = preview(C.NFL_UPCOMING)
        assertEquals(GameCenterPhase.UPCOMING, p.phase)
        assertTrue(p.showsInfoPanel)
    }

    @Test
    fun `live NFL - three stats (fourth capped), three leaders, 3rd 4_32`() {
        val p = preview(C.NFL_LIVE)
        assertEquals(listOf("Total Yards", "Turnovers", "3rd Down"), p.teamStats.map { it.label })
        assertEquals(listOf("J. Hurts", "S. Barkley", "C. Lamb"), p.leaders.map { it.athleteName })
        assertEquals(listOf("3rd", "4:32"), p.clockParts)
    }

    @Test
    fun `final NFL - leader without a stat line collapses`() {
        val p = preview(C.NFL_FINAL)
        assertEquals(GameCenterPhase.FINAL, p.phase)
        assertEquals(2, p.leaders.size)
        assertTrue(p.clockParts.isEmpty())
    }

    @Test
    fun `NBA NHL MLB soccer fixtures`() {
        assertEquals(listOf("FG%", "Rebounds", "Assists"), preview(C.NBA_LIVE).teamStats.map { it.label })
        assertEquals(listOf("Shots", "Power Play"), preview(C.NHL_LIVE).teamStats.map { it.label })
        val mlb = preview(C.MLB_LIVE)
        assertEquals(listOf("Top 7th"), mlb.clockParts)
        assertNull(mlb.situation)
        val soccer = preview(C.SOCCER_LIVE)
        assertEquals(listOf("Possession", "Shots", "Shots on Target"), soccer.teamStats.map { it.label })
        assertFalse(soccer.hasLeaders)
    }

    @Test
    fun `UFC fixtures - fight layout, winner and method only on the completed bout`() {
        val upcoming = preview(C.UFC_UPCOMING)
        assertEquals(GameCenterLayout.FIGHT, upcoming.layout)
        assertTrue(upcoming.bout!!.mainEvent)
        assertNull(upcoming.bout!!.winner)
        val done = preview(C.UFC_FINAL)
        assertEquals(0, done.bout!!.winner)
        assertEquals("KO/TKO", done.bout!!.method)
        assertNotNull(done.bout!!.round)
    }

    @Test
    fun `no boxing fixtures are fabricated`() {
        assertFalse(leagues.values.any { it.sport == "boxing" })
    }
}
