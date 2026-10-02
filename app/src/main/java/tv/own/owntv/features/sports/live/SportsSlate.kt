package tv.own.owntv.features.sports.live

import androidx.compose.runtime.Immutable

/**
 * The last-known-good Sports slate: what the screen renders. Only ever replaced by a newer successful
 * fetch — a failed refresh never blanks it.
 */
@Immutable
data class SportsSlate(
    val leagues: List<SportsLeague>,
    /** Backend `popular[]` order from the most recent /sports/home. */
    val popularIds: List<String>,
    val eventsById: Map<String, SportsEvent>,
    /** Cursor for the next `/sports/events?since=` (already overlapped by the backend). */
    val cursor: String,
    /** Wall-clock time of the last /sports/home load (drives the periodic full reload). */
    val homeLoadedAtMs: Long,
    /** Wall-clock time of the last successful fetch of any kind. */
    val lastSuccessMs: Long,
    /** Ids supplied by extended per-sport fetches since the last /home load (carry-over candidates). */
    val extendedIds: Set<String> = emptySet(),
)

/**
 * A rendered row, events in display order: Popular Events, one league, or one sport group (Soccer:
 * every enabled competition of the sport in a single row; each card keeps its competition label).
 */
@Immutable
data class SportsEventSection(
    val key: String,
    val title: String,
    /** The row's single league; null for Popular and for sport groups (mixed leagues → per-card label). */
    val league: SportsLeague?,
    val events: List<SportsEvent>,
    /** Sport groups: the competitions present in the row, in backend order (row subtitle). */
    val competitions: List<SportsLeague> = emptyList(),
)

internal object SportsSlateLogic {

    /** Mirrors the backend /sports/home window (now-18h … now+48h, plus anything live). */
    const val DAY_MS = 24 * 3_600_000L
    const val HOME_PAST_MS = 18 * 3_600_000L
    const val HOME_FUTURE_MS = 48 * 3_600_000L

    /**
     * Sports loaded with a longer window than /sports/home (fixtures are days apart, so 48h would
     * leave the row nearly empty), and rendered as ONE row however many competitions are enabled.
     * Keyed by sport, never by competition: newly enabled competitions appear without an app update.
     */
    /**
     * Extended windows per sport (look-ahead from now; the look-back is always [HOME_PAST_MS]).
     *  - soccer 13 days: fixtures are days apart;
     *  - football 7 days: complete weekly slates (Sunday NFL / Saturday NCAAF) even on a Friday,
     *    when /sports/home's 48 h window would show only part of them;
     *  - mma / boxing 13 days: fight cards are a week or more apart.
     * Daily sports (NBA, NCAAB, WNBA, MLB, NHL) keep using /sports/home only.
     * All within the API's 14-day range limit (which also allows the 18 h look-back).
     */
    val EXTENDED_WINDOWS: Map<String, Long> = mapOf(
        "soccer" to 13 * DAY_MS,
        "football" to 7 * DAY_MS,
        "mma" to 13 * DAY_MS,
        "boxing" to 13 * DAY_MS,
    )
    val EXTENDED_SPORTS: Set<String> get() = EXTENDED_WINDOWS.keys

    /** Sports rendered as ONE row across all their competitions (each card keeps its competition label). */
    val GROUPED_SPORTS: Set<String> = setOf("soccer", "mma", "boxing")

    /** Soccer look-ahead (kept for existing callers/tests). */
    const val EXTENDED_FUTURE_MS = 13 * DAY_MS

    /**
     * Preferred row order. Unlisted rows (other leagues/sports) follow in backend league order, so an
     * unknown future league still appears.
     */
    private val PREFERRED_ROW_ORDER = listOf(
        "league:nfl", "league:ncaaf", "league:nba", "sport:soccer", "sport:mma", "sport:boxing",
        "league:nhl", "league:mlb", "league:wnba", "league:ncaab",
    )

    /** Extended-window sports that have at least one enabled league. */
    fun extendedSports(leagues: List<SportsLeague>): List<String> =
        leagues.mapNotNull { it.sport }.filter { it in EXTENDED_SPORTS }.distinct()

    /** Adds/updates events by id (newer-or-equal [SportsEvent.updatedAtMs] wins), then prunes. */
    fun mergeEvents(slate: SportsSlate, events: List<SportsEvent>, nowMs: Long): SportsSlate {
        if (events.isEmpty()) return slate
        val merged = LinkedHashMap(slate.eventsById)
        for (incoming in events) {
            val existing = merged[incoming.id]
            if (existing == null || incoming.updatedAtMs >= existing.updatedAtMs) merged[incoming.id] = incoming
        }
        return slate.copy(eventsById = prune(merged, nowMs))
    }

    /** Keeps [previous]'s events of [sport] that [fresh] lacks (an extended fetch failed: last-known-good). */
    fun carryOverSport(fresh: SportsSlate, previous: SportsSlate?, sport: String, nowMs: Long): SportsSlate {
        if (previous == null) return fresh
        val sportLeagues = (fresh.leagues + previous.leagues).filter { it.sport == sport }.map { it.id }.toSet()
        // Only what the previous EXTENDED fetch supplied: /home is authoritative for its own events, so an
        // event it no longer lists is never resurrected by a failed extended fetch.
        val kept = previous.eventsById.values.filter {
            it.id in previous.extendedIds && it.leagueId in sportLeagues && it.id !in fresh.eventsById
        }
        return withExtended(mergeEvents(fresh, kept, nowMs), kept)
    }

    /** Merge an extended per-sport page and remember its ids (for carry-over if a later fetch fails). */
    fun mergeExtended(slate: SportsSlate, events: List<SportsEvent>, nowMs: Long): SportsSlate =
        withExtended(mergeEvents(slate, events, nowMs), events)

    private fun withExtended(slate: SportsSlate, events: List<SportsEvent>): SportsSlate =
        if (events.isEmpty()) slate else slate.copy(extendedIds = slate.extendedIds + events.map { it.id }.filter { it in slate.eventsById })

    fun fromHome(home: SportsHomePayload, nowMs: Long): SportsSlate = SportsSlate(
        leagues = home.leagues,
        popularIds = home.popular.distinct(),
        eventsById = home.events.associateBy { it.id },
        cursor = home.cursor,
        homeLoadedAtMs = nowMs,
        lastSuccessMs = nowMs,
    )

    /**
     * Merge an incremental `/sports/events?since=` page. The backend cursor deliberately overlaps
     * (30s), so the same event may arrive again: events are keyed by their stable id and an incoming
     * copy only replaces the stored one when it is not older ([SportsEvent.updatedAtMs]). New events
     * are added; events that aged out of the home window are pruned.
     */
    fun mergeDelta(slate: SportsSlate, delta: SportsEventsPayload, nowMs: Long): SportsSlate {
        val merged = LinkedHashMap(slate.eventsById)
        for (incoming in delta.events) {
            val existing = merged[incoming.id]
            if (existing == null || incoming.updatedAtMs >= existing.updatedAtMs) merged[incoming.id] = incoming
        }
        return slate.copy(
            eventsById = prune(merged, nowMs),
            cursor = delta.cursor,
            lastSuccessMs = nowMs,
        )
    }

    /**
     * Drop events the backend window no longer covers. The since-feed never reports removals, so an
     * event that started more than 18h ago (and isn't still live/delayed) is removed client-side.
     */
    fun prune(events: Map<String, SportsEvent>, nowMs: Long): Map<String, SportsEvent> =
        events.filterValues { e ->
            e.status == SportsEventStatus.LIVE || e.status == SportsEventStatus.DELAYED ||
                e.startTimeMs >= nowMs - HOME_PAST_MS
        }

    /**
     * Popular Events (backend `popular[]` order, ids that no longer exist skipped) followed by one row
     * per league in backend league order. Leagues with no events are omitted. Within a league: live
     * first, then upcoming by start time, then finished (most recent first).
     */
    fun sections(
        slate: SportsSlate,
        query: String,
        popularTitle: String,
        /** Localized row titles for [GROUPED_SPORTS] (sport → title); missing → capitalized sport id. */
        sportTitles: Map<String, String> = emptyMap(),
    ): List<SportsEventSection> {
        val knownLeagues = slate.leagues.associateBy { it.id }
        val matches: (SportsEvent) -> Boolean =
            if (query.isBlank()) { _ -> true } else { e -> matchesQuery(e, query, knownLeagues[e.leagueId]) }
        val out = ArrayList<SportsEventSection>()
        val popular = slate.popularIds.mapNotNull { slate.eventsById[it] }.filter(matches)
        if (popular.isNotEmpty()) out += SportsEventSection(POPULAR_KEY, popularTitle, null, popular)

        val byLeague = slate.eventsById.values.filter(matches).groupBy { it.leagueId }
        val orderedLeagueIds = slate.leagues.map { it.id } + byLeague.keys.filter { it !in knownLeagues }.sorted()
        val rows = ArrayList<SportsEventSection>()
        val groups = LinkedHashMap<String, MutableList<String>>() // sport → league ids in backend order
        for (leagueId in orderedLeagueIds) {
            val events = byLeague[leagueId].orEmpty()
            if (events.isEmpty()) continue
            val league = knownLeagues[leagueId]
            val sport = league?.sport
            if (sport != null && sport in GROUPED_SPORTS) {
                groups.getOrPut(sport) {
                    rows += SportsEventSection("sport:$sport", "", null, emptyList()) // filled below
                    ArrayList()
                } += leagueId
                continue
            }
            rows += SportsEventSection("league:$leagueId", league?.shortName ?: leagueId.uppercase(), league, events.sortedWith(LEAGUE_ROW_ORDER))
        }
        val filled = rows.map { row ->
            if (!row.key.startsWith("sport:")) return@map row
            val sport = row.key.removePrefix("sport:")
            val leagueIds = groups.getValue(sport)
            SportsEventSection(
                key = row.key,
                title = sportTitles[sport] ?: sport.replaceFirstChar { it.uppercase() },
                league = null,
                events = leagueIds.flatMap { byLeague[it].orEmpty() }.sortedWith(LEAGUE_ROW_ORDER),
                competitions = leagueIds.mapNotNull { knownLeagues[it] },
            )
        }
        val rank = { key: String -> PREFERRED_ROW_ORDER.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it } }
        // Stable: preferred rows in the preferred order, then every other row in backend order.
        out += filled.withIndex()
            .sortedWith(compareBy<IndexedValue<SportsEventSection>> { rank(it.value.key) }.thenBy { it.index })
            .map { it.value }
        return out
    }

    private val LEAGUE_ROW_ORDER: Comparator<SportsEvent> = compareBy<SportsEvent> {
        when (it.status) {
            SportsEventStatus.LIVE, SportsEventStatus.DELAYED -> 0
            SportsEventStatus.SCHEDULED, SportsEventStatus.UNKNOWN -> 1
            SportsEventStatus.POSTPONED -> 2
            SportsEventStatus.FINAL, SportsEventStatus.CANCELED -> 3
        }
    }.thenComparator { a, b ->
        // upcoming: soonest first; finished: most recent first
        if (a.status == SportsEventStatus.FINAL || a.status == SportsEventStatus.CANCELED) b.startTimeMs.compareTo(a.startTimeMs)
        else a.startTimeMs.compareTo(b.startTimeMs)
    }.thenBy { it.id }

    /**
     * Search across team name / short name / abbreviation, event title and competition name / short
     * name (case-insensitive; every term must match).
     */
    fun matchesQuery(e: SportsEvent, query: String, league: SportsLeague? = null): Boolean {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = buildList {
            add(e.title)
            for (t in listOfNotNull(e.home, e.away)) { add(t.name); add(t.shortName); add(t.abbreviation) }
            add(league?.name); add(league?.shortName)
        }.filterNotNull().joinToString(" ").lowercase()
        return terms.all { haystack.contains(it) }
    }

    const val POPULAR_KEY = "popular"
}
