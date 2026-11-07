package tv.own.owntv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The pure decisions behind a Movies/Series start, pulled out of [OwnTVPlayer] so they can be tested
 * without mpv, ExoPlayer or a Surface. [OwnTVPlayer] still owns every side effect; these classes only
 * decide *whether* and *when*.
 */

/** Whether a VOD that failed on mpv may be handed to ExoPlayer. [START] is the only verdict that does. */
internal enum class ExoFallbackVerdict { START, LIVE, EXO_ALREADY_ACTIVE, ALREADY_TRIED, ENGINE_SETTING, NO_URL, NO_SURFACE, AUDIO_UNSAFE }

internal object ExoFallbackPolicy {
    /**
     * One ExoPlayer attempt per item: [alreadyTried] is set by the first fallback and only cleared by a
     * genuinely new item or a manual engine choice, so a run of mpv failures on the same movie can never
     * start a second ExoPlayer session behind the first.
     */
    fun decide(
        isLive: Boolean,
        exoActive: Boolean,
        alreadyTried: Boolean,
        engineAllowsHandover: Boolean,
        hasUrl: Boolean,
        hasSurface: Boolean,
        audioSafeForExo: () -> Boolean,
    ): ExoFallbackVerdict = when {
        isLive -> ExoFallbackVerdict.LIVE
        exoActive -> ExoFallbackVerdict.EXO_ALREADY_ACTIVE
        alreadyTried -> ExoFallbackVerdict.ALREADY_TRIED
        !engineAllowsHandover -> ExoFallbackVerdict.ENGINE_SETTING
        !hasUrl -> ExoFallbackVerdict.NO_URL
        !hasSurface -> ExoFallbackVerdict.NO_SURFACE
        !audioSafeForExo() -> ExoFallbackVerdict.AUDIO_UNSAFE
        else -> ExoFallbackVerdict.START
    }
}

/**
 * Sequences an mpv → ExoPlayer handover: release the outgoing mpv, wait (bounded) until it has let go of
 * the provider connection, settle, then [start]'s `arm` hands the item to ExoPlayer.
 *
 * The generation token is read AFTER `release` runs. Releasing a stuck core is a hard reset, and a hard
 * reset supersedes the old instance by bumping the player's generation itself; a token taken before it
 * was always stale, so the ExoPlayer start was always abandoned and the item sat on an endless spinner.
 * Anything that bumps the generation after that point — Back, stop, another movie, a retry — still
 * cancels the handover, which is what the token is for.
 *
 * Only one handover is ever in flight: starting a new one cancels the previous one, so two handovers can
 * never both reach `arm` and open two ExoPlayer sessions for one item.
 */
internal class EngineHandover(
    private val scope: CoroutineScope,
    private val generation: () -> Int,
) {
    private var job: Job? = null

    /** True between [start] and the handover arming, being cancelled, or being superseded. */
    val inFlight: Boolean get() = job?.isActive == true

    /**
     * @param release releases the outgoing mpv and returns a signal that completes once it has let go of
     *   its connection (or null when there is nothing to wait for). May bump [generation].
     * @param releaseTimeoutMs the longest the handover waits for that signal. A wedged core must never
     *   hold the fallback hostage; past this the handover proceeds and [onReleaseTimeout] says so.
     * @param settleMs the decoder/surface settle beat after the release, before ExoPlayer claims them.
     */
    fun start(
        release: () -> Deferred<Unit>?,
        releaseTimeoutMs: Long,
        settleMs: Long,
        onReleaseTimeout: () -> Unit = {},
        arm: () -> Unit,
    ): Job {
        job?.cancel()
        val released = release()
        val token = generation()
        return scope.launch {
            if (released != null && withTimeoutOrNull(releaseTimeoutMs) { released.await() } == null) {
                if (generation() == token) onReleaseTimeout()
            }
            if (generation() != token) return@launch
            if (settleMs > 0) delay(settleMs)
            if (generation() != token) return@launch
            arm()
        }.also { job = it }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * Which words a Movies/Series failure gets. A stream that never connected must not be described as a
 * decoding problem: "Audio is playing, but video could not be rendered" and "the file may be corrupted"
 * are both claims about a file the player actually received.
 */
internal object VodFailureWording {
    /** ExoPlayer's no-frame timer fired. Only a stream that reached READY can be "audio without video";
     *  one that never did was never received at all. */
    fun noFrame(streamOpened: Boolean): PlaybackFailure =
        if (streamOpened) PlaybackFailure.AudioNoVideo else PlaybackFailure.StreamUnavailable(customUserAgentHint = false)

    /** mpv failed first, then ExoPlayer. When ExoPlayer could not even connect, the honest answer is
     *  "couldn't play this stream", not "both engines failed / the file may be corrupted". */
    fun bothEnginesMpvFirst(exoError: PlaybackFailure, customUserAgentHint: Boolean): PlaybackFailure =
        if (exoError is PlaybackFailure.StreamUnavailable) PlaybackFailure.StreamUnavailable(customUserAgentHint)
        else PlaybackFailure.BothEnginesMpvFirst(exoError)

    /** ExoPlayer failed first ([exoError], null when it never ran), then mpv with [mpvError]. If ExoPlayer
     *  never connected and mpv never opened the file either, it is a connection failure. */
    fun bothEnginesExoFirst(
        exoError: PlaybackFailure?,
        mpvError: PlaybackFailure,
        mpvOpenedFile: Boolean,
        customUserAgentHint: Boolean,
    ): PlaybackFailure = when {
        exoError == null -> mpvError
        exoError is PlaybackFailure.StreamUnavailable && !mpvOpenedFile -> PlaybackFailure.StreamUnavailable(customUserAgentHint)
        else -> PlaybackFailure.BothEnginesExoFirst
    }
}

/**
 * One viewing session of one title: which title the player's position and duration belong to, whether
 * that title has genuinely started, and where it was asked to start.
 *
 * The player's position and duration are plain fields that every engine writes into. Without a session
 * boundary they kept the previous title's values until the new one reported its own, so a title that
 * never started was saved with the previous title's resume point (and so appeared in Continue Watching),
 * and Retry / screensaver restore reopened it at that foreign position.
 *
 * - A session begins on a user-initiated load ([onLoad] with `userInitiated`) of a different title, or of
 *   any title after [onStop]. Internal reloads — retries, decoder rungs, engine fallback and the engine
 *   toggle — never begin one, so a title that is playing keeps its own position across them.
 * - Retry is user-initiated but continues the same session: same title, not stopped.
 * - [persistable] yields nothing until [onStarted]; a title that never played is never written.
 */
internal class VodSession {
    var identity: String? = null
        private set
    var started: Boolean = false
        private set
    /** Where the session's title was asked to start (its saved resume point, or 0). */
    var requestedStartMs: Long = 0L
        private set
    private var stopped = true

    /** @return true when this load begins a new session: the caller must clear position and duration. */
    fun onLoad(identity: String, startPositionMs: Long, userInitiated: Boolean): Boolean {
        if (!userInitiated || (!stopped && identity == this.identity)) return false
        this.identity = identity
        started = false
        requestedStartMs = startPositionMs.coerceAtLeast(0L)
        stopped = false
        return true
    }

    /** The current title produced picture, sound, or an opened paused start. */
    fun onStarted() { started = true }

    /** The player was stopped (Back, Home, leaving the player). The next user load is a new session. */
    fun onStop() { stopped = true }

    /** Position and duration that may be saved for the current title, or null when nothing may be. */
    fun persistable(positionMs: Long, durationMs: Long): Pair<Long, Long>? =
        if (started && positionMs > 0 && durationMs > 0) positionMs to durationMs else null

    /** Where to reopen the current title (Retry, restore): its own position once it has played, else
     *  the point it was asked to start from — never another title's position. */
    fun resumePositionMs(currentPositionMs: Long): Long =
        if (started && currentPositionMs > 0) currentPositionMs else requestedStartMs
}

/**
 * Bounds a Movies/Series start across both engines, so a stream that never produces anything ends on an
 * error instead of an endless spinner. The engines' own ladders (open watchdog, silent reset, decoder
 * rungs, ExoPlayer fallback) run inside this budget untouched; this only catches the case where all of
 * them are still waiting when the budget runs out.
 *
 * "Started" is deliberately generous, because a false expiry would kill a working film:
 * - a video frame (either engine), or
 * - the playhead advancing on two consecutive checks after an engine opened the stream (audio is
 *   playing, so the decoder ladder — not this — owns whatever is wrong with the picture), or
 * - an opened stream that has no video track, or one that was asked to start paused.
 *
 * An engine handover ([onHandover]) guarantees the incoming engine [handoverBudgetMs] of its own: the
 * outgoing engine's ladder can use up most of [totalMs] before it gives up (ExoPlayer-first spends ~33 s
 * on its hardware and software rungs), and expiring then would cut mpv off a few seconds into the
 * fallback it was handed. The extension only ever moves the deadline later, and never past [hardCapMs],
 * so however many handovers happen the start stays bounded.
 */
internal class FirstFrameDeadline(
    private val totalMs: Long,
    private val handoverBudgetMs: Long = 0,
    private val hardCapMs: Long = totalMs,
) {
    enum class Verdict { WAIT, STARTED, ERROR_SHOWN, EXPIRED }

    /** When the current attempt expires, measured from the start of the item. */
    @Volatile var deadlineMs: Long = totalMs
        private set

    /** An engine handed the item to the other engine at [elapsedMs]. */
    fun onHandover(elapsedMs: Long) {
        deadlineMs = minOf(hardCapMs, maxOf(deadlineMs, elapsedMs + handoverBudgetMs))
    }

    data class Signals(
        val videoFrame: Boolean,
        val streamOpened: Boolean,
        val audioOnly: Boolean,
        val startPaused: Boolean,
        val positionMs: Long,
        val errorShown: Boolean,
    )

    private var lastPositionMs: Long? = null
    private var advancingChecks = 0

    fun check(elapsedMs: Long, s: Signals): Verdict {
        if (s.errorShown) return Verdict.ERROR_SHOWN
        if (s.videoFrame) return Verdict.STARTED
        if (s.streamOpened && (s.audioOnly || s.startPaused)) return Verdict.STARTED
        if (s.streamOpened) {
            val last = lastPositionMs
            val delta = if (last == null) 0L else s.positionMs - last
            // A single jump is not playback: the first position report after an open moves the playhead
            // from the previous item's value to this one's resume point. Real playback moves forward by
            // roughly one check interval, again and again.
            advancingChecks = if (delta in 1..MAX_TICK_ADVANCE_MS) advancingChecks + 1 else 0
            lastPositionMs = s.positionMs
            if (advancingChecks >= 2) return Verdict.STARTED
        } else {
            lastPositionMs = null
            advancingChecks = 0
        }
        return if (elapsedMs >= deadlineMs) Verdict.EXPIRED else Verdict.WAIT
    }

    private companion object {
        const val MAX_TICK_ADVANCE_MS = 5_000L
    }
}
