package tv.own.owntv.features.sports.live

import tv.own.owntv.BuildConfig

/**
 * Developer-only Game Center fixtures (physical inspection on a TV). The catalog and its switch live
 * in the `debug` source set only; release builds have no implementation, and [active] additionally
 * refuses anything outside a debug build. Customers never see fixture events.
 */
interface SportsGameCenterFixtures {
    val leagues: List<SportsLeague>

    /** Fixture events (ids prefixed [FIXTURE_ID_PREFIX]) relative to [nowMs]. */
    fun events(nowMs: Long): List<SportsEvent>

    fun detail(eventId: String): GameCenterDetail?

    /**
     * Matched-channel refs for a fixture event, built from the user's REAL local channels (so they run
     * through the real [SportsChannelResolver]); null for events the fixtures don't cover.
     */
    suspend fun channelRefs(eventId: String): List<SportsChannelRef>? = null

    companion object {
        const val FIXTURE_ID_PREFIX = "evt_gcfixture_"
    }
}

object SportsGameCenterDevMode {
    /** Installed by the debug source set's fixture installer; never set in release. */
    @Volatile
    var installed: (() -> SportsGameCenterFixtures?)? = null

    /** The fixtures to use, or null (always null outside a debug build). */
    fun active(isDebugBuild: Boolean = BuildConfig.DEBUG): SportsGameCenterFixtures? =
        if (isDebugBuild) installed?.invoke() else null
}

/**
 * Where an event's matched-channel refs come from. Production reads the backend's `channels[]` only
 * while [SportsChannelFeature.ENABLED] (still false: `SPORTS_API_CHANNELS` is off and no `?brand=` is
 * sent), so release resolves nothing. Refs are never trusted as-is: [SportsChannelResolver] verifies
 * each one against the local source before it can be shown or played.
 */
fun interface SportsEventChannelSource {
    suspend fun refs(event: SportsEvent): List<SportsChannelRef>

    companion object {
        val Production = SportsEventChannelSource { event -> if (SportsChannelFeature.ENABLED) event.channels else emptyList() }
    }
}

/** What the Sports events side is wired with: Game Center detail, event channels, optional debug fixtures. */
class SportsGameCenterConfig(
    val detailSource: GameCenterDetailSource,
    val fixtures: SportsGameCenterFixtures?,
    val channelSource: SportsEventChannelSource = SportsEventChannelSource.Production,
    val resolver: SportsChannelResolver? = null,
    /** Event channel actions (Where to Watch, event video, event Multiscreen). Off in release today. */
    val channelsEnabled: Boolean = SportsChannelFeature.ENABLED,
) {
    companion object {
        /**
         * Production: no Game Center endpoint and no event channels yet (event focus does no I/O).
         * Debug fixture mode: the fixture catalog answers detail requests and supplies channel refs
         * for its own events only; every other event keeps the production rules.
         */
        fun create(
            fixtures: SportsGameCenterFixtures? = SportsGameCenterDevMode.active(),
            resolver: SportsChannelResolver? = null,
        ): SportsGameCenterConfig =
            SportsGameCenterConfig(
                detailSource = fixtures?.let { f -> GameCenterDetailSource { id -> f.detail(id) } } ?: GameCenterDetailSource.None,
                fixtures = fixtures,
                channelSource = fixtures?.let { f ->
                    SportsEventChannelSource { e -> f.channelRefs(e.id) ?: SportsEventChannelSource.Production.refs(e) }
                } ?: SportsEventChannelSource.Production,
                resolver = resolver,
                channelsEnabled = resolver != null && (SportsChannelFeature.ENABLED || fixtures != null),
            )
    }
}

internal object SportsGameCenterFixtureMerge {

    /**
     * Shows fixture events at the front of Popular Events (one row to sweep with the D-pad) without
     * touching the real slate's data. With no real slate yet, fixtures alone form the content.
     */
    fun withFixtures(state: SportsLiveState, fixtures: SportsGameCenterFixtures, nowMs: Long): SportsLiveState {
        val events = fixtures.events(nowMs)
        if (events.isEmpty()) return state
        val base = (state as? SportsLiveState.Content)?.slate ?: SportsSlate(
            leagues = emptyList(),
            popularIds = emptyList(),
            eventsById = emptyMap(),
            cursor = "",
            homeLoadedAtMs = nowMs,
            lastSuccessMs = nowMs,
        )
        val knownLeagueIds = base.leagues.mapTo(HashSet()) { it.id }
        val slate = base.copy(
            leagues = base.leagues + fixtures.leagues.filter { it.id !in knownLeagueIds },
            popularIds = events.map { it.id } + base.popularIds.filterNot { it.startsWith(SportsGameCenterFixtures.FIXTURE_ID_PREFIX) },
            eventsById = base.eventsById + events.associateBy { it.id },
        )
        return SportsLiveState.Content(slate, stale = (state as? SportsLiveState.Content)?.stale ?: false)
    }
}
