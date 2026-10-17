package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A — the mpv → ExoPlayer fallback for Movies/Series, and the start deadline.
 *
 * The production bug: the stuck-mpv fallback read its generation token, then hard-reset mpv, which bumps
 * the generation; the deferred ExoPlayer start compared against the stale token, gave up, and the film sat
 * on an endless spinner. [EngineHandover] reads the token after the release, and these tests pin that
 * down together with everything the token still has to cancel.
 *
 * runBlocking's single-threaded loop plus explicit release signals make the ordering deterministic.
 */
class VodPlaybackGuardsTest {

    /** Stands in for OwnTVPlayer.loadGeneration and the side effects the handover drives. */
    private class Player {
        var generation = 0
        val armed = mutableListOf<String>()
        var releaseTimeouts = 0
        /** OwnTVPlayer.hardReset(): bumps the generation and hands back the destroy signal. */
        fun hardReset(destroyed: CompletableDeferred<Unit>): CompletableDeferred<Unit> {
            generation++
            return destroyed
        }
        /** Back, stop(), a new item, Retry: anything that supersedes the current load. */
        fun supersede() { generation++ }
    }

    private fun released() = CompletableDeferred(Unit)

    // --- the fixed bug ------------------------------------------------------------------------------

    @Test
    fun `stuck mpv failure starts ExoPlayer although the reset bumps the generation`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        p.supersede() // fallbackToExoVod's own loadGeneration++
        handover.start(
            release = { p.hardReset(released()) },
            releaseTimeoutMs = 1_000, settleMs = 1,
        ) { p.armed += "movie" }.join()
        assertEquals(listOf("movie"), p.armed)
    }

    @Test
    fun `responsive mpv failure starts ExoPlayer only after mpv has stopped`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val stopRan = CompletableDeferred<Unit>()
        val job = handover.start(release = { stopRan }, releaseTimeoutMs = 1_000, settleMs = 1) { p.armed += "movie" }
        repeat(5) { yield() }
        assertTrue("ExoPlayer must not open while mpv still holds the connection", p.armed.isEmpty())
        assertTrue(handover.inFlight)
        stopRan.complete(Unit)
        job.join()
        assertEquals(listOf("movie"), p.armed)
        assertFalse(handover.inFlight)
    }

    // --- cancellation ---------------------------------------------------------------------------------

    @Test
    fun `Back while mpv is still releasing cancels the fallback`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val destroyed = CompletableDeferred<Unit>()
        val job = handover.start(release = { p.hardReset(destroyed) }, releaseTimeoutMs = 1_000, settleMs = 1) { p.armed += "movie" }
        yield()
        p.supersede() // Back
        destroyed.complete(Unit)
        job.join()
        assertTrue(p.armed.isEmpty())
    }

    @Test
    fun `Back during the settle beat cancels the fallback`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val job = handover.start(release = { p.hardReset(released()) }, releaseTimeoutMs = 1_000, settleMs = 50) { p.armed += "movie" }
        launch { delay(10); p.supersede() }
        job.join()
        assertTrue(p.armed.isEmpty())
    }

    @Test
    fun `stop cancels an in-flight fallback outright`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val job = handover.start(release = { CompletableDeferred() }, releaseTimeoutMs = 1_000, settleMs = 1) { p.armed += "movie" }
        handover.cancel()
        job.join()
        assertTrue(p.armed.isEmpty())
        assertFalse(handover.inFlight)
    }

    @Test
    fun `switching to another movie during a fallback plays only the new movie`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val firstDestroyed = CompletableDeferred<Unit>()
        val first = handover.start(release = { p.hardReset(firstDestroyed) }, releaseTimeoutMs = 1_000, settleMs = 1) { p.armed += "movie A" }
        yield()
        p.supersede() // movie B is loaded (loadUrl bumps the generation)
        // ...and movie B's mpv fails too, so it gets its own fallback.
        val second = handover.start(release = { p.hardReset(released()) }, releaseTimeoutMs = 1_000, settleMs = 1) { p.armed += "movie B" }
        firstDestroyed.complete(Unit) // A's old core finally dies — far too late to matter
        first.join(); second.join()
        assertEquals(listOf("movie B"), p.armed)
    }

    @Test
    fun `rapid Back and reopen only ever plays the last request`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val jobs = (1..5).map { n ->
            val destroyed = CompletableDeferred<Unit>()
            val job = handover.start(release = { p.hardReset(destroyed) }, releaseTimeoutMs = 1_000, settleMs = 5) { p.armed += "try $n" }
            yield()
            destroyed.complete(Unit)
            if (n < 5) p.supersede() // Back, then open again
            job
        }
        jobs.forEach { it.join() }
        assertEquals(listOf("try 5"), p.armed)
    }

    @Test
    fun `rapid Back ending on Back plays nothing`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val jobs = (1..3).map { n ->
            handover.start(release = { p.hardReset(released()) }, releaseTimeoutMs = 1_000, settleMs = 5) { p.armed += "try $n" }
                .also { p.supersede() }
        }
        jobs.forEach { it.join() }
        assertTrue(p.armed.isEmpty())
    }

    // --- no duplicate sessions ------------------------------------------------------------------------

    @Test
    fun `a second handover for the same item supersedes the first, never two ExoPlayer starts`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val first = handover.start(release = { released() }, releaseTimeoutMs = 1_000, settleMs = 20) { p.armed += "exo" }
        val second = handover.start(release = { released() }, releaseTimeoutMs = 1_000, settleMs = 20) { p.armed += "exo" }
        first.join(); second.join()
        assertEquals(1, p.armed.size)
    }

    @Test
    fun `repeated mpv failures on one item get exactly one ExoPlayer attempt`() {
        var tried = false
        val verdicts = (1..3).map {
            ExoFallbackPolicy.decide(
                isLive = false, exoActive = false, alreadyTried = tried, engineAllowsHandover = true,
                hasUrl = true, hasSurface = true, audioSafeForExo = { true },
            ).also { v -> if (v == ExoFallbackVerdict.START) tried = true }
        }
        assertEquals(
            listOf(ExoFallbackVerdict.START, ExoFallbackVerdict.ALREADY_TRIED, ExoFallbackVerdict.ALREADY_TRIED),
            verdicts,
        )
    }

    @Test
    fun `no fallback while ExoPlayer already owns the item`() {
        assertEquals(
            ExoFallbackVerdict.EXO_ALREADY_ACTIVE,
            ExoFallbackPolicy.decide(false, exoActive = true, alreadyTried = false, engineAllowsHandover = true, hasUrl = true, hasSurface = true) { true },
        )
    }

    @Test
    fun `the player setting and live are respected`() {
        assertEquals(
            ExoFallbackVerdict.ENGINE_SETTING,
            ExoFallbackPolicy.decide(false, false, false, engineAllowsHandover = false, hasUrl = true, hasSurface = true) { true },
        )
        assertEquals(
            ExoFallbackVerdict.LIVE,
            ExoFallbackPolicy.decide(isLive = true, exoActive = false, alreadyTried = false, engineAllowsHandover = true, hasUrl = true, hasSurface = true) { true },
        )
    }

    @Test
    fun `audio support is only probed once everything else allows the fallback`() {
        var probes = 0
        ExoFallbackPolicy.decide(false, false, alreadyTried = true, engineAllowsHandover = true, hasUrl = true, hasSurface = true) { probes++; true }
        assertEquals(0, probes)
        assertEquals(
            ExoFallbackVerdict.AUDIO_UNSAFE,
            ExoFallbackPolicy.decide(false, false, false, true, hasUrl = true, hasSurface = true) { probes++; false },
        )
        assertEquals(1, probes)
    }

    // --- bounded waits --------------------------------------------------------------------------------

    @Test
    fun `a wedged mpv core cannot hold the fallback hostage`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        handover.start(
            release = { p.hardReset(CompletableDeferred()) }, // destroy() never returns
            releaseTimeoutMs = 20, settleMs = 1,
            onReleaseTimeout = { p.releaseTimeouts++ },
        ) { p.armed += "movie" }.join()
        assertEquals(1, p.releaseTimeouts)
        assertEquals(listOf("movie"), p.armed)
    }

    @Test
    fun `a timed-out release after Back neither reports nor arms`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val job = handover.start(
            release = { p.hardReset(CompletableDeferred()) },
            releaseTimeoutMs = 20, settleMs = 1,
            onReleaseTimeout = { p.releaseTimeouts++ },
        ) { p.armed += "movie" }
        p.supersede()
        job.join()
        assertEquals(0, p.releaseTimeouts)
        assertTrue(p.armed.isEmpty())
    }

    @Test
    fun `nothing to release arms after the settle beat`() = runBlocking {
        val p = Player()
        EngineHandover(this) { p.generation }
            .start(release = { null }, releaseTimeoutMs = 1_000, settleMs = 1) { p.armed += "movie" }.join()
        assertEquals(listOf("movie"), p.armed)
    }

    // --- first-frame deadline -------------------------------------------------------------------------

    private fun signals(
        videoFrame: Boolean = false,
        streamOpened: Boolean = false,
        audioOnly: Boolean = false,
        startPaused: Boolean = false,
        positionMs: Long = 0,
        errorShown: Boolean = false,
    ) = FirstFrameDeadline.Signals(videoFrame, streamOpened, audioOnly, startPaused, positionMs, errorShown)

    @Test
    fun `nothing from either engine ends on an error at the deadline, not an endless spinner`() {
        val d = FirstFrameDeadline(45_000)
        for (t in 1_000L until 45_000L step 1_000) assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(t, signals()))
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED, d.check(45_000, signals()))
    }

    @Test
    fun `a video frame from either engine stands the deadline down`() {
        assertEquals(FirstFrameDeadline.Verdict.STARTED, FirstFrameDeadline(45_000).check(30_000, signals(videoFrame = true)))
        assertEquals(FirstFrameDeadline.Verdict.STARTED, FirstFrameDeadline(45_000).check(99_000, signals(videoFrame = true)))
    }

    @Test
    fun `an error already on screen is left alone`() {
        assertEquals(FirstFrameDeadline.Verdict.ERROR_SHOWN, FirstFrameDeadline(45_000).check(60_000, signals(errorShown = true)))
    }

    @Test
    fun `a ladder that clears its error and keeps spinning past the budget still ends`() {
        val d = FirstFrameDeadline(45_000)
        assertEquals(FirstFrameDeadline.Verdict.ERROR_SHOWN, d.check(20_000, signals(errorShown = true)))
        assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(30_000, signals()))
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED, d.check(46_000, signals()))
    }

    @Test
    fun `playing audio counts as started so the decoder ladder keeps the item`() {
        val d = FirstFrameDeadline(45_000)
        assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(10_000, signals(streamOpened = true, positionMs = 600_000)))
        assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(11_000, signals(streamOpened = true, positionMs = 601_000)))
        assertEquals(FirstFrameDeadline.Verdict.STARTED, d.check(12_000, signals(streamOpened = true, positionMs = 602_000)))
    }

    @Test
    fun `a single position jump after the open is not playback`() {
        val d = FirstFrameDeadline(45_000)
        // The previous item's position, then this item's resume point, then nothing moves.
        d.check(1_000, signals(streamOpened = true, positionMs = 10_000))
        d.check(2_000, signals(streamOpened = true, positionMs = 12_000))
        for (t in 3_000L until 45_000L step 1_000) {
            assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(t, signals(streamOpened = true, positionMs = 12_000)))
        }
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED, d.check(45_000, signals(streamOpened = true, positionMs = 12_000)))
    }

    @Test
    fun `position changes before any engine opened the stream are ignored`() {
        val d = FirstFrameDeadline(45_000)
        assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(1_000, signals(positionMs = 1_000)))
        assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(2_000, signals(positionMs = 2_000)))
        assertEquals(FirstFrameDeadline.Verdict.WAIT, d.check(3_000, signals(positionMs = 3_000)))
    }

    @Test
    fun `an opened audio-only item or a paused start counts as started`() {
        assertEquals(FirstFrameDeadline.Verdict.STARTED, FirstFrameDeadline(45_000).check(2_000, signals(streamOpened = true, audioOnly = true)))
        assertEquals(FirstFrameDeadline.Verdict.STARTED, FirstFrameDeadline(45_000).check(2_000, signals(streamOpened = true, startPaused = true)))
        // ...but not before anything opened.
        assertEquals(FirstFrameDeadline.Verdict.WAIT, FirstFrameDeadline(45_000).check(2_000, signals(audioOnly = true, startPaused = true)))
    }
}
