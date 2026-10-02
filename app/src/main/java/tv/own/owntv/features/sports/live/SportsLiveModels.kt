package tv.own.owntv.features.sports.live

import androidx.compose.runtime.Immutable

/**
 * GoatTV Sports Live — client models for the public DigitalOcean Sports API
 * (`/sports/home`, `/sports/events`, `/sports/leagues`). Sanitized metadata only: ids are GoatTV ids
 * (`nba`, `nba.phi`, `evt_…`), never provider ids, and nothing here can build a stream.
 */

@Immutable
data class SportsLeague(
    val id: String,
    val name: String,
    val shortName: String,
    val order: Int,
    val sport: String?,
    /** "team" for team sports; other kinds (athlete cards) may have null home/away. */
    val participantKind: String?,
    /** Soccer competition metadata (null for US leagues): league / cup / continental / international. */
    val competitionType: String? = null,
    val region: String? = null,
    val gender: String? = null,
)

@Immutable
data class SportsTeam(
    val id: String?,
    val name: String,
    val shortName: String?,
    val abbreviation: String?,
    val logoUrl: String?,
    /** Score as sent by the API (a string); null while scheduled. Use [SportsEvent.showsScores]. */
    val score: String?,
)

@Immutable
data class SportsBroadcast(
    val name: String,
    /** TV / STREAMING / RADIO */
    val type: String,
    /** NATIONAL / HOME / AWAY / REGIONAL */
    val market: String,
    val networkKey: String?,
)

enum class SportsEventStatus {
    SCHEDULED, LIVE, FINAL, DELAYED, POSTPONED, CANCELED,

    /** A status this app version doesn't know yet — rendered neutrally, never as live. */
    UNKNOWN;

    companion object {
        fun parse(raw: String?): SportsEventStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * One matched channel for an event (Phase B contract). Only present once the backend exposure gate
 * (`SPORTS_API_CHANNELS`) is on AND the app requests `?brand=`; Phase C1 never does either — see
 * [SportsChannelFeature]. [remoteId] is the provider stream id resolved against the LOCAL source;
 * the server never sends a URL.
 */
@Immutable
data class SportsChannelRef(
    val channelId: String,
    val remoteId: String,
    val epgChannelId: String?,
    val name: String,
    val category: String?,
    val logoUrl: String?,
    val networkKey: String?,
    val confidence: Int,
    val reason: String,
)

@Immutable
data class SportsEvent(
    val id: String,
    val leagueId: String,
    val title: String?,
    val away: SportsTeam?,
    val home: SportsTeam?,
    val startTimeMs: Long,
    val status: SportsEventStatus,
    val statusDetail: String?,
    val venueName: String?,
    val priority: Int,
    val broadcasts: List<SportsBroadcast>,
    val updatedAtMs: Long,
    /** Backend-ranked channels; always empty while the channel feature is off. */
    val channels: List<SportsChannelRef> = emptyList(),
) {
    val isLive: Boolean get() = status == SportsEventStatus.LIVE

    /** Scores are only meaningful once play has started — never show a scheduled placeholder "0". */
    val showsScores: Boolean
        get() = (status == SportsEventStatus.LIVE || status == SportsEventStatus.FINAL || status == SportsEventStatus.DELAYED) &&
            (home?.score != null || away?.score != null)

    val isTeamEvent: Boolean get() = home != null && away != null

    /** First TV broadcast (national first), else the first broadcast of any non-radio type. */
    val primaryBroadcast: SportsBroadcast?
        get() {
            val visual = broadcasts.filter { !it.type.equals("RADIO", ignoreCase = true) }
            return visual.firstOrNull { it.type.equals("TV", ignoreCase = true) && it.market.equals("NATIONAL", ignoreCase = true) }
                ?: visual.firstOrNull { it.type.equals("TV", ignoreCase = true) }
                ?: visual.firstOrNull()
        }
}

/** GET /sports/home */
data class SportsHomePayload(
    val generatedAtMs: Long,
    val cursor: String,
    val leagues: List<SportsLeague>,
    val popular: List<String>,
    val events: List<SportsEvent>,
)

/** GET /sports/events[?since=] */
data class SportsEventsPayload(
    val cursor: String,
    val events: List<SportsEvent>,
)

/**
 * Android-side feature boundary for Sports channels (Phase C2). While false the app never asks for
 * brand-scoped channel data and never offers Watch / Select Channel, even if a response carried
 * channels. Flip only after the backend exposure gate is intentionally enabled.
 */
object SportsChannelFeature {
    const val ENABLED: Boolean = false
}
