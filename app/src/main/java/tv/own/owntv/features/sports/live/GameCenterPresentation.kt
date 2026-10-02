package tv.own.owntv.features.sports.live

import androidx.compose.runtime.Immutable

/**
 * What the Game Center preview renders for one event (pure, unit-tested). The matchup comes from
 * the event alone, so it is ready the instant a card is focused; stats/leaders join only when the
 * detail for THIS event has arrived. The preview is a glance, not a box score: at most
 * [MAX_TEAM_STATS] comparisons and [MAX_LEADERS] leaders.
 */
@Immutable
data class GameCenterPreview(
    val eventId: String,
    val kind: SportsVisualKind,
    val phase: GameCenterPhase,
    val layout: GameCenterLayout,
    /** Live clock line parts ("3rd", "4:32" / "Top 7th" / "72'"); empty when unknown. */
    val clockParts: List<String>,
    /** Baseball outs/count/bases, only when the detail states them. */
    val situation: GameCenterLiveSituation?,
    val teamStats: List<GameCenterTeamStat>,
    val leaders: List<GameCenterLeader>,
    /** Fight layout: the bout to feature (main event). */
    val bout: SportsBout?,
    /** True while the focused event's detail is still loading (the matchup is already shown). */
    val detailPending: Boolean,
    val stale: Boolean,
) {
    val hasStats: Boolean get() = teamStats.isNotEmpty()
    val hasLeaders: Boolean get() = leaders.isNotEmpty()

    /** No stats to show: the right side shows kickoff / venue / broadcast instead of an empty table. */
    val showsInfoPanel: Boolean get() = !hasStats && !hasLeaders
}

enum class GameCenterPhase { UPCOMING, LIVE, FINAL, INTERRUPTED }

enum class GameCenterLayout { MATCHUP, FIGHT, HEADLINE }

internal object GameCenterPresentation {

    const val MAX_TEAM_STATS = 3
    const val MAX_LEADERS = 3

    fun phase(event: SportsEvent): GameCenterPhase = when (event.status) {
        SportsEventStatus.LIVE, SportsEventStatus.DELAYED -> GameCenterPhase.LIVE
        SportsEventStatus.FINAL -> GameCenterPhase.FINAL
        SportsEventStatus.POSTPONED, SportsEventStatus.CANCELED -> GameCenterPhase.INTERRUPTED
        SportsEventStatus.SCHEDULED, SportsEventStatus.UNKNOWN -> GameCenterPhase.UPCOMING
    }

    fun preview(event: SportsEvent, league: SportsLeague?, detailState: GameCenterDetailState): GameCenterPreview {
        val phase = phase(event)
        val layout = when {
            SportsEventPresentation.isFightCard(event, league) -> GameCenterLayout.FIGHT
            event.isTeamEvent -> GameCenterLayout.MATCHUP
            else -> GameCenterLayout.HEADLINE
        }
        // Only this event's detail counts: a response for the previously focused card is never shown.
        val detail = (detailState as? GameCenterDetailState.Ready)?.detail?.takeIf { it.eventId == event.id }
        // A game that hasn't started has nothing to compare — no "0 — 0" tables, no empty leaders.
        val showsGameData = phase == GameCenterPhase.LIVE || phase == GameCenterPhase.FINAL
        val teamStats = if (showsGameData && layout == GameCenterLayout.MATCHUP) visibleTeamStats(detail?.teamStats.orEmpty()) else emptyList()
        val leaders = if (showsGameData && layout == GameCenterLayout.MATCHUP) visibleLeaders(detail?.leaders.orEmpty()) else emptyList()
        return GameCenterPreview(
            eventId = event.id,
            kind = SportsEventPresentation.visualKind(league, event.leagueId),
            phase = phase,
            layout = layout,
            clockParts = if (phase == GameCenterPhase.LIVE) clockParts(event, detail?.live) else emptyList(),
            situation = detail?.live?.takeIf { phase == GameCenterPhase.LIVE && (it.outs != null || it.hasBaseballCount || it.hasBases) },
            teamStats = teamStats,
            leaders = leaders,
            bout = if (layout == GameCenterLayout.FIGHT) event.fight?.mainEvent else null,
            detailPending = detailState is GameCenterDetailState.Pending && detailState.eventId == event.id,
            stale = detail?.stale == true,
        )
    }

    /** First [MAX_TEAM_STATS] rows that have at least one value (backend order = importance). */
    fun visibleTeamStats(stats: List<GameCenterTeamStat>): List<GameCenterTeamStat> =
        stats.filter { it.label.isNotBlank() && (!it.away.isNullOrBlank() || !it.home.isNullOrBlank()) }.take(MAX_TEAM_STATS)

    /** First [MAX_LEADERS] leaders with a name and a stat line. */
    fun visibleLeaders(leaders: List<GameCenterLeader>): List<GameCenterLeader> =
        leaders.filter { it.athleteName.isNotBlank() && it.label.isNotBlank() && !it.summary.isNullOrBlank() }.take(MAX_LEADERS)

    /**
     * Detail period/clock when the Game Center supplies them, otherwise the event's own status
     * detail ("Q3 4:12", "72'"). Halftime is rendered by the status chip, never duplicated here.
     */
    fun clockParts(event: SportsEvent, live: GameCenterLiveSituation?): List<String> {
        val fromDetail = listOfNotNull(live?.periodLabel?.trim()?.takeIf { it.isNotEmpty() }, live?.clock?.trim()?.takeIf { it.isNotEmpty() })
        if (fromDetail.isNotEmpty()) return fromDetail
        val status = SportsEventPresentation.status(event)
        return listOfNotNull((status as? SportsEventPresentation.Status.Live)?.detail)
    }

    /** Leader badge fallback when no headshot is supplied ("Jalen Hurts" → "JH"). */
    fun leaderInitials(leader: GameCenterLeader): String = SportsEventPresentation.initials(leader.athleteName)

    /** The team a leader plays for, for the abbreviation tag ("PHI"). */
    fun leaderTeam(event: SportsEvent, leader: GameCenterLeader): SportsTeam? = when (leader.side) {
        GameCenterSide.AWAY -> event.away
        GameCenterSide.HOME -> event.home
        null -> null
    }
}
