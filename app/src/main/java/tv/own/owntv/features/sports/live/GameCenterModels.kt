package tv.own.owntv.features.sports.live

import androidx.compose.runtime.Immutable

/**
 * GoatTV Game Center — Android-side normalized detail for one event, mapped from the GoatTV Sports
 * API `GET /sports/events/{id}/game-center` ([GameCenterApi]). Provider-neutral: no ESPN types, ids or
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
    /** The backend's snapshot is older than it should be for the event's state; still worth showing. */
    val stale: Boolean = false,
    /** Backend hint for the next refresh while focused (live ≈ 30 s, pending ≈ 10 s); null = none needed. */
    val refreshAfterMs: Long? = null,
    val live: GameCenterLiveSituation? = null,
    /** Backend-ordered most-useful-first; the preview shows only the first few. */
    val teamStats: List<GameCenterTeamStat> = emptyList(),
    val leaders: List<GameCenterLeader> = emptyList(),
    /** Box score groups for Game Details (not shown in the preview). */
    val playerGroups: List<GameCenterPlayerGroup> = emptyList(),
    val scoring: List<GameCenterScoringPlay> = emptyList(),
)

/** The backend's `gameCenter.availability`, exactly. */
enum class GameCenterAvailability {
    /** A snapshot exists: render whatever sections it supplies (any may be absent). */
    AVAILABLE,

    /** Nothing stored yet; the backend is fetching it. Poll on the refresh hint while focused. */
    PENDING,

    /** Nothing beyond the event itself (feature off, unsupported sport, postponed, provider failures). */
    UNAVAILABLE,
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
    /** Football: team with the ball, when stated. */
    val possession: GameCenterSide? = null,
    /** Football: "3rd & 2 at LIB 33", when stated. */
    val downDistance: String? = null,
    /** Most recent play as the backend states it; null when not supplied (never invented). */
    val lastPlay: String? = null,
) {
    val hasBaseballCount: Boolean get() = balls != null && strikes != null
    val hasBases: Boolean get() = onFirst != null || onSecond != null || onThird != null
}

/** One away-vs-home comparison row ("Total Yards 327 — 281"). */
@Immutable
data class GameCenterTeamStat(
    /** Stable key ("totalYards", "fieldGoalPct", "possession") for ordering/tests; never displayed. */
    val key: String,
    val label: String,
    val away: String?,
    val home: String?,
    /** App string for the label when the backend only supplies a column header ("E" → "Errors"). */
    @param:androidx.annotation.StringRes val labelRes: Int? = null,
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
    /** Headline value ("299", "2-3") shown before [summary]; null when the source gives only a line. */
    val value: String? = null,
) {
    /** The stat line parts in display order ("299", "22/40, 3 TD, 2 INT"); joined by the UI's separator. */
    val statParts: List<String> get() = listOfNotNull(value?.takeIf { it.isNotBlank() }, summary?.takeIf { it.isNotBlank() })
}

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

/** One Game Center request's outcome. */
sealed interface GameCenterFetch {
    /** The backend answered (AVAILABLE, PENDING or UNAVAILABLE). */
    data class Loaded(val detail: GameCenterDetail) : GameCenterFetch

    /**
     * Transient failure (network, 5xx, non-JSON, 429): keep whatever is already shown; retry only on the
     * normal refresh schedule ([retryAfterMs] when the server said so).
     */
    data class Failed(val retryAfterMs: Long? = null) : GameCenterFetch
}

/**
 * Where Game Center detail comes from: the GoatTV Sports API in production ([GameCenterApiSource]);
 * the debug fixture catalog for its own fixture events. Implementations must be cancellation-friendly:
 * the controller cancels a request as soon as focus moves to another event.
 */
fun interface GameCenterDetailSource {
    suspend fun fetch(eventId: String): GameCenterFetch

    companion object {
        /** No Game Center backend: every event is UNAVAILABLE without any I/O (tests, unsupported setups). */
        val None = GameCenterDetailSource { id -> GameCenterFetch.Loaded(GameCenterDetail(id, GameCenterAvailability.UNAVAILABLE)) }
    }
}

/** Detail load state for the focused event; [eventId] guards against showing a stale event's stats. */
sealed interface GameCenterDetailState {
    val eventId: String?

    data object Idle : GameCenterDetailState { override val eventId: String? = null }

    /** Waiting for detail: the first request is in flight, or the backend answered PENDING. */
    data class Pending(override val eventId: String) : GameCenterDetailState
    data class Ready(override val eventId: String, val detail: GameCenterDetail) : GameCenterDetailState
    data class Unavailable(override val eventId: String) : GameCenterDetailState
}
