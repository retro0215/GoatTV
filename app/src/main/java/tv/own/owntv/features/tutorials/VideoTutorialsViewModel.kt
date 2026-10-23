package tv.own.owntv.features.tutorials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.own.owntv.core.network.ConnectivityObserver

/** What the Tutorials screen shows. */
data class VideoTutorialsUiState(
    val phase: Phase = Phase.LOADING,
    val groups: List<VideoTutorialGroup> = emptyList(),
    /** Showing the cached catalog because the latest refresh could not reach the server. */
    val savedNotice: SavedNotice? = null,
    val refreshing: Boolean = false,
    /** Debug builds reading the staging project show a marker. Always false in release. */
    val staging: Boolean = false,
) {
    enum class Phase { LOADING, CONTENT, EMPTY, OFFLINE, ERROR }
    enum class SavedNotice { OFFLINE, ERROR }
}

/**
 * Pure state transitions (unit-tested): the cached catalog shows immediately; a successful refresh
 * replaces it; a failed refresh keeps a cached catalog on screen with a notice, and without one shows
 * the offline or error state with Retry.
 */
internal object VideoTutorialsState {
    fun initial(cached: CachedVideoTutorials?, staging: Boolean): VideoTutorialsUiState =
        if (cached == null) VideoTutorialsUiState(phase = VideoTutorialsUiState.Phase.LOADING, refreshing = true, staging = staging)
        else content(cached.tutorials, staging).copy(refreshing = true)

    fun after(previous: VideoTutorialsUiState, cached: CachedVideoTutorials?, outcome: VideoTutorialsRefresh): VideoTutorialsUiState {
        val staging = previous.staging
        return when (outcome) {
            is VideoTutorialsRefresh.Updated -> content(outcome.tutorials, staging)
            VideoTutorialsRefresh.UpToDate ->
                if (cached != null) content(cached.tutorials, staging) else previous.copy(refreshing = false)
            VideoTutorialsRefresh.Offline ->
                if (cached != null) content(cached.tutorials, staging).copy(savedNotice = VideoTutorialsUiState.SavedNotice.OFFLINE)
                else VideoTutorialsUiState(phase = VideoTutorialsUiState.Phase.OFFLINE, staging = staging)
            // The device reported a connection but the request still failed (timeout, server error,
            // unexpected answer): "couldn't load", not "offline".
            is VideoTutorialsRefresh.Failed ->
                if (cached != null) content(cached.tutorials, staging).copy(savedNotice = VideoTutorialsUiState.SavedNotice.ERROR)
                else VideoTutorialsUiState(phase = VideoTutorialsUiState.Phase.ERROR, staging = staging)
        }
    }

    private fun content(tutorials: List<VideoTutorial>, staging: Boolean) = VideoTutorialsUiState(
        phase = if (tutorials.isEmpty()) VideoTutorialsUiState.Phase.EMPTY else VideoTutorialsUiState.Phase.CONTENT,
        groups = groupByCategory(tutorials),
        staging = staging,
    )
}

class VideoTutorialsViewModel(
    private val repository: VideoTutorialsRepository,
    private val connectivity: ConnectivityObserver,
) : ViewModel() {
    private val _state = MutableStateFlow(VideoTutorialsState.initial(repository.cached(), repository.isStaging))
    val state: StateFlow<VideoTutorialsUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** Entering the screen: refresh unless the cached copy is recent. */
    fun onEnter() = refresh(force = false)

    /** Retry: always asks the server (when online). */
    fun retry() = refresh(force = true)

    private fun refresh(force: Boolean) {
        if (job?.isActive == true) return
        _state.update { it.copy(refreshing = true) }
        job = viewModelScope.launch {
            val outcome = repository.refresh(force = force, online = connectivity.isOnlineNow())
            _state.update { VideoTutorialsState.after(it, repository.cached(), outcome) }
        }
    }
}
