@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package tv.own.owntv.features.search

import tv.own.owntv.core.epg.displayLogoUrl
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelSearchResult
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.database.entity.playStreamUrl
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.player.OwnTVPlayer

/** Combined results of a global query (each list bounded). */
@Immutable
data class SearchResults(
    val channels: List<tv.own.owntv.core.database.dao.ChannelSearchResult> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    /** The (trimmed) query these results answer; "" for none — lets the UI tell "searching" from "nothing". */
    val query: String = "",
    /** Tabbed Search only: how many results each category has, and whether more may exist (see [CategoryCount]). */
    val liveCount: CategoryCount = CategoryCount(),
    val movieCount: CategoryCount = CategoryCount(),
    val seriesCount: CategoryCount = CategoryCount(),
    /** The search failed (e.g. a database error) — shown as an error state, never a crash. */
    val error: Boolean = false,
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/** The tabbed results' categories (navigation Phase C), in on-screen order. */
enum class SearchTab { LIVE, MOVIES, SERIES }

/**
 * One category's count after every visibility filter (hidden items, hidden categories, Kids).
 * [visible] is how many visible results were found; [more] is true when the scan stopped at its row limit,
 * so further matches may exist beyond what was looked at.
 */
data class CategoryCount(val visible: Int = 0, val more: Boolean = false)

/**
 * Bounded, honest result counts for the tabbed Search (navigation Phases C/D).
 *
 * Visibility filters run in Kotlin after the SQL limit (hidden items are keyed by provider id/name, which
 * SQL cannot see), so a plain `LIMIT 501` cannot tell "500 visible" from "500 rows, 200 of them hidden".
 * So: one pass of [TAB_CAP] + 1 rows; if that pass was full but filtering left [TAB_CAP] or fewer visible,
 * ONE refill pass of [REFILL_LIMIT] + 1 rows. Never more — at most ~2,000 rows per category, three
 * categories, only for broad queries that also hit hidden content. The tab then reads:
 *  - "N"    — the scan reached the end of the matches: N is exact;
 *  - "N+"   — the scan stopped at its limit with N (≤ 500) visible: at least N, maybe more;
 *  - "500+" — more than 500 visible (at most 500 are listed).
 * The one unavoidable uncertainty is the "N+" case: beyond [REFILL_LIMIT] rows the true total is unknown,
 * and the label says so instead of guessing.
 */
object SearchCounts {
    const val TAB_CAP = 500
    const val REFILL_LIMIT = 2_000

    /** What a tab shows: [number], with a "+" when [plus]. */
    data class Label(val number: Int, val plus: Boolean)

    fun label(c: CategoryCount): Label = when {
        c.visible > TAB_CAP -> Label(TAB_CAP, plus = true)
        c.more -> Label(c.visible, plus = true)
        else -> Label(c.visible, plus = false)
    }

    /**
     * Fetch and filter one category. [fetch] runs the DAO query with a row limit; [filter] applies the
     * visibility rules and renames. The old mixed layout ([tabbed] false) keeps its single [legacyLimit]
     * query and is never counted.
     */
    suspend fun <R, T> collect(
        tabbed: Boolean,
        legacyLimit: Int,
        fetch: suspend (limit: Int) -> List<R>,
        filter: (List<R>) -> List<T>,
    ): Pair<List<T>, CategoryCount> {
        if (!tabbed) {
            val rows = filter(fetch(legacyLimit))
            return rows to CategoryCount(rows.size, more = false)
        }
        var limit = TAB_CAP + 1
        var raw = fetch(limit)
        var visible = filter(raw)
        if (raw.size >= limit && visible.size <= TAB_CAP) {
            // A full first pass that filtering thinned out: look further, once, within a fixed bound.
            limit = REFILL_LIMIT + 1
            raw = fetch(limit)
            visible = filter(raw)
        }
        return visible.take(TAB_CAP) to CategoryCount(visible.size, more = raw.size >= limit)
    }
}

/**
 * The query → results pipeline, unchanged in behaviour and pulled out so it is testable: trimmed, 300 ms
 * debounce, duplicate queries ignored, the previous search cancelled by a newer query (flatMapLatest), under
 * two characters = no search. A failing search becomes an error result instead of tearing the flow down.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun searchResultsFlow(
    queries: Flow<String>,
    /** The profile/playlist scope: a change re-runs the current query at once, so results never go stale. */
    scope: Flow<Any?> = flowOf(null),
    debounceMs: Long = 300,
    search: suspend (String) -> SearchResults,
): Flow<SearchResults> = combine(
    queries.map { it.trim() }.debounce(debounceMs).distinctUntilChanged(),
    scope.distinctUntilChanged(),
) { q, sc -> q to sc }
    .distinctUntilChanged()
    .flatMapLatest { (q, _) ->
        if (q.length < 2) flowOf(SearchResults())
        else flow {
            val result = try {
                search(q)
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (t: Throwable) {
                SearchResults(query = q, error = true)
            }
            emit(result)
        }
    }

/** Batch 5 — empty-state launcher intents. All bounded (favourites / recent history). */
enum class SearchIntent {
    CONTINUE,
    UNWATCHED,
    CHANNELS,
}

/** Phase 11 — cross-section search over a profile's channels, movies and series. */
class SearchViewModel(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val historyDao: HistoryDao,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val favoriteDao: FavoriteDao,
    val player: OwnTVPlayer,
    private val externalPlayerLauncher: tv.own.owntv.core.player.ExternalPlayerLauncher,
    private val streamUrlResolver: tv.own.owntv.core.stalker.StreamUrlResolver,
) : ViewModel() {

    /** Global "External player" toggle — the screen must NOT open the fullscreen in-app player when on. */
    val externalPlayerOn: kotlinx.coroutines.flow.StateFlow<Boolean> = settings.externalPlayerMovies
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private data class Ctx(
        val profileId: Long,
        val liveSourceIds: List<Long>,
        val movieSourceIds: List<Long>,
        val seriesSourceIds: List<Long>,
    ) {
        val hasAny: Boolean get() = liveSourceIds.isNotEmpty() || movieSourceIds.isNotEmpty() || seriesSourceIds.isNotEmpty()
    }
    // Observe the active profile's sources reactively so adding/removing a playlist refreshes Search
    // immediately (was read once at startup, so a new playlist showed nothing until app restart).
    // Per-section Off flags split the id sets so an Off section never surfaces in results.
    private val ctx: StateFlow<Ctx> = activeProfileSources(settings, sourceDao)
        .map { aps -> Ctx(aps.profileId, aps.liveSourceIds, aps.movieSourceIds, aps.seriesSourceIds) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Ctx(-1L, emptyList(), emptyList(), emptyList()))

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val results: StateFlow<SearchResults> = searchResultsFlow(_query, scope = ctx) { q -> search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /**
     * Tabbed Search: the selected category. Live when Search opens (the shell clears the query on entry) and
     * whenever the query is cleared; a tab the customer picked stays while they keep typing and across a
     * details round trip; a profile or playlist change starts over on Live with freshly searched results.
     */
    private val _tab = MutableStateFlow(SearchTab.LIVE)
    val tab: StateFlow<SearchTab> = _tab.asStateFlow()
    fun selectTab(t: SearchTab) { _tab.value = t }

    init {
        viewModelScope.launch {
            var last: Ctx? = null
            ctx.collect { c ->
                if (last != null && last != c) _tab.value = SearchTab.LIVE
                last = c
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        if (q.isNotBlank()) _intent.value = null // typing overrides an active launcher intent
        if (q.isBlank()) _tab.value = SearchTab.LIVE // cleared: the next search starts on Live
    }

    // --- Batch 5: empty-state launcher (recent search terms + Continue / Unwatched / Channels) ---

    /** Persisted recent search terms (most-recent first). */
    val recentSearches: StateFlow<List<String>> = settings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearRecentSearches() { viewModelScope.launch { settings.clearRecentSearches() } }

    /** Save the current query into recents — called when the user actually opens a result. */
    fun rememberCurrentQuery() { viewModelScope.launch { settings.addRecentSearch(_query.value) } }

    private val _intent = MutableStateFlow<SearchIntent?>(null)
    val intent: StateFlow<SearchIntent?> = _intent.asStateFlow()

    /** Selecting an intent clears any typed query so the curated list shows; null returns to the launcher. */
    fun setIntent(i: SearchIntent?) {
        _intent.value = i
        if (i != null) _query.value = ""
    }

    /** Curated results for the active empty-state intent (bounded; reuses favourites/history queries). */
    val curatedResults: StateFlow<SearchResults> = combine(_intent, ctx) { i, c -> i to c }
        .flatMapLatest { (i, c) ->
            if (i == null || c.profileId < 0 || !c.hasAny) flowOf(SearchResults())
            else flowOf(loadIntent(i, c))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    private suspend fun loadIntent(intent: SearchIntent, c: Ctx): SearchResults = when (intent) {
        SearchIntent.CONTINUE -> SearchResults(
            channels = channelDao.recentlyWatched(c.profileId, LIMIT).first()
                .filter { it.sourceId in c.liveSourceIds }
                .map { ChannelSearchResult(it, null) },
            movies = if (c.movieSourceIds.isEmpty()) emptyList()
            else movieDao.recentlyWatchedSnapshot(c.profileId, c.movieSourceIds, LIMIT),
            series = if (c.seriesSourceIds.isEmpty()) emptyList()
            else seriesDao.recentlyWatchedSnapshot(c.profileId, c.seriesSourceIds, LIMIT),
        )
        SearchIntent.UNWATCHED -> SearchResults(
            movies = if (c.movieSourceIds.isEmpty()) emptyList()
            else movieDao.unwatchedFavorites(c.profileId, c.movieSourceIds, LIMIT),
            series = if (c.seriesSourceIds.isEmpty()) emptyList()
            else seriesDao.unwatchedFavorites(c.profileId, c.seriesSourceIds, LIMIT),
        )
        SearchIntent.CHANNELS -> SearchResults(
            channels = channelDao.favoritesListAlpha(c.profileId).first()
                .filter { it.sourceId in c.liveSourceIds }
                .take(LIMIT)
                .map { ChannelSearchResult(it, null) },
        )
    }

    /**
     * Builds a sanitized FTS4 MATCH expression from user text: each whitespace-separated token is
     * stripped to letters/digits and turned into a prefix term ("harry pot" → "harry* pot*", implicit
     * AND). Returns null when nothing tokenizable remains (symbols-only input) — caller falls back to
     * the substring LIKE queries. Prefix-token semantics differ slightly from substring LIKE
     * (matches word starts, not mid-word substrings), which is the accepted trade-off for an
     * index-served search over ~220k rows per keystroke (A3).
     */
    private fun ftsQueryFor(q: String): String? {
        val tokens = q.split(Regex("\\s+"))
            .map { t -> t.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    // The tabbed layout is GoatTV-only while it is validated; the other brands keep the 40-per-section list.
    private val tabbed: Boolean get() = tv.own.owntv.features.shell.MainSection.favoritesNavEnabled

    /** Where searches run: off the main thread (up to three 501-row queries and their filtering on a large
     *  playlist). Internal so a test can run it on its own scheduler; never changed in the app. */
    internal var workDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

    private suspend fun search(q: String): SearchResults = kotlinx.coroutines.withContext(workDispatcher) {
        searchNow(q)
    }

    private suspend fun searchNow(q: String): SearchResults {
        val pid = currentProfileId() ?: return SearchResults(query = q)
        val c = ctx.value
        if (!c.hasAny) return SearchResults(query = q)
        val fts = ftsQueryFor(q)
        // Respect this profile's customizations: hidden items and hidden categories never surface,
        // renames are shown (channels, and bulk-renamed movies/series from Customize).
        val custLive = customize.observe(pid, MediaType.LIVE).first()
        val custMovie = customize.observe(pid, MediaType.MOVIE).first()
        val custSeries = customize.observe(pid, MediaType.SERIES).first()
        val isKidsProfile = profileDao.getById(pid)?.isKids == true
        val hiddenLiveCats = hiddenCategoryIds(c.liveSourceIds, MediaType.LIVE, custLive, isKidsProfile)
        val hiddenMovieCats = hiddenCategoryIds(c.movieSourceIds, MediaType.MOVIE, custMovie, isKidsProfile)
        val hiddenSeriesCats = hiddenCategoryIds(c.seriesSourceIds, MediaType.SERIES, custSeries, isKidsProfile)
        val (channels, liveCount) = if (c.liveSourceIds.isEmpty()) emptyList<ChannelSearchResult>() to CategoryCount()
            else SearchCounts.collect(
                tabbed, LIMIT,
                fetch = { lim -> if (fts != null) channelDao.searchListDetailedFts(fts, c.liveSourceIds, lim) else channelDao.searchListDetailed(q, c.liveSourceIds, lim) },
                filter = { rows ->
                    rows.filter {
                        CustomizeKeys.channel(it.channel) !in custLive.hiddenItems &&
                            (it.channel.categoryId == null || it.channel.categoryId !in hiddenLiveCats)
                    }.map { row -> custLive.itemNames[CustomizeKeys.channel(row.channel)]?.let { row.copy(channel = row.channel.copy(name = it)) } ?: row }
                },
            )
        val (movies, movieCount) = if (c.movieSourceIds.isEmpty()) emptyList<MovieEntity>() to CategoryCount()
            else SearchCounts.collect(
                tabbed, LIMIT,
                fetch = { lim -> if (fts != null) movieDao.searchListFts(fts, c.movieSourceIds, lim) else movieDao.searchList(q, c.movieSourceIds, lim) },
                filter = { rows ->
                    rows.filter {
                        CustomizeKeys.movie(it) !in custMovie.hiddenItems &&
                            (it.categoryId == null || it.categoryId !in hiddenMovieCats)
                    }.map { m -> custMovie.itemNames[CustomizeKeys.movie(m)]?.let { m.copy(name = it) } ?: m }
                },
            )
        val (series, seriesCount) = if (c.seriesSourceIds.isEmpty()) emptyList<SeriesEntity>() to CategoryCount()
            else SearchCounts.collect(
                tabbed, LIMIT,
                fetch = { lim -> if (fts != null) seriesDao.searchListFts(fts, c.seriesSourceIds, lim) else seriesDao.searchList(q, c.seriesSourceIds, lim) },
                filter = { rows ->
                    rows.filter {
                        CustomizeKeys.series(it) !in custSeries.hiddenItems &&
                            (it.categoryId == null || it.categoryId !in hiddenSeriesCats)
                    }.map { sr -> custSeries.itemNames[CustomizeKeys.series(sr)]?.let { sr.copy(name = it) } ?: sr }
                },
            )
        return SearchResults(
            channels = channels, movies = movies, series = series, query = q,
            liveCount = liveCount, movieCount = movieCount, seriesCount = seriesCount,
        )
    }

    /** DB ids of this profile's hidden categories for [type] (so hidden groups drop out of search too). */
    private suspend fun hiddenCategoryIds(
        sourceIds: List<Long>,
        type: MediaType,
        cust: tv.own.owntv.core.customize.SectionCustomizations,
        isKidsProfile: Boolean,
    ): Set<Long> {
        if (cust.hiddenCategories.isEmpty() && !isKidsProfile) return emptySet()
        return tv.own.owntv.core.content.AdultCategoryClassifier.hiddenCategoryIds(
            categoryDao.observe(sourceIds, type).first(),
            cust.hiddenCategories,
            isKidsProfile,
        )
    }

    /** Live channels this profile has favourited — so a search result can show a star and toggle it. */
    val favoriteChannelIds: StateFlow<Set<Long>> = ctx
        .flatMapLatest { favoriteDao.observeFavoriteIds(it.profileId, MediaType.LIVE) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Long-press a channel result to add/remove it from Favorites (no need to open Live TV first). */
    fun toggleFavoriteChannel(channel: ChannelEntity) {
        viewModelScope.launch {
            val pid = ctx.value.profileId
            if (pid < 0) return@launch
            if (favoriteChannelIds.value.contains(channel.id)) {
                favoriteDao.remove(pid, MediaType.LIVE, channel.id)
            } else {
                favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = MediaType.LIVE, itemId = channel.id))
            }
        }
    }

    /** The non-playback half of tuning a channel result: watch history + the recent-searches entry.
     *  The shell calls this and then hands playback to LiveViewModel's shared path (F05), so a channel
     *  opened from Search gets the same engine routing as one opened from Live TV. */
    fun noteChannelPlayed(channel: ChannelEntity) {
        record(MediaType.LIVE, channel.id)
        rememberCurrentQuery()
    }

    // A second live-start path used to live here as a "standalone host" fallback: mpv straight away, no
    // ExoPlayer-first ladder, no per-channel engine pin, no learned quirks. It was unreachable in the app
    // (the shell always supplies the callback) but it was a standing invitation for the two paths to
    // drift, so SearchScreen now REQUIRES the shared callback and this copy is gone.

    // Films found here play through MovieViewModel (the shell passes SearchScreen its playMovie): the one
    // movie path, with the saved position, progress saving, history and Stalker reconnect. The copy that
    // used to live here had none of those.

    private fun record(type: MediaType, itemId: Long) {
        viewModelScope.launch {
            val pid = currentProfileId() ?: return@launch
            runCatching {
                historyDao.record(WatchHistoryEntity(profileId = pid, mediaType = type, itemId = itemId))
            }.onFailure { t ->
                Log.w(TAG, "record history failed profile=$pid type=$type itemId=$itemId", t)
            }
        }
    }

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
    }

    private companion object {
        const val TAG = "OwnTVHome"
        const val LIMIT = 40
    }
}
