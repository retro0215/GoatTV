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

/** What the Sports events side is wired with: the detail source plus optional debug fixtures. */
class SportsGameCenterConfig(
    val detailSource: GameCenterDetailSource,
    val fixtures: SportsGameCenterFixtures?,
) {
    companion object {
        /**
         * Production: no Game Center endpoint yet (event focus does no I/O). Debug fixture mode: the
         * fixture catalog answers detail requests for its own events only.
         */
        fun create(fixtures: SportsGameCenterFixtures? = SportsGameCenterDevMode.active()): SportsGameCenterConfig =
            SportsGameCenterConfig(
                detailSource = fixtures?.let { f -> GameCenterDetailSource { id -> f.detail(id) } } ?: GameCenterDetailSource.None,
                fixtures = fixtures,
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
