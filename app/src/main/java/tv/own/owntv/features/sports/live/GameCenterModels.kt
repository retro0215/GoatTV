package tv.own.owntv.features.sports.live

import androidx.compose.runtime.Immutable

/**
 * GoatTV Game Center — Android-side normalized detail for one event, the shape the future
 * `/sports/events/{id}/center` contract is converging on. Provider-neutral: no ESPN types, ids or
 * field names. Every section is optional because no sport supplies all of them (soccer has no
 * player leaders from the provider, a scheduled game has no stats, boxing has nothing yet), and
 * the UI collapses whatever is absent rather than drawing empty tables.
 *
 * Labels ("Total Yards", "Passing") and display values ("1/3", "224 YDS · 2 TD") arrive already
 * formatted by the backend; Android never computes a stat.
 */
@Immutable
data class GameCenterDetail(
    val eventId: String,
    val availability: GameCenterAvailability,
    /** The backend served a cached copy because its provider fetch failed. */
    val stale: Boolean = false,
    /** Backend hint for the next refresh (live ≈ seconds, final ≈ never); null = no hint. */
    val refreshAfterMs: Long? = null,
    val live: GameCenterLiveSituation? = null,
    /** Backend-ordered most-useful-first; the preview shows only the first few. */
    val teamStats: List<GameCenterTeamStat> = emptyList(),
    val leaders: List<GameCenterLeader> = emptyList(),
    /** Box score groups for Game Details (not shown in the preview). */
    val playerGroups: List<GameCenterPlayerGroup> = emptyList(),
    val scoring: List<GameCenterScoringPlay> = emptyList(),
)

enum class GameCenterAvailability {
    /** Every section the sport supports was returned. */
    FULL,

    /** Some sections are missing (provider gap); render what exists. */
    PARTIAL,

    /** Nothing beyond the event itself (scheduled game, unsupported sport, provider down). */
    NONE,
}

enum class GameCenterSide { AWAY, HOME }

/** Live situation; every field optional. Baseball fields stay null for other sports. */
@Immutable
data class GameCenterLiveSituation(
    /** Backend period label: "3rd", "4th", "OT", "Top 7th", "72'". */
    val periodLabel: String? = null,
    /** Game clock where the sport has one ("4:32"). */
    val clock: String? = null,
    val outs: Int? = null,
    val balls: Int? = null,
    val strikes: Int? = null,
    val onFirst: Boolean? = null,
    val onSecond: Boolean? = null,
    val onThird: Boolean? = null,
    /** Team with possession / at bat, when stated. */
    val possession: GameCenterSide? = null,
) {
    val hasBaseballCount: Boolean get() = balls != null && strikes != null
    val hasBases: Boolean get() = onFirst != null || onSecond != null || onThird != null
}

/** One away-vs-home comparison row ("Total Yards 327 — 281"). */
@Immutable
data class GameCenterTeamStat(
    /** Stable key ("totalYards", "fgPct", "possession") for ordering/tests; never displayed. */
    val key: String,
    val label: String,
    val away: String?,
    val home: String?,
)

/** A category leader ("Passing · J. Hurts · 224 YDS · 2 TD"). */
@Immutable
data class GameCenterLeader(
    val key: String,
    val label: String,
    val athleteName: String,
    val side: GameCenterSide?,
    val summary: String?,
    /** Optional; the preview is complete without it (initials fallback). */
    val headshotUrl: String? = null,
)

@Immutable
data class GameCenterPlayerGroup(
    val side: GameCenterSide,
    val label: String,
    val columns: List<String>,
    val rows: List<GameCenterPlayerRow>,
)

@Immutable
data class GameCenterPlayerRow(val athleteName: String, val values: List<String>)

@Immutable
data class GameCenterScoringPlay(
    val periodLabel: String?,
    val clock: String?,
    val side: GameCenterSide?,
    val text: String,
)

/**
 * Where Game Center detail comes from. Production has no endpoint yet ([None]); the debug build's
 * fixture mode supplies local fixtures. Implementations must be cancellation-friendly: the
 * controller cancels a request as soon as focus moves to another event.
 */
fun interface GameCenterDetailSource {
    suspend fun detail(eventId: String): GameCenterDetail?

    companion object {
        /** No Game Center backend connected: the preview renders from the event alone, without any I/O. */
        val None = GameCenterDetailSource { null }
    }
}

/** Detail load state for the focused event; [eventId] guards against showing a stale event's stats. */
sealed interface GameCenterDetailState {
    val eventId: String?

    data object Idle : GameCenterDetailState { override val eventId: String? = null }
    data class Pending(override val eventId: String) : GameCenterDetailState
    data class Ready(override val eventId: String, val detail: GameCenterDetail) : GameCenterDetailState
    data class Unavailable(override val eventId: String) : GameCenterDetailState
}
