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
