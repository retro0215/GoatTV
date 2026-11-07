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

    // --- start budget across an engine handover -------------------------------------------------------
    //
    // Timelines are replayed second by second exactly as OwnTVPlayer's 1 s poll sees them. Times are the
    // worst case against a provider that never answers: mpv-first hands ExoPlayer the item at ~22 s (two
    // 10 s T_OPEN strikes); ExoPlayer-first hands mpv the item at ~33 s (8 s hardware + 25 s software
    // no-frame rungs). Constants mirror OwnTVPlayer's.

    private val total = 45_000L
    private val handoverBudget = 40_000L
    private val hardCap = 90_000L
    private fun budget() = FirstFrameDeadline(total, handoverBudget, hardCap)

    /** Replays a start: [handoversAt] are fallback times, [at] gives the signals seen at each second.
     *  Returns the first verdict that ends the poll and when it happened (null if the timeline ran out). */
    private fun replay(
        d: FirstFrameDeadline,
        handoversAt: List<Long> = emptyList(),
        untilMs: Long = 120_000,
        at: (Long) -> FirstFrameDeadline.Signals = { signals() },
    ): Pair<FirstFrameDeadline.Verdict, Long>? {
        var t = 1_000L
        while (t <= untilMs) {
            handoversAt.filter { it in (t - 1_000) until t }.forEach { d.onHandover(it) }
            val v = d.check(t, at(t))
            if (v != FirstFrameDeadline.Verdict.WAIT && v != FirstFrameDeadline.Verdict.ERROR_SHOWN) return v to t
            t += 1_000
        }
        return null
    }

    @Test
    fun `mpv first, success`() {
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 3_000L, replay(budget()) { signals(videoFrame = it >= 3_000) })
    }

    @Test
    fun `mpv first, open timeout, ExoPlayer fallback plays on its software rung`() {
        // ExoPlayer starts ~26 s (4 s bounded release + settle), its software rung renders at ~55 s.
        val r = replay(budget(), handoversAt = listOf(22_000)) { signals(videoFrame = it >= 55_000) }
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 55_000L, r)
        // Without the handover grant the same start was cut off at 45 s.
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED to 45_000L, replay(budget()) { signals(videoFrame = it >= 55_000) })
    }

    @Test
    fun `ExoPlayer first, success`() {
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 2_000L, replay(budget()) { signals(videoFrame = it >= 2_000) })
    }

    @Test
    fun `ExoPlayer first, timeout, mpv fallback gets its own open ladder`() {
        // mpv takes the item at 33 s; its first open hangs (T_OPEN at 43 s), the silent retry opens and
        // renders at 52 s. Before this fix the deadline killed it at 45 s, right after the silent reset.
        val r = replay(budget(), handoversAt = listOf(33_000)) { signals(videoFrame = it >= 52_000) }
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 52_000L, r)
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED to 45_000L, replay(budget()) { signals(videoFrame = it >= 52_000) })
    }

    @Test
    fun `both engines fail - a dead stream still ends on an error`() {
        // Spinner left up by both ladders: the deadline ends it at handover + budget.
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED to 62_000L, replay(budget(), handoversAt = listOf(22_000)))
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED to 73_000L, replay(budget(), handoversAt = listOf(33_000)))
        // The second engine's own combined error is left on screen and never overwritten.
        assertEquals(null, replay(budget(), handoversAt = listOf(33_000)) { signals(errorShown = it >= 55_000) })
    }

    @Test
    fun `the handover grant is bounded - repeated handovers never pass the hard cap`() {
        val r = replay(budget(), handoversAt = listOf(20_000, 40_000, 60_000, 80_000, 89_000))
        assertEquals(FirstFrameDeadline.Verdict.EXPIRED to 90_000L, r)
    }

    @Test
    fun `an early handover never shortens the budget`() {
        val d = budget()
        d.onHandover(1_000)
        assertEquals(45_000L, d.deadlineMs)
        d.onHandover(30_000)
        assertEquals(70_000L, d.deadlineMs)
        d.onHandover(10_000) // a late-reported earlier handover cannot pull it back in
        assertEquals(70_000L, d.deadlineMs)
    }

    @Test
    fun `a deadline built without a handover budget keeps its fixed total`() {
        val d = FirstFrameDeadline(45_000)
        d.onHandover(40_000)
        assertEquals(45_000L, d.deadlineMs)
    }

    @Test
    fun `slow but successful stream is not cut off`() {
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 44_000L, replay(budget()) { signals(videoFrame = it >= 44_000) })
        // On the fallback engine, audio first: the playhead advancing counts as started.
        val r = replay(budget(), handoversAt = listOf(33_000)) {
            signals(streamOpened = it >= 50_000, positionMs = if (it >= 50_000) it - 50_000 else 0)
        }
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 52_000L, r)
    }

    @Test
    fun `paused playback restoration counts as started once either engine opens`() {
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 4_000L, replay(budget()) { signals(streamOpened = it >= 4_000, startPaused = true) })
        val r = replay(budget(), handoversAt = listOf(33_000)) { signals(streamOpened = it >= 48_000, startPaused = true) }
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 48_000L, r)
    }

    @Test
    fun `Back during a fallback cancels it and the next title gets a fresh budget`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val first = budget()
        first.onHandover(33_000) // title A: ExoPlayer-first gave up, mpv fallback starting
        val pending = CompletableDeferred<Unit>()
        handover.start(release = { pending }, releaseTimeoutMs = 4_000, settleMs = 500) { p.armed += "A" }
        p.supersede() // Back
        handover.cancel()
        pending.complete(Unit)
        yield()
        assertTrue(p.armed.isEmpty())
        assertFalse(handover.inFlight)
        // Title B (or Retry) arms a new deadline: none of A's grant carries over.
        assertEquals(45_000L, budget().deadlineMs)
        assertEquals(73_000L, first.deadlineMs)
    }

    @Test
    fun `switching titles during a fallback starts only the new title`() = runBlocking {
        val p = Player()
        val handover = EngineHandover(this) { p.generation }
        val pendingA = CompletableDeferred<Unit>()
        handover.start(release = { pendingA }, releaseTimeoutMs = 4_000, settleMs = 0) { p.armed += "A" }
        p.supersede() // title B loads
        handover.start(release = { released() }, releaseTimeoutMs = 4_000, settleMs = 0) { p.armed += "B" }
        pendingA.complete(Unit)
        delay(10)
        assertEquals(listOf("B"), p.armed)
        assertEquals(FirstFrameDeadline.Verdict.STARTED to 5_000L, replay(budget()) { signals(videoFrame = it >= 5_000) })
    }

    // --- resume positions: one session per title ----------------------------------------------------
    //
    // Mirrors OwnTVPlayer: `position`/`duration` are the shared fields every engine writes; a new session
    // clears them (onLoad returned true); saves go through persistable(). The device bug: Teen Titans played
    // to 6:22, Bad Apples was opened but never played, and was saved at 6:22 into Continue Watching.

    private class Playback {
        val session = VodSession()
        var position = 0L
        var duration = 0L
        val saved = mutableMapOf<String, Long>()
        var current: String? = null

        /** play()/next()/Retry (userInitiated) or an internal reload (retry rung, fallback, engine toggle). */
        fun load(title: String, startMs: Long, userInitiated: Boolean = true) {
            if (session.onLoad(title, startMs, userInitiated)) { position = 0; duration = 0 }
            current = title
        }
        fun playTo(posMs: Long, durMs: Long = 1_300_000) { session.onStarted(); position = posMs; duration = durMs }
        /** The 10 s tick, pause, and leaving the player all call this (Movie/SeriesViewModel). */
        fun save() { session.persistable(position, duration)?.let { saved[current!!] = it.first } }
        fun stop() = session.onStop()
    }

    @Test
    fun `a title that never starts is not saved and never inherits the previous title's position`() {
        val p = Playback()
        p.load("teen-titans-s01e02", 0); p.playTo(382_000); p.save()
        p.stop()
        p.load("bad-apples", 0) // opened, stalls, never plays
        repeat(6) { p.save() }  // the 10 s ticks while it spins
        p.stop(); p.save()      // Back
        assertEquals(mapOf("teen-titans-s01e02" to 382_000L), p.saved)
        assertEquals(0L, p.position) // nothing of Teen Titans left on the player for the new title
    }

    @Test
    fun `switching titles without Back also starts a fresh session`() {
        val p = Playback()
        p.load("ep1", 0); p.playTo(600_000)
        p.load("ep2", 0) // auto-play next / next button: no stop in between
        p.save()
        assertEquals(null, p.saved["ep2"])
        assertEquals(0L, p.position)
    }

    @Test
    fun `a legitimate resume is preserved and reopened where it stopped`() {
        val p = Playback()
        p.load("film", 1_000_000) // Continue Watching at 16:40
        assertEquals(1_000_000L, p.session.resumePositionMs(p.position))
        p.playTo(1_060_000); p.save(); p.stop(); p.save()
        assertEquals(1_060_000L, p.saved["film"])
        p.load("film", 1_060_000) // reopened later: a new session at its own saved point
        assertEquals(0L, p.position)
        assertEquals(1_060_000L, p.session.resumePositionMs(p.position))
    }

    @Test
    fun `a failed stream and its Retry reopen at the title's own start, not another title's position`() {
        val p = Playback()
        p.load("a", 0); p.playTo(382_000); p.stop()
        p.load("b", 50_000) // b's own resume point
        p.position = 382_000 // a late report from the outgoing engine
        assertEquals(50_000L, p.session.resumePositionMs(p.position)) // what Retry reloads at
        p.load("b", p.session.resumePositionMs(p.position)) // Retry: user-initiated, same title, not stopped
        p.save()
        assertEquals(null, p.saved["b"])
        assertFalse(p.session.started)
    }

    @Test
    fun `Retry mid-film keeps the session and resumes where it stopped`() {
        val p = Playback()
        p.load("film", 0); p.playTo(2_400_000)
        val at = p.session.resumePositionMs(p.position)
        p.load("film", at) // Retry
        assertEquals(2_400_000L, at)
        assertEquals(2_400_000L, p.position) // not cleared: same session
        p.save()
        assertEquals(2_400_000L, p.saved["film"])
    }

    @Test
    fun `engine fallback and internal retries never start a new session`() {
        val p = Playback()
        p.load("film", 300_000)
        p.load("film", 300_000, userInitiated = false) // T_OPEN silent retry
        p.load("film", 300_000, userInitiated = false) // ExoPlayer to mpv fallback
        p.playTo(310_000); p.save()
        assertEquals(310_000L, p.saved["film"])
        p.load("film", 310_000, userInitiated = false) // engine toggle mid-film
        assertEquals(310_000L, p.position)
        assertTrue(p.session.started)
    }

    @Test
    fun `Back before anything played saves nothing, Back after playback saves the last position`() {
        val p = Playback()
        p.load("x", 0); p.stop(); p.save()
        assertEquals(emptyMap<String, Long>(), p.saved)
        p.load("y", 0); p.playTo(90_000); p.stop(); p.save() // the save that follows stop() still lands
        assertEquals(mapOf("y" to 90_000L), p.saved)
    }

    @Test
    fun `a restored paused start counts once the stream opens, at its restore point`() {
        val p = Playback()
        p.load("film", 1_234_000)
        p.save()
        assertEquals(emptyMap<String, Long>(), p.saved)
        p.playTo(1_234_000) // FirstFrameDeadline: stream opened + startPaused gives STARTED
        p.save()
        assertEquals(1_234_000L, p.saved["film"])
    }

    @Test
    fun `nothing is persisted without both a position and a duration`() {
        val s = VodSession()
        s.onLoad("t", 0, userInitiated = true); s.onStarted()
        assertEquals(null, s.persistable(0, 1_000))
        assertEquals(null, s.persistable(1_000, 0))
        assertEquals(1_000L to 2_000L, s.persistable(1_000, 2_000))
    }

    // --- failure wording ------------------------------------------------------------------------------

    @Test
    fun `a stream ExoPlayer never received is a connection failure, not audio-without-video`() {
        assertEquals(PlaybackFailure.StreamUnavailable(false), VodFailureWording.noFrame(streamOpened = false))
        assertEquals(PlaybackFailure.AudioNoVideo, VodFailureWording.noFrame(streamOpened = true))
    }

    @Test
    fun `mpv-first - both failing to connect reads as an unavailable stream, a real decode failure keeps both-engines`() {
        assertEquals(
            PlaybackFailure.StreamUnavailable(true),
            VodFailureWording.bothEnginesMpvFirst(PlaybackFailure.StreamUnavailable(false), customUserAgentHint = true),
        )
        assertEquals(
            PlaybackFailure.BothEnginesMpvFirst(PlaybackFailure.AudioNoVideo),
            VodFailureWording.bothEnginesMpvFirst(PlaybackFailure.AudioNoVideo, customUserAgentHint = false),
        )
    }

    @Test
    fun `ExoPlayer-first - connection failure on both is an unavailable stream, anything else stays both-engines`() {
        val unavailable = PlaybackFailure.StreamUnavailable(false)
        assertEquals(unavailable, VodFailureWording.bothEnginesExoFirst(unavailable, PlaybackFailure.FileCorrupt, mpvOpenedFile = false, customUserAgentHint = false))
        // mpv did open the file: whatever went wrong is the file or decoder, not the connection.
        assertEquals(PlaybackFailure.BothEnginesExoFirst, VodFailureWording.bothEnginesExoFirst(unavailable, PlaybackFailure.FileCorrupt, mpvOpenedFile = true, customUserAgentHint = false))
        assertEquals(PlaybackFailure.BothEnginesExoFirst, VodFailureWording.bothEnginesExoFirst(PlaybackFailure.AudioNoVideo, PlaybackFailure.FileCorrupt, mpvOpenedFile = false, customUserAgentHint = false))
        // ExoPlayer never ran: mpv's own failure stands.
        assertEquals(PlaybackFailure.FileCorrupt, VodFailureWording.bothEnginesExoFirst(null, PlaybackFailure.FileCorrupt, mpvOpenedFile = false, customUserAgentHint = false))
    }
}
