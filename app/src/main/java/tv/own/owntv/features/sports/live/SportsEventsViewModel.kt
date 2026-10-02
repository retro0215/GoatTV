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

    private val preview = SportsPreviewController(viewModelScope, gameCenter.detailSource)

    /** Sticky preview content: channel video, or the focused event's Game Center. */
    val previewMode: StateFlow<SportsPreviewMode> = preview.mode

    /** Game Center detail for the focused event (guarded by event id). */
    val gameCenterDetail: StateFlow<GameCenterDetailState> = preview.detail

    private val _query = MutableStateFlow("")
    /** Search across team names/abbreviations/titles (architecture for C2 search UI). */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedEventId = MutableStateFlow<String?>(null)
    /** Event whose detail panel is open (OK on a card). */
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    fun setQuery(value: String) { _query.value = value }
    fun openEvent(id: String) { _selectedEventId.value = id }
    fun closeEvent() { _selectedEventId.value = null }

    /** An event card gained focus: Game Center immediately (no player, no network in this phase). */
    fun onEventFocused(id: String) = preview.onEventFocused(id)

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
