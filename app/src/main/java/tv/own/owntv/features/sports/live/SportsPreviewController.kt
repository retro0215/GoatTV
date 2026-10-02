package tv.own.owntv.features.sports.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.ChannelEntity
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the sticky Sports preview shows. Event metadata/stats ([EventGameCenter]) never touch the
 * player; [ChannelVideo] and [EventVideo] both drive the ONE existing in-pane preview engine.
 */
sealed interface SportsPreviewMode {
    /** A traditional Sports channel is (or was last) focused: the existing channel video preview. */
    data object ChannelVideo : SportsPreviewMode

    /** An event card is focused: Game Center, rendered immediately from the event's local data. */
    data class EventGameCenter(val eventId: String) : SportsPreviewMode

    /** A LIVE event's locally verified channel playing in the pane (dwell, double OK or Where to Watch). */
    data class EventVideo(val eventId: String, val channel: ChannelEntity) : SportsPreviewMode

    companion object {
        /** Stable focus on a LIVE event with a verified channel before Game Center hands over to video. */
        const val EVENT_VIDEO_SETTLE_MS: Long = 3000L
    }
}

/**
 * Preview mode + Game Center detail + event video for the focused event.
 *
 * Focus changes are synchronous state writes (no I/O, no player work), so crossing many cards with the
 * D-pad costs nothing: the previous event's detail request and its video dwell are cancelled before
 * anything starts for the next one, and late results for an event that is no longer focused are
 * ignored. Channel resolution (local DB only) runs only once a dwell completes or on an explicit
 * action — never per card crossed.
 */
class SportsPreviewController(
    private val scope: CoroutineScope,
    private val source: GameCenterDetailSource,
    /** The event's channels that passed LOCAL verification, in backend rank order. */
    private val channels: suspend (SportsEvent) -> List<ResolvedSportsChannel> = { emptyList() },
    private val channelsEnabled: Boolean = false,
    private val dwell: suspend () -> Unit = { delay(SportsPreviewMode.EVENT_VIDEO_SETTLE_MS) },
) {
    private val _mode = MutableStateFlow<SportsPreviewMode>(SportsPreviewMode.ChannelVideo)
    val mode: StateFlow<SportsPreviewMode> = _mode.asStateFlow()

    private val _detail = MutableStateFlow<GameCenterDetailState>(GameCenterDetailState.Idle)
    val detail: StateFlow<GameCenterDetailState> = _detail.asStateFlow()

    private var detailJob: Job? = null
    private var videoJob: Job? = null

    /** Feed the user picked per event (this Sports session only; never persisted). */
    private val selectedFeed = HashMap<String, Long>()

    /**
     * Events whose video failed: no AUTOMATIC retry while the event stays focused (no retry loop).
     * Cleared when focus leaves the event, or by a deliberate action (double OK / picking a feed).
     */
    private val failedVideo = HashSet<String>()

    /** True while an event modal (Where to Watch, Game Details, the event's Multiscreen menu) is open. */
    private var modalOpen = false

    /** The focused event when the modal opened: the only one whose dwell may restart on close. */
    private var modalEventId: String? = null

    /** The current [SportsPreviewMode.EventVideo] came from the dwell (not an explicit user choice). */
    private var autoVideo = false

    fun onEventFocused(event: SportsEvent) {
        val previous = focusedEventId()
        if (previous == event.id) return // same card (e.g. back from a dialog): keep its state
        previous?.let { failedVideo -= it } // leaving a failed event re-allows it next time
        cancelVideoWork()
        autoVideo = false
        _mode.value = SportsPreviewMode.EventGameCenter(event.id)
        loadDetail(event.id)
        startDwell(event)
    }

    /**
     * An event modal opened or closed. Opening suspends automatic preview: the running dwell is
     * cancelled and an auto-started video behind it returns to Game Center (an explicitly chosen feed
     * keeps playing). Closing with the same event still focused, still existing and still eligible
     * starts a FRESH dwell from zero — never the remainder of the old one. [latest] returns the event's
     * current data (null if it no longer exists).
     */
    fun setModalOpen(open: Boolean, latest: (String) -> SportsEvent?) {
        if (open == modalOpen) return
        modalOpen = open
        if (open) {
            modalEventId = focusedEventId()
            cancelVideoWork()
            val current = _mode.value
            if (current is SportsPreviewMode.EventVideo && autoVideo) {
                autoVideo = false
                _mode.value = SportsPreviewMode.EventGameCenter(current.eventId)
            }
            return
        }
        val id = modalEventId
        modalEventId = null
        if (id == null || focusedEventId() != id || _mode.value != SportsPreviewMode.EventGameCenter(id)) return
        val event = latest(id) ?: return
        startDwell(event)
    }

    private fun startDwell(event: SportsEvent) {
        if (modalOpen || !autoPreviewEligible(event)) return
        videoJob = scope.launch {
            dwell()
            val channel = pick(event) ?: return@launch
            if (!modalOpen && _mode.value == SportsPreviewMode.EventGameCenter(event.id)) {
                autoVideo = true
                _mode.value = SportsPreviewMode.EventVideo(event.id, channel)
            }
        }
    }

    fun onChannelFocused() {
        focusedEventId()?.let { failedVideo -= it }
        autoVideo = false
        cancelVideoWork()
        detailJob?.cancel()
        detailJob = null
        if (_mode.value != SportsPreviewMode.ChannelVideo) _mode.value = SportsPreviewMode.ChannelVideo
    }

    /**
     * Double OK. Event Video → Game Center; Game Center → Event Video when the event is LIVE and has a
     * verified channel. Otherwise nothing changes (no error for the normal no-channel case).
     */
    fun toggleEventVideo(event: SportsEvent) {
        val current = _mode.value
        if (current is SportsPreviewMode.EventVideo && current.eventId == event.id) {
            cancelVideoWork()
            autoVideo = false
            _mode.value = SportsPreviewMode.EventGameCenter(event.id)
            return
        }
        if (!channelsEnabled || event.status != SportsEventStatus.LIVE || focusedEventId() != event.id) return
        cancelVideoWork()
        videoJob = scope.launch {
            val channel = pick(event) ?: return@launch
            if (focusedEventId() != event.id) return@launch
            failedVideo -= event.id
            autoVideo = false
            _mode.value = SportsPreviewMode.EventVideo(event.id, channel)
        }
    }

    /** Where to Watch → Preview: play [channel] (already re-verified) for [event] and remember the feed. */
    fun selectFeed(event: SportsEvent, channel: ChannelEntity) {
        cancelVideoWork()
        selectedFeed[event.id] = channel.id
        failedVideo -= event.id
        autoVideo = false
        _mode.value = SportsPreviewMode.EventVideo(event.id, channel)
    }

    /** Remember the feed chosen for fullscreen too, so returning shows the same feed. */
    fun rememberFeed(eventId: String, channelId: Long) {
        selectedFeed[eventId] = channelId
    }

    /**
     * The event's video (automatic or explicit) could not be played: back to Game Center, and no
     * automatic dwell retries it until focus leaves the event or the user acts deliberately.
     */
    fun onEventVideoFailed(eventId: String) {
        val current = _mode.value
        if (current !is SportsPreviewMode.EventVideo || current.eventId != eventId) return
        cancelVideoWork()
        failedVideo += eventId
        autoVideo = false
        _mode.value = SportsPreviewMode.EventGameCenter(eventId)
    }

    fun selectedFeedFor(eventId: String): Long? = selectedFeed[eventId]

    private fun autoPreviewEligible(event: SportsEvent): Boolean =
        channelsEnabled && event.status == SportsEventStatus.LIVE && event.id !in failedVideo

    /** The user's feed for this event if it still verifies, else the highest-ranked verified channel. */
    private suspend fun pick(event: SportsEvent): ChannelEntity? {
        val accepted = try {
            channels(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val chosen = selectedFeed[event.id]
        return (accepted.firstOrNull { it.channel.id == chosen } ?: accepted.firstOrNull())?.channel
    }

    private fun loadDetail(eventId: String) {
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

    private fun cancelVideoWork() {
        videoJob?.cancel()
        videoJob = null
    }

    private fun focusedEventId(): String? = when (val m = _mode.value) {
        is SportsPreviewMode.EventGameCenter -> m.eventId
        is SportsPreviewMode.EventVideo -> m.eventId
        SportsPreviewMode.ChannelVideo -> null
    }
}
