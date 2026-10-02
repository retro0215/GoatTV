package tv.own.owntv.features.sports.live

import org.json.JSONArray
import org.json.JSONObject

/** JSON fixtures in the exact shape of the production Sports API (team objects are flat). */
internal object SportsFixtures {

    fun team(id: String, name: String, short: String, abbr: String, score: Any? = null, logo: String? = "https://a.espncdn.com/i/teamlogos/x/500/$abbr.png") =
        JSONObject().apply {
            put("id", id); put("name", name); put("shortName", short); put("abbreviation", abbr)
            put("logoUrl", logo ?: JSONObject.NULL); put("score", score ?: JSONObject.NULL)
        }

    fun broadcast(name: String, type: String = "TV", market: String = "NATIONAL", key: String? = name.lowercase()) =
        JSONObject().apply { put("name", name); put("type", type); put("market", market); put("networkKey", key ?: JSONObject.NULL) }

    fun event(
        id: String,
        league: String = "nfl",
        status: String = "SCHEDULED",
        start: String = "2026-10-04T17:00:00Z",
        updated: String = "2026-10-02T12:00:00.000Z",
        home: JSONObject? = team("$league.phi", "Philadelphia Eagles", "Eagles", "PHI"),
        away: JSONObject? = team("$league.lar", "Los Angeles Rams", "Rams", "LAR"),
        title: String? = null,
        broadcasts: List<JSONObject> = listOf(broadcast("FOX")),
        statusDetail: String? = null,
        priority: Int = 50,
        channels: JSONArray? = null,
    ) = JSONObject().apply {
        put("id", id); put("leagueId", league); put("status", status); put("startTime", start); put("updatedAt", updated)
        put("home", home ?: JSONObject.NULL); put("away", away ?: JSONObject.NULL); put("title", title ?: JSONObject.NULL)
        put("broadcasts", JSONArray(broadcasts)); put("statusDetail", statusDetail ?: JSONObject.NULL)
        put("venueName", "Lincoln Financial Field"); put("priority", priority)
        if (channels != null) put("channels", channels)
    }

    val LEAGUES = JSONArray(
        listOf(
            JSONObject("""{"id":"nfl","name":"NFL","order":1,"sport":"football","shortName":"NFL","participantKind":"team"}"""),
            JSONObject("""{"id":"ncaaf","name":"College Football","order":2,"sport":"football","shortName":"NCAAF","participantKind":"team"}"""),
            JSONObject("""{"id":"nhl","name":"NHL","order":7,"sport":"hockey","shortName":"NHL","participantKind":"team"}"""),
        ),
    )

    fun home(events: List<JSONObject>, popular: List<String>, cursor: String = "2026-10-02T12:00:00.000Z") = JSONObject().apply {
        put("generatedAt", "2026-10-02T12:00:30.000Z"); put("cursor", cursor)
        put("leagues", LEAGUES); put("popular", JSONArray(popular)); put("events", JSONArray(events))
    }.toString()

    fun page(events: List<JSONObject>, cursor: String = "2026-10-02T12:05:00.000Z") =
        JSONObject().apply { put("cursor", cursor); put("events", JSONArray(events)) }.toString()

    fun parsed(o: JSONObject): SportsEvent = SportsApiParser.parseEvent(o)!!
}
