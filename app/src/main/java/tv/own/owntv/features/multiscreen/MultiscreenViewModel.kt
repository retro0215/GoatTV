package tv.own.owntv.features.multiscreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.features.live.LiveKey
import tv.own.owntv.features.live.LiveRailItem
import tv.own.owntv.ui.components.OwnTVIcon

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class MultiscreenViewModel(
    private val store: MultiscreenStore,
    private val channelDao: tv.own.owntv.core.database.dao.ChannelDao,
    private val sourceDao: tv.own.owntv.core.database.dao.SourceDao,
    private val categoryDao: tv.own.owntv.core.database.dao.CategoryDao,
    private val settings: tv.own.owntv.features.settings.data.SettingsRepository,
    private val favoriteDao: tv.own.owntv.core.database.dao.FavoriteDao,
) : ViewModel() {
    val channels: StateFlow<List<ChannelEntity>> = store.channels
    val audioFocusIndex: StateFlow<Int> = store.audioFocusIndex
    val tileEngines: StateFlow<Map<Long, Boolean>> = store.tileEngines

    val favoriteIds: StateFlow<Set<Long>> = settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList<Long>())
            else favoriteDao.observeFavoriteIds(pid, MediaType.LIVE)
        }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val activeProfileId: StateFlow<Long?> = settings.activeProfileId
        .map { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _sources = MutableStateFlow<Map<Long, SourceEntity>>(emptyMap())
    val sources: StateFlow<Map<Long, SourceEntity>> = _sources.asStateFlow()

    private data class Ctx(val profileId: Long, val sourceIds: List<Long>)

    private val ctx: StateFlow<Ctx> = activeProfileSources(settings, sourceDao)
        .map { aps -> Ctx(aps.profileId, aps.liveSourceIds) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Ctx(-1L, emptyList()))

    init {
        channels.onEach { list ->
            val currentSources = _sources.value
            val neededIds = list.map { it.sourceId }.toSet()
            if (!currentSources.keys.containsAll(neededIds)) {
                viewModelScope.launch {
                    val fresh = neededIds.associateWith { sourceDao.getById(it) }
                        .filterValues { it != null }
                        .mapValues { it.value!! }
                    _sources.value = fresh
                }
            }
        }.launchIn(viewModelScope)
    }

    fun addChannel(channel: ChannelEntity): Boolean {
        return store.addChannel(channel)
    }

    fun removeChannel(channelId: Long) {
        store.removeChannel(channelId)
    }

    fun setAudioFocus(index: Int) {
        store.setAudioFocus(index)
    }

    fun clear() {
        store.clear()
    }

    fun isInMultiscreen(channelId: Long): Boolean {
        return store.isInMultiscreen(channelId)
    }

    fun moveChannel(fromIndex: Int, toIndex: Int) {
        store.moveChannel(fromIndex, toIndex)
    }

    fun setChannels(list: List<ChannelEntity>) {
        store.setChannels(list)
    }

    fun toggleEngine(channelId: Long) {
        store.toggleEngine(channelId)
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first() ?: return@launch
            if (favoriteIds.value.contains(channel.id)) {
                favoriteDao.remove(pid, MediaType.LIVE, channel.id)
            } else {
                favoriteDao.add(tv.own.owntv.core.database.entity.FavoriteEntity(profileId = pid, mediaType = MediaType.LIVE, itemId = channel.id))
            }
        }
    }

    private val _pickerCategory = MutableStateFlow<LiveKey>(LiveKey.All)
    val pickerCategory: StateFlow<LiveKey> = _pickerCategory.asStateFlow()

    private val _pickerSearch = MutableStateFlow("")
    val pickerSearch: StateFlow<String> = _pickerSearch.asStateFlow()

    /** Categories available for the picker (including Favorites, History, All, and Folders). */
    val pickerCategories: StateFlow<List<LiveRailItem>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyList())
            else categoryDao.observe(c.sourceIds, MediaType.LIVE).map { cats ->
                val items = mutableListOf<LiveRailItem>()
                items.add(LiveRailItem(LiveKey.Favorites, icon = OwnTVIcon.FAVORITE))
                items.add(LiveRailItem(LiveKey.History, icon = OwnTVIcon.HISTORY))
                items.add(LiveRailItem(LiveKey.All))
                items.addAll(cats.map { LiveRailItem(LiveKey.Folder(it.id), it.name) })
                items
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Paged channels for the currently selected picker category and search query. */
    val pickerChannels: Flow<PagingData<ChannelEntity>> = combine(
        ctx,
        _pickerCategory,
        _pickerSearch.debounce(300).distinctUntilChanged()
    ) { c, key, query -> Triple(c, key, query) }
        .flatMapLatest { (c, key, query) ->
            if (c.profileId < 0) return@flatMapLatest flowOf(PagingData.empty())
            
            val source: PagingSource<Int, ChannelEntity> = when {
                query.isNotBlank() -> channelDao.searchAll(query, c.sourceIds)
                key is LiveKey.Favorites -> channelDao.pagingFavorites(c.profileId)
                key is LiveKey.History -> channelDao.pagingHistory(c.profileId, c.sourceIds)
                key is LiveKey.All -> channelDao.pagingAllOriginal(c.sourceIds)
                key is LiveKey.Folder -> channelDao.pagingByCategory(key.id)
                else -> channelDao.pagingAllOriginal(c.sourceIds)
            }
            
            Pager(PagingConfig(pageSize = 50)) { source }.flow
        }
        .cachedIn(viewModelScope)

    fun setPickerCategory(key: LiveKey) {
        _pickerCategory.value = key
    }

    fun setPickerSearch(query: String) {
        _pickerSearch.value = query
    }
}
