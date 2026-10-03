package tv.own.owntv.features.sports.live

import android.util.Log
import org.json.JSONArray
import tv.own.owntv.R
import org.json.JSONObject

/**
 * GoatTV Game Center over the public Sports API: `GET /sports/events/{evt_id}/game-center`.
 *
 * DTO ([GameCenterResponseDto]) → Android domain ([GameCenterDetail]) here; presentation and Compose
 * never see network objects. Android talks only to the GoatTV API (never the data provider): the
 * contract carries GoatTV ids (`evt_…`, `ply_…`), GoatTV stat keys/labels and no provider ids or URLs.
 * Every section is optional; anything absent stays null/empty and is never inferred.
 */
internal data class GameCenterResponseDto(
    val eventId: String,
    val availability: String,
    val phase: String?,
    val stale: Boolean,
    val refreshAfterSeconds: Long?,
    val situation: SituationDto?,
    val teamStats: List<StatDto>,
    val leaders: LeadersDto?,
    val lineScoreExtras: List<StatDto>,
    val boxScore: Map<GameCenterSide, List<BoxGroupDto>>,
    val scoring: List<ScoringDto>,
) {
    data class SituationDto(
        val kind: String?,
        val possession: String?,
        val downDistance: String?,
        val balls: Int?,
        val strikes: Int?,
        val outs: Int?,
        val onFirst: Boolean?,
        val onSecond: Boolean?,
        val onThird: Boolean?,
        val lastPlay: String?,
    )

    data class StatDto(val key: String, val label: String, val away: String?, val home: String?)

    data class PlayerDto(val name: String, val headshotUrl: String?)

    data class LeaderDto(val player: PlayerDto, val value: String?, val summary: String?)

    data class LeaderCategoryDto(val key: String, val label: String, val away: LeaderDto?, val home: LeaderDto?)

    data class LeadersDto(val scope: String?, val categories: List<LeaderCategoryDto>)

    data class BoxGroupDto(val label: String, val columns: List<String>, val rows: List<Pair<String, List<String?>>>)

    data class ScoringDto(val side: String?, val period: String?, val clock: String?, val text: String)
}

internal object GameCenterApi {

    /** GoatTV event ids only; anything else (debug fixture ids, malformed ids) never reaches the network. */
    val EVENT_ID = Regex("^evt_[0-9a-f]{20}$")

    /** Refresh hints are clamped so a bad value can neither hammer the API nor freeze a live game. */
    const val MIN_REFRESH_SECONDS = 5L
    const val MAX_REFRESH_SECONDS = 3600L

    /** `GET …/game-center` body → DTO; null when it is not the expected object or names another event. */
    fun parse(body: String, requestedEventId: String): GameCenterResponseDto? = runCatching {
        val root = JSONObject(body)
        val eventId = root.optJSONObject("event")?.str("id") ?: return null
        if (eventId != requestedEventId) return null
        val gc = root.optJSONObject("gameCenter") ?: return null
        val availability = gc.str("availability") ?: return null
        GameCenterResponseDto(
            eventId = eventId,
            availability = availability,
            phase = gc.str("phase"),
            stale = gc.optBoolean("stale", false),
            refreshAfterSeconds = if (root.has("refreshAfterSeconds") && !root.isNull("refreshAfterSeconds")) root.optLong("refreshAfterSeconds") else null,
            situation = gc.optJSONObject("situation")?.let { s ->
                GameCenterResponseDto.SituationDto(
                    kind = s.str("kind"),
                    possession = s.str("possession"),
                    downDistance = s.str("downDistance"),
                    balls = s.int("balls"),
                    strikes = s.int("strikes"),
                    outs = s.int("outs"),
                    onFirst = s.bool("onFirst"),
                    onSecond = s.bool("onSecond"),
                    onThird = s.bool("onThird"),
                    lastPlay = s.str("lastPlay"),
                )
            },
            teamStats = stats(gc.optJSONArray("teamStats")),
            leaders = gc.optJSONObject("leaders")?.let { l ->
                GameCenterResponseDto.LeadersDto(
                    scope = l.str("scope"),
                    categories = l.optJSONArray("categories").objects().mapNotNull { c ->
                        val key = c.str("key") ?: return@mapNotNull null
                        GameCenterResponseDto.LeaderCategoryDto(key, c.str("label") ?: return@mapNotNull null, leader(c.optJSONObject("away")), leader(c.optJSONObject("home")))
                    },
                )
            },
            lineScoreExtras = stats(gc.optJSONObject("lineScore")?.optJSONArray("extras")),
            boxScore = buildMap {
                val box = gc.optJSONObject("boxScore") ?: return@buildMap
                for ((side, key) in listOf(GameCenterSide.AWAY to "away", GameCenterSide.HOME to "home")) {
                    put(side, box.optJSONObject(key)?.optJSONArray("groups").objects().mapNotNull(::boxGroup))
                }
            },
            scoring = gc.optJSONArray("scoring").objects().mapNotNull { p ->
                GameCenterResponseDto.ScoringDto(p.str("side"), p.str("period"), p.str("clock"), p.str("text") ?: return@mapNotNull null)
            },
        )
    }.getOrNull()

    /** DTO → domain. Pure (unit-tested). */
    fun toDomain(dto: GameCenterResponseDto): GameCenterDetail {
        val availability = when (dto.availability) {
            "AVAILABLE" -> GameCenterAvailability.AVAILABLE
            "PENDING" -> GameCenterAvailability.PENDING
            else -> GameCenterAvailability.UNAVAILABLE // includes values this app version doesn't know
        }
        val refreshMs = dto.refreshAfterSeconds?.coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)?.times(1000)
        if (availability != GameCenterAvailability.AVAILABLE) {
            return GameCenterDetail(dto.eventId, availability, refreshAfterMs = refreshMs)
        }
        return GameCenterDetail(
            eventId = dto.eventId,
            availability = availability,
            stale = dto.stale,
            refreshAfterMs = refreshMs,
            live = dto.situation?.let(::situation),
            teamStats = teamStats(dto),
            // Season leaders (pre-game) are never presented as this game's leaders.
            leaders = if (dto.leaders?.scope == "GAME") dto.leaders.categories.mapNotNull(::gameLeader) else emptyList(),
            playerGroups = dto.boxScore.flatMap { (side, groups) ->
                groups.map { g -> GameCenterPlayerGroup(side, g.label, g.columns, g.rows.map { (name, values) -> GameCenterPlayerRow(name, values.map { it.orEmpty() }) }) }
            },
            scoring = dto.scoring.map { GameCenterScoringPlay(it.period, it.clock, side(it.side), it.text) },
        )
    }

    /** Backend order is "most useful first"; MLB errors live in the line-score extras (R-H-E). */
    private fun teamStats(dto: GameCenterResponseDto): List<GameCenterTeamStat> {
        val stats = dto.teamStats.map { GameCenterTeamStat(it.key, it.label, it.away, it.home) }
        val known = stats.mapTo(HashSet()) { it.key }
        val extras = dto.lineScoreExtras.mapNotNull { e ->
            val labelRes = EXTRA_LABELS[e.key] ?: return@mapNotNull null
            if (e.key in known) null else GameCenterTeamStat(e.key, e.label, e.away, e.home, labelRes)
        }
        return stats + extras
    }

    /** Line-score extras are column headers ("H", "E"); as comparison rows they get the full name. */
    private val EXTRA_LABELS = mapOf("hits" to R.string.sports_gc_stat_hits, "errors" to R.string.sports_gc_stat_errors)

    private fun situation(s: GameCenterResponseDto.SituationDto): GameCenterLiveSituation? {
        val football = s.kind == "football"
        val baseball = s.kind == "baseball"
        val out = GameCenterLiveSituation(
            // Period/clock: the event's own status line is refreshed every scoreboard tick and is the
            // source of truth for the clock (no "0:00" at halftime/intermissions from a snapshot).
            outs = s.outs.takeIf { baseball },
            balls = s.balls.takeIf { baseball },
            strikes = s.strikes.takeIf { baseball },
            onFirst = s.onFirst.takeIf { baseball },
            onSecond = s.onSecond.takeIf { baseball },
            onThird = s.onThird.takeIf { baseball },
            possession = side(s.possession).takeIf { football },
            downDistance = s.downDistance.takeIf { football },
            lastPlay = s.lastPlay,
        )
        return out.takeIf { it != GameCenterLiveSituation() }
    }

    /**
     * One leader per category for the glance: the side with the larger headline value ("299" yds vs
     * "268"; "2-3" vs "1-3"; "4.0 IP" vs "3.1 IP"). The value and the backend's one-line context form the
     * stat line. No numbers are computed — only chosen.
     */
    private fun gameLeader(c: GameCenterResponseDto.LeaderCategoryDto): GameCenterLeader? {
        val (leader, side) = when {
            c.away != null && c.home != null -> if (lead(c.home.value) > lead(c.away.value)) c.home to GameCenterSide.HOME else c.away to GameCenterSide.AWAY
            c.away != null -> c.away to GameCenterSide.AWAY
            c.home != null -> c.home to GameCenterSide.HOME
            else -> return null
        }
        return GameCenterLeader(c.key, c.label, leader.player.name, side, leader.summary, safeHeadshot(leader.player.headshotUrl), leader.value)
    }

    private val LEADING_NUMBER = Regex("^-?\\d+(?:\\.\\d+)?")

    private fun lead(value: String?): Double = value?.trim()?.let { LEADING_NUMBER.find(it)?.value?.toDoubleOrNull() } ?: Double.NEGATIVE_INFINITY

    /** Headshots only from a GoatTV image host over https; never a provider image URL. */
    fun safeHeadshot(url: String?): String? =
        url?.trim()?.takeIf { it.startsWith("https://") && !it.contains("espn", ignoreCase = true) }

    private fun side(raw: String?): GameCenterSide? = when (raw) {
        "away" -> GameCenterSide.AWAY
        "home" -> GameCenterSide.HOME
        else -> null
    }

    private fun stats(arr: JSONArray?): List<GameCenterResponseDto.StatDto> = arr.objects().mapNotNull { o ->
        val key = o.str("key") ?: return@mapNotNull null
        GameCenterResponseDto.StatDto(key, o.str("label") ?: return@mapNotNull null, o.str("away"), o.str("home"))
    }

    private fun leader(o: JSONObject?): GameCenterResponseDto.LeaderDto? {
        if (o == null) return null
        val p = o.optJSONObject("player") ?: return null
        val name = p.str("name") ?: return null
        return GameCenterResponseDto.LeaderDto(GameCenterResponseDto.PlayerDto(name, p.str("headshotUrl")), o.str("value"), o.str("summary"))
    }

    private fun boxGroup(g: JSONObject): GameCenterResponseDto.BoxGroupDto? {
        val label = g.str("label") ?: return null
        val columns = g.optJSONArray("columns").objects().mapNotNull { it.str("label") }
        val rows = g.optJSONArray("rows").objects().mapNotNull { r ->
            val name = r.optJSONObject("player")?.str("name") ?: return@mapNotNull null
            val values = r.optJSONArray("values")
            name to (0 until (values?.length() ?: 0)).map { i -> values!!.opt(i)?.takeIf { it != JSONObject.NULL }?.toString() }
        }
        return GameCenterResponseDto.BoxGroupDto(label, columns, rows)
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        if (v !is String && v !is Number) return null
        return v.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.int(key: String): Int? = if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toInt()

    private fun JSONObject.bool(key: String): Boolean? = if (!has(key) || isNull(key)) null else opt(key) as? Boolean
}

/**
 * Production Game Center source: the GoatTV Sports API. One request per call; the HTTP call is cancelled
 * when the caller's coroutine is (focus moved), so a crossed card never keeps a request alive.
 */
class GameCenterApiSource internal constructor(
    private val request: suspend (String) -> SportsApiResult<GameCenterResponseDto>,
) : GameCenterDetailSource {

    constructor(client: SportsApiClient) : this({ id -> client.gameCenter(id) })

    override suspend fun fetch(eventId: String): GameCenterFetch {
        if (!GameCenterApi.EVENT_ID.matches(eventId)) {
            return GameCenterFetch.Loaded(GameCenterDetail(eventId, GameCenterAvailability.UNAVAILABLE))
        }
        return when (val r = request(eventId)) {
            is SportsApiResult.Success -> GameCenterFetch.Loaded(GameCenterApi.toDomain(r.value)).also {
                Log.d(TAG, "game-center $eventId ${it.detail.availability} stale=${it.detail.stale}")
            }
            // Gone / invalid for the API: nothing to show and nothing to retry.
            is SportsApiResult.Rejected -> GameCenterFetch.Loaded(GameCenterDetail(eventId, GameCenterAvailability.UNAVAILABLE))
            is SportsApiResult.RateLimited -> GameCenterFetch.Failed(r.retryAfterSeconds * 1000)
            is SportsApiResult.Unavailable -> GameCenterFetch.Failed()
        }
    }

    private companion object {
        const val TAG = "SportsGameCenter"
    }
}
