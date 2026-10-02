package tv.own.owntv.features.sports.live

import tv.own.owntv.features.sports.live.SportsGameCenterFixtures.Companion.FIXTURE_ID_PREFIX

/**
 * DEBUG BUILDS ONLY — demo data for inspecting every Game Center state on a TV. Shaped like the
 * normalized backend contract; values are illustrative, not real results. No logos, flags or
 * headshots: fixtures exercise the initials fallbacks and never fetch anything.
 */
internal object GameCenterFixtureCatalog : SportsGameCenterFixtures {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    const val NFL_UPCOMING = FIXTURE_ID_PREFIX + "nfl_upcoming"
    const val NFL_LIVE = FIXTURE_ID_PREFIX + "nfl_live"
    const val NFL_FINAL = FIXTURE_ID_PREFIX + "nfl_final"
    const val NBA_LIVE = FIXTURE_ID_PREFIX + "nba_live"
    const val NHL_LIVE = FIXTURE_ID_PREFIX + "nhl_live"
    const val MLB_LIVE = FIXTURE_ID_PREFIX + "mlb_live"
    const val SOCCER_LIVE = FIXTURE_ID_PREFIX + "soccer_live"
    const val UFC_UPCOMING = FIXTURE_ID_PREFIX + "ufc_upcoming"
    const val UFC_FINAL = FIXTURE_ID_PREFIX + "ufc_final"

    override val leagues: List<SportsLeague> = listOf(
        SportsLeague("nfl", "NFL", "NFL", 1, "football", "team"),
        SportsLeague("nba", "NBA", "NBA", 3, "basketball", "team"),
        SportsLeague("mlb", "MLB", "MLB", 5, "baseball", "team"),
        SportsLeague("nhl", "NHL", "NHL", 7, "hockey", "team"),
        SportsLeague("epl", "Premier League", "EPL", 40, "soccer", "team", competitionType = "league", region = "England", gender = "men"),
        SportsLeague("ufc", "UFC", "UFC", 30, "mma", "athlete"),
    )

    private fun team(league: String, abbr: String, name: String, short: String, score: String? = null) =
        SportsTeam(id = "$league.${abbr.lowercase()}", name = name, shortName = short, abbreviation = abbr, logoUrl = null, score = score)

    private fun tv(name: String) = SportsBroadcast(name, "TV", "NATIONAL", name.lowercase())

    private fun game(
        id: String, league: String, away: SportsTeam, home: SportsTeam, start: Long, status: SportsEventStatus,
        detail: String?, venue: String, broadcast: String,
    ) = SportsEvent(
        id = id, leagueId = league, title = null, away = away, home = home, startTimeMs = start, status = status,
        statusDetail = detail, venueName = venue, priority = 100, broadcasts = listOf(tv(broadcast)), updatedAtMs = start,
    )

    private fun fighter(name: String, record: String, country: String) =
        SportsFighter(id = null, name = name, shortName = null, record = record, country = country, flagUrl = null)

    override fun events(nowMs: Long): List<SportsEvent> = listOf(
        game(NFL_UPCOMING, "nfl", team("nfl", "NYG", "New York Giants", "Giants"), team("nfl", "PHI", "Philadelphia Eagles", "Eagles"),
            nowMs + DAY, SportsEventStatus.SCHEDULED, null, "Lincoln Financial Field", "FOX"),
        game(NFL_LIVE, "nfl", team("nfl", "PHI", "Philadelphia Eagles", "Eagles", "21"), team("nfl", "DAL", "Dallas Cowboys", "Cowboys", "17"),
            nowMs - 2 * HOUR, SportsEventStatus.LIVE, "3rd 4:32", "AT&T Stadium", "NBC"),
        game(NFL_FINAL, "nfl", team("nfl", "KC", "Kansas City Chiefs", "Chiefs", "24"), team("nfl", "BUF", "Buffalo Bills", "Bills", "27"),
            nowMs - 6 * HOUR, SportsEventStatus.FINAL, "Final", "Highmark Stadium", "CBS"),
        game(NBA_LIVE, "nba", team("nba", "MIA", "Miami Heat", "Heat", "88"), team("nba", "TOR", "Toronto Raptors", "Raptors", "84"),
            nowMs - 2 * HOUR, SportsEventStatus.LIVE, "4th 6:21", "Scotiabank Arena", "ESPN"),
        game(NHL_LIVE, "nhl", team("nhl", "NYR", "New York Rangers", "Rangers", "3"), team("nhl", "DET", "Detroit Red Wings", "Red Wings", "2"),
            nowMs - 2 * HOUR, SportsEventStatus.LIVE, "3rd 8:14", "Little Caesars Arena", "TNT"),
        game(MLB_LIVE, "mlb", team("mlb", "NYY", "New York Yankees", "Yankees", "4"), team("mlb", "BOS", "Boston Red Sox", "Red Sox", "2"),
            nowMs - 2 * HOUR, SportsEventStatus.LIVE, "Top 7th", "Fenway Park", "FOX"),
        game(SOCCER_LIVE, "epl", team("epl", "ARS", "Arsenal", "Arsenal", "2"), team("epl", "CHE", "Chelsea", "Chelsea", "1"),
            nowMs - HOUR, SportsEventStatus.LIVE, "72'", "Stamford Bridge", "NBC"),
        SportsEvent(
            id = UFC_UPCOMING, leagueId = "ufc", title = "UFC 332", away = null, home = null, startTimeMs = nowMs + 2 * DAY,
            status = SportsEventStatus.SCHEDULED, statusDetail = null, venueName = "T-Mobile Arena", priority = 100,
            broadcasts = listOf(SportsBroadcast("Paramount+", "STREAMING", "NATIONAL", "paramountplus")), updatedAtMs = nowMs,
            fight = SportsFight(
                listOf(
                    SportsBout(1, "Flyweight", 3, nowMs + 2 * DAY, SportsEventStatus.SCHEDULED, null, null, false,
                        listOf(fighter("Rafael Moreno", "14-3-0", "Brazil"), fighter("Kenji Sato", "11-2-0", "Japan")), null, null),
                    SportsBout(2, "Lightweight", 5, nowMs + 2 * DAY + 2 * HOUR, SportsEventStatus.SCHEDULED, null, null, true,
                        listOf(fighter("Dmitri Volkov", "23-4-0", "Russia"), fighter("Marcus Hale", "19-2-0", "USA")), null, null),
                ),
            ),
        ),
        SportsEvent(
            id = UFC_FINAL, leagueId = "ufc", title = "UFC Fight Night", away = null, home = null, startTimeMs = nowMs - DAY,
            status = SportsEventStatus.FINAL, statusDetail = "Final", venueName = "UFC APEX", priority = 100,
            broadcasts = listOf(SportsBroadcast("ESPN+", "STREAMING", "NATIONAL", "espnplus")), updatedAtMs = nowMs,
            fight = SportsFight(
                listOf(
                    SportsBout(1, "Welterweight", 5, nowMs - DAY, SportsEventStatus.FINAL, 2, "3:14", true,
                        listOf(fighter("Leon Carver", "20-5-0", "England"), fighter("Tomas Reyes", "17-3-1", "Mexico")), winner = 0, method = "KO/TKO"),
                ),
            ),
        ),
    )

    private fun stat(key: String, label: String, away: String, home: String) = GameCenterTeamStat(key, label, away, home)

    private fun leader(key: String, label: String, name: String, side: GameCenterSide, summary: String?) =
        GameCenterLeader(key, label, name, side, summary, headshotUrl = null)

    override fun detail(eventId: String): GameCenterDetail? = when (eventId) {
        // Scheduled: the backend has nothing to compare yet.
        NFL_UPCOMING, UFC_UPCOMING -> GameCenterDetail(eventId, GameCenterAvailability.NONE)
        NFL_LIVE -> GameCenterDetail(
            eventId, GameCenterAvailability.FULL, refreshAfterMs = 15_000,
            live = GameCenterLiveSituation(periodLabel = "3rd", clock = "4:32", possession = GameCenterSide.AWAY),
            teamStats = listOf(
                stat("totalYards", "Total Yards", "327", "281"),
                stat("turnovers", "Turnovers", "0", "2"),
                stat("thirdDown", "3rd Down", "5/9", "3/10"),
                stat("possessionTime", "Possession", "18:02", "14:26"), // 4th row: the preview caps at 3
            ),
            leaders = listOf(
                leader("passing", "Passing", "J. Hurts", GameCenterSide.AWAY, "224 YDS · 2 TD"),
                leader("rushing", "Rushing", "S. Barkley", GameCenterSide.AWAY, "86 YDS · 1 TD"),
                leader("receiving", "Receiving", "C. Lamb", GameCenterSide.HOME, "7 REC · 98 YDS"),
            ),
        )
        NFL_FINAL -> GameCenterDetail(
            eventId, GameCenterAvailability.PARTIAL,
            teamStats = listOf(
                stat("totalYards", "Total Yards", "362", "401"),
                stat("turnovers", "Turnovers", "2", "1"),
            ),
            leaders = listOf(
                leader("passing", "Passing", "P. Mahomes", GameCenterSide.AWAY, "289 YDS · 2 TD"),
                leader("rushing", "Rushing", "J. Cook", GameCenterSide.HOME, "112 YDS · 1 TD"),
                leader("receiving", "Receiving", "T. Kelce", GameCenterSide.AWAY, null), // no stat line → dropped
            ),
        )
        NBA_LIVE -> GameCenterDetail(
            eventId, GameCenterAvailability.FULL, refreshAfterMs = 10_000,
            live = GameCenterLiveSituation(periodLabel = "4th", clock = "6:21"),
            teamStats = listOf(
                stat("fgPct", "FG%", "48%", "44%"),
                stat("rebounds", "Rebounds", "38", "34"),
                stat("assists", "Assists", "24", "19"),
            ),
            leaders = listOf(
                leader("points", "Points", "J. Carter", GameCenterSide.AWAY, "27 PTS"),
                leader("rebounds", "Rebounds", "D. Okafor", GameCenterSide.HOME, "11 REB"),
                leader("assists", "Assists", "M. Ellis", GameCenterSide.AWAY, "8 AST"),
            ),
        )
        NHL_LIVE -> GameCenterDetail(
            eventId, GameCenterAvailability.FULL, refreshAfterMs = 15_000,
            live = GameCenterLiveSituation(periodLabel = "3rd", clock = "8:14"),
            teamStats = listOf(
                stat("shots", "Shots", "31", "27"),
                stat("powerPlay", "Power Play", "1/3", "0/2"),
            ),
            leaders = listOf(
                leader("goals", "Goals", "A. Lindqvist", GameCenterSide.AWAY, "2"),
                leader("saves", "Goalie", "K. Varga", GameCenterSide.AWAY, "25 SV"),
            ),
        )
        MLB_LIVE -> GameCenterDetail(
            eventId, GameCenterAvailability.PARTIAL, refreshAfterMs = 15_000,
            // Outs / count / bases are not supplied by this fixture, so none are drawn.
            live = GameCenterLiveSituation(periodLabel = "Top 7th"),
            teamStats = listOf(
                stat("hits", "Hits", "8", "5"),
                stat("errors", "Errors", "0", "1"),
            ),
            leaders = listOf(
                leader("batting", "Batting", "R. Diaz", GameCenterSide.AWAY, "2-3, HR"),
                leader("pitching", "Pitching", "C. Morgan", GameCenterSide.AWAY, "6 IP · 7 K"),
            ),
        )
        SOCCER_LIVE -> GameCenterDetail(
            eventId, GameCenterAvailability.PARTIAL, refreshAfterMs = 15_000,
            teamStats = listOf(
                stat("possession", "Possession", "57%", "43%"),
                stat("shots", "Shots", "14", "8"),
                stat("shotsOnTarget", "Shots on Target", "6", "3"),
            ),
            // No soccer player leaders from the provider: the leaders section collapses.
            scoring = listOf(
                GameCenterScoringPlay("1st", "23'", GameCenterSide.HOME, "Goal"),
                GameCenterScoringPlay("2nd", "51'", GameCenterSide.AWAY, "Goal"),
                GameCenterScoringPlay("2nd", "63'", GameCenterSide.AWAY, "Goal"),
            ),
        )
        UFC_FINAL -> GameCenterDetail(eventId, GameCenterAvailability.NONE)
        else -> null
    }
}
