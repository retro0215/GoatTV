package tv.own.owntv.features.sports.live

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event side of the Sports screen. The channel rows keep their own [tv.own.owntv.features.sports.SportsViewModel];
 * this one only exposes the app-scoped [SportsLiveStore] plus UI selection/search state.
 */
class SportsEventsViewModel(private val store: SportsLiveStore) : ViewModel() {

    val state: StateFlow<SportsLiveState> = store.state

    private val _query = MutableStateFlow("")
    /** Search across team names/abbreviations/titles (architecture for C2 search UI). */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedEventId = MutableStateFlow<String?>(null)
    /** Event whose detail panel is open (OK on a card). */
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    fun setQuery(value: String) { _query.value = value }
    fun openEvent(id: String) { _selectedEventId.value = id }
    fun closeEvent() { _selectedEventId.value = null }

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
