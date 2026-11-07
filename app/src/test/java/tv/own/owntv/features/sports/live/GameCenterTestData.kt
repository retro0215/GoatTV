package tv.own.owntv.features.sports.live

/** Small builders for Game Center tests (test-only data; not the debug fixture catalog). */
internal object GameCenterTestData {

    val NFL = SportsLeague("nfl", "NFL", "NFL", 1, "football", "team")
    val NCAAF = SportsLeague("ncaaf", "College Football", "NCAAF", 2, "football", "team")
    val NBA = SportsLeague("nba", "NBA", "NBA", 3, "basketball", "team")
    val MLB = SportsLeague("mlb", "MLB", "MLB", 5, "baseball", "team")
    val NHL = SportsLeague("nhl", "NHL", "NHL", 7, "hockey", "team")
    val EPL = SportsLeague("epl", "Premier League", "EPL", 40, "soccer", "team")
    val UFC = SportsLeague("ufc", "UFC", "UFC", 30, "mma", "athlete")
    val BOXING = SportsLeague("boxing", "Boxing", "Boxing", 31, "boxing", "athlete")

    fun team(abbr: String, score: String? = null, logo: String? = null) =
        SportsTeam("x.${abbr.lowercase()}", abbr, abbr, abbr, logo, score)

    fun game(
        id: String = "evt_1",
        league: String = "nfl",
        status: SportsEventStatus = SportsEventStatus.LIVE,
        awayScore: String? = "21",
        homeScore: String? = "17",
        detail: String? = "Q3 4:32",
        title: String? = null,
    ) = SportsEvent(
        id = id, leagueId = league, title = title,
        away = team("PHI", awayScore), home = team("DAL", homeScore),
        startTimeMs = 1_000L, status = status, statusDetail = detail, venueName = "AT&T Stadium", priority = 50,
        broadcasts = listOf(SportsBroadcast("NBC", "TV", "NATIONAL", "nbc")), updatedAtMs = 1_000L,
    )

    fun fighter(name: String) = SportsFighter(null, name, null, "10-1-0", "USA", flagUrl = null)

    fun fightCard(
        id: String = "evt_ufc",
        league: String = "ufc",
        status: SportsEventStatus = SportsEventStatus.SCHEDULED,
        bout: SportsBout = SportsBout(1, "Lightweight", 5, 1_000L, status, null, null, true, listOf(fighter("Fighter A"), fighter("Fighter B")), null, null),
    ) = SportsEvent(
        id = id, leagueId = league, title = "UFC 332", away = null, home = null, startTimeMs = 1_000L, status = status,
        statusDetail = null, venueName = null, priority = 50, broadcasts = emptyList(), updatedAtMs = 1_000L,
        fight = SportsFight(listOf(bout)),
    )

    fun stat(key: String, away: String? = "1", home: String? = "2") = GameCenterTeamStat(key, key, away, home)

    fun leader(key: String, name: String = "A. Player", summary: String? = "10 PTS", side: GameCenterSide? = GameCenterSide.AWAY, headshot: String? = null) =
        GameCenterLeader(key, key, name, side, summary, headshot)

    fun detail(
        eventId: String = "evt_1",
        stats: List<GameCenterTeamStat> = emptyList(),
        leaders: List<GameCenterLeader> = emptyList(),
        live: GameCenterLiveSituation? = null,
        stale: Boolean = false,
    ) = GameCenterDetail(eventId, GameCenterAvailability.AVAILABLE, stale = stale, live = live, teamStats = stats, leaders = leaders)

    fun ready(detail: GameCenterDetail) = GameCenterDetailState.Ready(detail.eventId, detail)

    /** Controller wait for tests that don't exercise timing: no settle; refresh/retry waits never end. */
    val skipSettleOnly: suspend (Long) -> Unit = { ms -> if (ms != SportsPreviewController.DETAIL_SETTLE_MS) kotlinx.coroutines.awaitCancellation() }
}
