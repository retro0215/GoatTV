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
    /**
     * Every Game Center wait: the stable-focus settle ([DETAIL_SETTLE_MS]) before the first request and
     * the refresh/retry delays after it. Injectable so tests control time.
     */
    private val detailWait: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _mode = MutableStateFlow<SportsPreviewMode>(SportsPreviewMode.ChannelVideo)
    val mode: StateFlow<SportsPreviewMode> = _mode.asStateFlow()

    private val _detail = MutableStateFlow<GameCenterDetailState>(GameCenterDetailState.Idle)
    val detail: StateFlow<GameCenterDetailState> = _detail.asStateFlow()

    private var detailJob: Job? = null
    private var videoJob: Job? = null

    /** Game Center responses for this Sports session (in memory only; freshness = backend refresh hint). */
    private val detailCache = GameCenterSessionCache(clock)

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

    /**
     * Game Center for the focused event, progressively: the pane already shows the event itself; a
     * cached response (this session) shows at once; the network is asked only after [DETAIL_SETTLE_MS] of
     * stable focus (a fast D-pad sweep sends nothing), and then again only when the backend's
     * `refreshAfterSeconds` says so — while the event stays focused. Leaving the event cancels the
     * request and the schedule; a late response for another event is never shown. Failures keep
     * whatever is shown (last good detail, or the basic event) and retry only on the normal schedule.
     */
    private fun loadDetail(eventId: String) {
        detailJob?.cancel()
        val cached = detailCache.get(eventId)
        _detail.value = cached?.let { stateFor(it.detail) } ?: GameCenterDetailState.Pending(eventId)
        detailJob = scope.launch {
            when (val wait = cached?.let { detailCache.millisUntilRefresh(eventId) }) {
                null -> if (cached == null) detailWait(DETAIL_SETTLE_MS) else return@launch // final/unavailable: nothing to refresh
                else -> detailWait(if (wait > 0) wait else DETAIL_SETTLE_MS)
            }
            var lastHintMs = cached?.detail?.refreshAfterMs
            while (focusedEventId() == eventId) {
                val result = try {
                    source.fetch(eventId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GameCenterFetch.Failed()
                }
                if (focusedEventId() != eventId) return@launch // focus moved on; obsolete
                val next = when (result) {
                    is GameCenterFetch.Loaded -> {
                        _detail.value = stateFor(detailCache.put(result.detail))
                        lastHintMs = result.detail.refreshAfterMs
                        result.detail.refreshAfterMs ?: return@launch
                    }
                    is GameCenterFetch.Failed -> {
                        // Keep the last good detail; with nothing to show, the basic event stays.
                        if (_detail.value !is GameCenterDetailState.Ready) _detail.value = GameCenterDetailState.Unavailable(eventId)
                        result.retryAfterMs ?: maxOf(lastHintMs ?: 0L, FAILURE_RETRY_MS)
                    }
                }
                detailWait(next)
            }
        }
    }

    private fun stateFor(detail: GameCenterDetail): GameCenterDetailState = when (detail.availability) {
        GameCenterAvailability.AVAILABLE -> GameCenterDetailState.Ready(detail.eventId, detail)
        GameCenterAvailability.PENDING -> GameCenterDetailState.Pending(detail.eventId)
        GameCenterAvailability.UNAVAILABLE -> GameCenterDetailState.Unavailable(detail.eventId)
    }

    private fun cancelVideoWork() {
        videoJob?.cancel()
        videoJob = null
    }

    companion object {
        /** Stable focus before the first Game Center request for an event. */
        const val DETAIL_SETTLE_MS = 400L

        /** Retry after a transient failure, unless the backend's own hint is longer. */
        const val FAILURE_RETRY_MS = 30_000L
    }

    private fun focusedEventId(): String? = when (val m = _mode.value) {
        is SportsPreviewMode.EventGameCenter -> m.eventId
        is SportsPreviewMode.EventVideo -> m.eventId
        SportsPreviewMode.ChannelVideo -> null
    }
}

/**
 * In-memory Game Center responses for one Sports session (never persisted). An entry is fresh until
 * its backend `refreshAfterSeconds` elapses; entries without a hint (final confirmed, unavailable) stay
 * fresh for the session. Bounded: the oldest entries go first.
 */
internal class GameCenterSessionCache(private val clock: () -> Long, private val maxEntries: Int = 64) {
    data class Entry(val detail: GameCenterDetail, val storedAtMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    fun get(eventId: String): Entry? = entries[eventId]

    /**
     * Stores a response and returns what should be shown. A response without data (PENDING /
     * UNAVAILABLE) never replaces data already held for the event this session: the last good detail
     * stays, on the new response's refresh schedule.
     */
    fun put(detail: GameCenterDetail): GameCenterDetail {
        val held = entries[detail.eventId]?.detail
        val shown = if (detail.availability != GameCenterAvailability.AVAILABLE && held?.availability == GameCenterAvailability.AVAILABLE) {
            held.copy(refreshAfterMs = detail.refreshAfterMs)
        } else {
            detail
        }
        entries[detail.eventId] = Entry(shown, clock())
        return shown
    }

    /** Millis until the entry should be refreshed (≤ 0 = due now); null = no refresh needed. */
    fun millisUntilRefresh(eventId: String): Long? {
        val e = entries[eventId] ?: return 0L
        val hint = e.detail.refreshAfterMs ?: return null
        return e.storedAtMs + hint - clock()
    }
}
