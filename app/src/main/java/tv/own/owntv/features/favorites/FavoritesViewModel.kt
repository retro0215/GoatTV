package tv.own.owntv.features.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.features.settings.data.SettingsRepository

/** The Favorites screen's tabs, in their on-screen order. */
enum class FavoritesTab { LIVE, MOVIES, SERIES }

/** What the Favorites screen shows. Lists are already filtered, de-duplicated and renamed. */
data class FavoritesState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val live: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
)

/**
 * The visibility rules every list in the app applies, in one pure place: hidden items, hidden categories
 * (profile customization + the Kids-profile adult policy, already folded into [hiddenCategoryIds]) and
 * renames from Customize. Duplicate favorite rows for one item collapse to one entry.
 */
object FavoritesFilter {
    fun <T> visible(
        items: List<T>,
        id: (T) -> Long,
        key: (T) -> String,
        categoryId: (T) -> Long?,
        customizations: SectionCustomizations,
        hiddenCategoryIds: Set<Long>,
        rename: (T, String) -> T,
    ): List<T> = items.asSequence()
        .distinctBy(id)
        .filter { key(it) !in customizations.hiddenItems }
        .filter { item -> categoryId(item).let { it == null || it !in hiddenCategoryIds } }
        .map { item -> customizations.itemNames[key(item)]?.let { rename(item, it) } ?: item }
        .toList()

    fun hiddenCategoryIds(categories: List<CategoryEntity>, customizations: SectionCustomizations, isKids: Boolean): Set<Long> =
        AdultCategoryClassifier.hiddenCategoryIds(categories, customizations.hiddenCategories, isKids)
}

/** Where focus goes after the focused favorite at [removedIndex] is removed from a list now [newSize] long. */
object FavoritesFocus {
    /** The neighbour to focus, or null when the tab is now empty (focus the tab itself). */
    fun indexAfterRemoval(removedIndex: Int, newSize: Int): Int? =
        if (newSize <= 0) null else removedIndex.coerceIn(0, newSize - 1)
}

/**
 * The unified Favorites screen's data (navigation Phase B). No second favorites system: the existing
 * `favorites` table, read through each content DAO's source-filtered [ChannelDao.favoritesForSources] join
 * for the active profile and the active playlist's per-section sources ([activeProfileSources]), so it
 * refreshes on its own when the profile, the playlist, a favorite or the catalog changes.
 */
@Suppress("OPT_IN_USAGE")
class FavoritesViewModel(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
) : ViewModel() {

    private val _tab = MutableStateFlow(FavoritesTab.LIVE)
    val tab: StateFlow<FavoritesTab> = _tab.asStateFlow()

    fun selectTab(tab: FavoritesTab) { _tab.value = tab }

    /** Entering Favorites from another destination starts on Live; returning from its own player/details does not. */
    fun resetTab() { _tab.value = FavoritesTab.LIVE }

    private val active: StateFlow<ActiveProfileSources?> = activeProfileSources(settings, sourceDao)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<FavoritesState> = activeProfileSources(settings, sourceDao)
        .flatMapLatest { a ->
            if (a.profileId < 0) flowOf(FavoritesState(loading = false))
            else combine(liveFlow(a), movieFlow(a), seriesFlow(a)) { l, m, s ->
                FavoritesState(loading = false, live = l, movies = m, series = s)
            }
        }
        .catch { emit(FavoritesState(loading = false, error = true)) }
        .flowOn(Dispatchers.Default) // filtering a long favorites list never runs on the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesState())

    private fun isKids(profileId: Long): Flow<Boolean> = profileDao.observeById(profileId).map { it?.isKids == true }

    private fun liveFlow(a: ActiveProfileSources): Flow<List<ChannelEntity>> {
        val ids = a.liveSourceIds
        if (ids.isEmpty()) return flowOf(emptyList())
        return combine(
            channelDao.favoritesForSources(a.profileId, ids),
            customize.observe(a.profileId, MediaType.LIVE),
            categoryDao.observe(ids, MediaType.LIVE),
            isKids(a.profileId),
        ) { favs, cust, cats, kids ->
            FavoritesFilter.visible(
                favs, { it.id }, CustomizeKeys::channel, { it.categoryId }, cust,
                FavoritesFilter.hiddenCategoryIds(cats, cust, kids),
            ) { c, n -> c.copy(name = n) }
        }
    }

    private fun movieFlow(a: ActiveProfileSources): Flow<List<MovieEntity>> {
        val ids = a.movieSourceIds
        if (ids.isEmpty()) return flowOf(emptyList())
        return combine(
            movieDao.favoritesForSources(a.profileId, ids),
            customize.observe(a.profileId, MediaType.MOVIE),
            categoryDao.observe(ids, MediaType.MOVIE),
            isKids(a.profileId),
        ) { favs, cust, cats, kids ->
            FavoritesFilter.visible(
                favs, { it.id }, CustomizeKeys::movie, { it.categoryId }, cust,
                FavoritesFilter.hiddenCategoryIds(cats, cust, kids),
            ) { m, n -> m.copy(name = n) }
        }
    }

    private fun seriesFlow(a: ActiveProfileSources): Flow<List<SeriesEntity>> {
        val ids = a.seriesSourceIds
        if (ids.isEmpty()) return flowOf(emptyList())
        return combine(
            seriesDao.favoritesForSources(a.profileId, ids),
            customize.observe(a.profileId, MediaType.SERIES),
            categoryDao.observe(ids, MediaType.SERIES),
            isKids(a.profileId),
        ) { favs, cust, cats, kids ->
            FavoritesFilter.visible(
                favs, { it.id }, CustomizeKeys::series, { it.categoryId }, cust,
                FavoritesFilter.hiddenCategoryIds(cats, cust, kids),
            ) { s, n -> s.copy(name = n) }
        }
    }

    /**
     * Remove one favorite with the same DAO call the Live/Movies/Series screens use. Only the favorite row
     * goes: the channel/movie/series and its watch history are untouched. The list updates through Room.
     */
    fun remove(tab: FavoritesTab, itemId: Long) {
        val pid = active.value?.profileId?.takeIf { it >= 0 } ?: return
        val type = when (tab) {
            FavoritesTab.LIVE -> MediaType.LIVE
            FavoritesTab.MOVIES -> MediaType.MOVIE
            FavoritesTab.SERIES -> MediaType.SERIES
        }
        viewModelScope.launch { runCatching { favoriteDao.remove(pid, type, itemId) } }
    }
}
