package tv.own.owntv.features.sports.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Event side of the Sports screen. The channel rows keep their own [tv.own.owntv.features.sports.SportsViewModel];
 * this one only exposes the app-scoped [SportsLiveStore] plus UI selection/search state and the
 * sticky preview's mode / Game Center detail ([SportsPreviewController]).
 */
class SportsEventsViewModel(
    private val store: SportsLiveStore,
    gameCenter: SportsGameCenterConfig,
) : ViewModel() {

    /** The store's slate; in debug fixture mode, with the Game Center fixtures at the front of Popular. */
    val state: StateFlow<SportsLiveState> = gameCenter.fixtures?.let { fixtures ->
        store.state
            .map { SportsGameCenterFixtureMerge.withFixtures(it, fixtures, System.currentTimeMillis()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SportsGameCenterFixtureMerge.withFixtures(store.state.value, fixtures, System.currentTimeMillis()))
    } ?: store.state

    private val channelSource = gameCenter.channelSource
    private val resolver = gameCenter.resolver

    /** Event channel actions (Where to Watch, event video, event Multiscreen): off in release today. */
    val eventChannelsEnabled: Boolean = gameCenter.channelsEnabled

    private val preview = SportsPreviewController(
        scope = viewModelScope,
        source = gameCenter.detailSource,
        channels = { event -> verifiedChannels(event) },
        channelsEnabled = eventChannelsEnabled,
    )

    /** Sticky preview content: channel video, the focused event's Game Center, or its event video. */
    val previewMode: StateFlow<SportsPreviewMode> = preview.mode

    /** Game Center detail for the focused event (guarded by event id). */
    val gameCenterDetail: StateFlow<GameCenterDetailState> = preview.detail

    /** Sports browsing position, kept while fullscreen / Multiscreen replaces the Sports screen. */
    val browse = SportsBrowseState()

    private val _query = MutableStateFlow("")
    /** Search across team names/abbreviations/titles (architecture for C2 search UI). */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedEventId = MutableStateFlow<String?>(null)
    /** Event whose detail panel is open (OK on a card). */
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    private val _whereToWatch = MutableStateFlow<SportsWhereToWatchState?>(null)
    /** The Where to Watch dialog (single OK / long press on an event), or null when closed. */
    val whereToWatch: StateFlow<SportsWhereToWatchState?> = _whereToWatch.asStateFlow()
    private var whereToWatchJob: kotlinx.coroutines.Job? = null

    /**
     * The event's channels that pass LOCAL verification right now (active Xtream source, sourceId +
     * remoteId, EPG / name agreement), in backend rank order. Empty whenever event channels are off.
     */
    suspend fun verifiedChannels(event: SportsEvent): List<ResolvedSportsChannel> {
        if (!eventChannelsEnabled) return emptyList()
        val r = resolver ?: return emptyList()
        val refs = channelSource.refs(event)
        if (refs.isEmpty()) return emptyList()
        return r.resolve(refs).accepted
    }

    /** Re-verify one channel at action time; null if it no longer passes (never play an unverified feed). */
    suspend fun reverify(event: SportsEvent, channelId: Long): ResolvedSportsChannel? =
        verifiedChannels(event).firstOrNull { it.channel.id == channelId }

    fun openWhereToWatch(event: SportsEvent, purpose: SportsWhereToWatchPurpose) {
        if (!eventChannelsEnabled) return
        whereToWatchJob?.cancel()
        _whereToWatch.value = SportsWhereToWatchState(event.id, purpose, channels = null)
        whereToWatchJob = viewModelScope.launch {
            val list = verifiedChannels(event)
            if (_whereToWatch.value?.eventId == event.id) {
                _whereToWatch.value = SportsWhereToWatchState(event.id, purpose, list, preview.selectedFeedFor(event.id))
            }
        }
    }

    fun closeWhereToWatch() {
        whereToWatchJob?.cancel()
        _whereToWatch.value = null
    }

    /** Double OK on an event card. */
    fun toggleEventVideo(event: SportsEvent) = preview.toggleEventVideo(event)

    /** Where to Watch → Preview: the pane plays this (re-verified) feed and remembers it for the event. */
    fun previewFeed(event: SportsEvent, channel: tv.own.owntv.core.database.entity.ChannelEntity) = preview.selectFeed(event, channel)

    fun rememberFeed(eventId: String, channelId: Long) = preview.rememberFeed(eventId, channelId)

    /** The pane's event video failed to start / died: back to Game Center, no automatic retry. */
    fun onEventVideoFailed(eventId: String) = preview.onEventVideoFailed(eventId)

    fun setQuery(value: String) { _query.value = value }
    fun openEvent(id: String) { _selectedEventId.value = id }
    fun closeEvent() { _selectedEventId.value = null }

    /** An event card gained focus: Game Center immediately; a LIVE event with a verified channel may
     *  hand over to video after the stable-focus dwell. No player work and no I/O happen here. */
    fun onEventFocused(event: SportsEvent) = preview.onEventFocused(event)

    /** A Sports channel gained focus: the existing channel video preview takes the pane back. */
    fun onChannelFocused() = preview.onChannelFocused()

    /**
     * Refresh loop for the visible screen: call from a lifecycle-scoped effect (cancelled when Sports
     * leaves composition or the app stops), so there is never more than one loop and nothing polls in
     * the background. Respects the store's schedule, so returning to Sports doesn't refetch early.
     */
    suspend fun runWhileVisible() {
        while (true) {
            val wait = store.millisUntilDue()
            if (wait > 0) delay(wait)
            store.refresh()
        }
    }
}

enum class SportsWhereToWatchPurpose {
    /** Single OK: watch (fullscreen) or preview a feed. */
    WATCH,

    /** Long press: pick the feed to add to Multiscreen. */
    MULTISCREEN,
}

/** Where to Watch dialog state. [channels] is null while the local verification runs. */
data class SportsWhereToWatchState(
    val eventId: String,
    val purpose: SportsWhereToWatchPurpose,
    val channels: List<ResolvedSportsChannel>?,
    /** The feed already chosen for this event in this session, if any (marked in the list). */
    val selectedChannelId: Long? = null,
)
