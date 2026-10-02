package tv.own.owntv.features.sports.live

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parser for the Sports API JSON (org.json, like the other API parsers). Tolerant by design:
 * unknown fields are ignored, a malformed event is dropped on its own instead of failing the whole
 * payload, and a payload that isn't the expected JSON object yields null (→ "temporarily unavailable").
 */
internal object SportsApiParser {

    fun parseHome(body: String): SportsHomePayload? = runCatching {
        val root = JSONObject(body)
        val cursor = root.optStringOrNull("cursor") ?: return null
        val events = parseEvents(root.optJSONArray("events")) ?: return null
        SportsHomePayload(
            generatedAtMs = parseIsoUtcMs(root.optStringOrNull("generatedAt")) ?: 0L,
            cursor = cursor,
            leagues = parseLeagues(root.optJSONArray("leagues")),
            popular = root.optJSONArray("popular").strings(),
            events = events,
        )
    }.getOrNull()

    fun parseEventsPage(body: String): SportsEventsPayload? = runCatching {
        val root = JSONObject(body)
        val cursor = root.optStringOrNull("cursor") ?: return null
        val events = parseEvents(root.optJSONArray("events")) ?: return null
        SportsEventsPayload(cursor = cursor, events = events)
    }.getOrNull()

    fun parseLeaguesPage(body: String): List<SportsLeague>? = runCatching {
        val arr = JSONObject(body).optJSONArray("leagues") ?: return null
        parseLeagues(arr)
    }.getOrNull()

    /** `{"error": "…"}` bodies the API sends with 4xx/5xx; null when absent or not JSON. */
    fun parseError(body: String): String? = runCatching { JSONObject(body).optStringOrNull("error") }.getOrNull()

    private fun parseLeagues(arr: JSONArray?): List<SportsLeague> {
        if (arr == null) return emptyList()
        val out = ArrayList<SportsLeague>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optStringOrNull("id") ?: continue
            val name = o.optStringOrNull("name") ?: id.uppercase()
            out += SportsLeague(
                id = id,
                name = name,
                shortName = o.optStringOrNull("shortName") ?: name,
                order = o.optInt("order", Int.MAX_VALUE),
                sport = o.optStringOrNull("sport"),
                participantKind = o.optStringOrNull("participantKind"),
                competitionType = o.optStringOrNull("competitionType"),
                region = o.optStringOrNull("region"),
                gender = o.optStringOrNull("gender"),
            )
        }
        return out.sortedWith(compareBy<SportsLeague> { it.order }.thenBy { it.id })
    }

    /** Null only when [arr] is missing (the payload is not what we expect). */
    private fun parseEvents(arr: JSONArray?): List<SportsEvent>? {
        if (arr == null) return null
        val out = ArrayList<SportsEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseEvent(o)?.let { out += it }
        }
        return out
    }

    internal fun parseEvent(o: JSONObject): SportsEvent? {
        val id = o.optStringOrNull("id") ?: return null
        val leagueId = o.optStringOrNull("leagueId") ?: return null
        val start = parseIsoUtcMs(o.optStringOrNull("startTime")) ?: return null
        val home = o.optJSONObject("home")?.let(::parseTeam)
        val away = o.optJSONObject("away")?.let(::parseTeam)
        val title = o.optStringOrNull("title")
        // An event with neither participants nor a title has nothing to render.
        if (home == null && away == null && title == null) return null
        return SportsEvent(
            id = id,
            leagueId = leagueId,
            title = title,
            away = away,
            home = home,
            startTimeMs = start,
            status = SportsEventStatus.parse(o.optStringOrNull("status")),
            statusDetail = o.optStringOrNull("statusDetail"),
            venueName = o.optStringOrNull("venueName"),
            priority = o.optInt("priority", 0),
            broadcasts = parseBroadcasts(o.optJSONArray("broadcasts")),
            updatedAtMs = parseIsoUtcMs(o.optStringOrNull("updatedAt")) ?: 0L,
            channels = parseChannels(o.optJSONArray("channels")),
        )
    }

    private fun parseTeam(o: JSONObject): SportsTeam? {
        val name = o.optStringOrNull("name") ?: o.optStringOrNull("shortName") ?: return null
        return SportsTeam(
            id = o.optStringOrNull("id"),
            name = name,
            shortName = o.optStringOrNull("shortName"),
            abbreviation = o.optStringOrNull("abbreviation"),
            logoUrl = o.optStringOrNull("logoUrl")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            // Scores are strings; tolerate a numeric score from a future API version.
            score = when (val s = o.opt("score")) {
                null, JSONObject.NULL -> null
                is Number -> s.toString()
                else -> s.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
            },
        )
    }

    private fun parseBroadcasts(arr: JSONArray?): List<SportsBroadcast> {
        if (arr == null) return emptyList()
        val out = ArrayList<SportsBroadcast>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optStringOrNull("name") ?: continue
            out += SportsBroadcast(
                name = name,
                type = o.optStringOrNull("type") ?: "TV",
                market = o.optStringOrNull("market") ?: "NATIONAL",
                networkKey = o.optStringOrNull("networkKey"),
            )
        }
        return out
    }

    /** Backend order is the ranking — preserved exactly (primary feeds first, 4K/UHD alternates after). */
    private fun parseChannels(arr: JSONArray?): List<SportsChannelRef> {
        if (arr == null) return emptyList()
        val out = ArrayList<SportsChannelRef>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val channelId = o.optStringOrNull("channelId") ?: continue
            val remoteId = o.optStringOrNull("remoteId") ?: continue
            val name = o.optStringOrNull("name") ?: continue
            out += SportsChannelRef(
                channelId = channelId,
                remoteId = remoteId,
                epgChannelId = o.optStringOrNull("epgChannelId"),
                name = name,
                category = o.optStringOrNull("category"),
                logoUrl = o.optStringOrNull("logoUrl"),
                networkKey = o.optStringOrNull("networkKey"),
                confidence = o.optInt("confidence", 0),
                reason = o.optStringOrNull("reason") ?: "",
            )
        }
        return out
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val v = opt(i)
            if (v is String && v.isNotBlank()) out += v
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})$""")

    /**
     * ISO-8601 instant → epoch millis, without java.time (minSdk 24, no desugaring). Accepts the API's
     * `…Z` forms with or without milliseconds, plus explicit offsets. Null when unparseable.
     */
    fun parseIsoUtcMs(raw: String?): Long? {
        val m = ISO.matchEntire(raw?.trim() ?: return null) ?: return null
        val (y, mo, d, h, mi) = m.destructured.toList().take(5).map { it.toInt() }
        val s = m.groupValues[6].ifEmpty { "0" }.toInt()
        val frac = m.groupValues[7]
        val millis = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
        if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || s > 60) return null
        val days = daysFromCivil(y, mo, d)
        var ms = (((days * 24 + h) * 60 + mi) * 60 + s) * 1000L + millis
        val zone = m.groupValues[8]
        if (zone != "Z") {
            val sign = if (zone.startsWith("-")) -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val offsetMin = digits.take(2).toInt() * 60 + digits.drop(2).toInt()
            ms -= sign * offsetMin * 60_000L
        }
        return ms
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}
