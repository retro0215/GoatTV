package tv.own.owntv.features.sports.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the sticky Sports preview shows. Event metadata/stats ([EventGameCenter]) never touch the
 * player; only [ChannelVideo] drives the in-pane ExoPlayer preview.
 */
sealed interface SportsPreviewMode {
    /** A traditional Sports channel is (or was last) focused: the existing channel video preview. */
    data object ChannelVideo : SportsPreviewMode

    /** An event card is focused: Game Center, rendered immediately from the event's local data. */
    data class EventGameCenter(val eventId: String) : SportsPreviewMode

    /**
     * RESERVED (Phase C2, never produced yet): a LIVE event with a verified playable channel after
     * [EVENT_VIDEO_SETTLE_MS] of stable focus. Until then it renders as Game Center.
     */
    data class EventVideo(val eventId: String, val channelId: String) : SportsPreviewMode

    companion object {
        /** Planned stable-focus delay before a live event's Game Center may hand over to video. */
        const val EVENT_VIDEO_SETTLE_MS: Long = 600L
    }
}

/**
 * Preview mode + Game Center detail for the focused event. Focus changes are synchronous state
 * writes (no I/O, no player work), so crossing many cards with the D-pad costs nothing; the detail
 * request for the previous event is cancelled before the next one starts, and a late response for an
 * event that is no longer focused is ignored.
 */
class SportsPreviewController(
    private val scope: CoroutineScope,
    private val source: GameCenterDetailSource,
) {
    private val _mode = MutableStateFlow<SportsPreviewMode>(SportsPreviewMode.ChannelVideo)
    val mode: StateFlow<SportsPreviewMode> = _mode.asStateFlow()

    private val _detail = MutableStateFlow<GameCenterDetailState>(GameCenterDetailState.Idle)
    val detail: StateFlow<GameCenterDetailState> = _detail.asStateFlow()

    private var detailJob: Job? = null

    fun onEventFocused(eventId: String) {
        val current = _mode.value
        if (current is SportsPreviewMode.EventGameCenter && current.eventId == eventId) return
        _mode.value = SportsPreviewMode.EventGameCenter(eventId)
        detailJob?.cancel()
        // Keep a detail we already hold for this event (focus returning from Game Details / a channel).
        val held = _detail.value
        if (held is GameCenterDetailState.Ready && held.eventId == eventId) return
        _detail.value = GameCenterDetailState.Pending(eventId)
        detailJob = scope.launch {
            val result = try {
                source.detail(eventId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (focusedEventId() != eventId) return@launch // focus moved on; obsolete
            _detail.value = if (result != null) GameCenterDetailState.Ready(eventId, result) else GameCenterDetailState.Unavailable(eventId)
        }
    }

    fun onChannelFocused() {
        if (_mode.value == SportsPreviewMode.ChannelVideo) return
        _mode.value = SportsPreviewMode.ChannelVideo
        detailJob?.cancel()
        detailJob = null
    }

    private fun focusedEventId(): String? = when (val m = _mode.value) {
        is SportsPreviewMode.EventGameCenter -> m.eventId
        is SportsPreviewMode.EventVideo -> m.eventId
        SportsPreviewMode.ChannelVideo -> null
    }
}
