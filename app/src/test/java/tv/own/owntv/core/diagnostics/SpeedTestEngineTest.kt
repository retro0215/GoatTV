package tv.own.owntv.core.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class SpeedTestEngineTest {

    /** Fake clock + transport: each ping costs [pingMs]; each chunk of [chunk] bytes advances [msPerChunk]. */
    private class Fake(
        val clock: AtomicLong = AtomicLong(0),
        val pingMs: Long = 30,
        val pingOk: Boolean = true,
        val chunk: Int = 64 * 1024,
        val msPerChunk: Long = 10,
        val fileBytes: Long = Long.MAX_VALUE,
    ) : SpeedTestTransport {
        val downloaded = AtomicLong(0)
        override suspend fun ping(): Boolean { clock.addAndGet(pingMs); return pingOk }
        override suspend fun download(onChunk: (Int) -> Boolean) {
            var sent = 0L
            while (sent < fileBytes) {
                yield()
                clock.addAndGet(msPerChunk)
                sent += chunk; downloaded.addAndGet(chunk.toLong())
                if (!onChunk(chunk)) return
            }
        }
    }

    private fun engine(f: Fake, config: SpeedTestConfig = SpeedTestConfig(connections = 1)) =
        SpeedTestEngine(f, config) { f.clock.get() }

    @Test
    fun `state machine - latency samples, then live download, then a result`() = runBlocking {
        val f = Fake()
        val states = mutableListOf<SpeedTestState>()
        val end = engine(f).run { states += it }
        assertEquals(SpeedTestState.Latency(1, 5), states.first())
        assertTrue(states.any { it is SpeedTestState.Downloading })
        val live = states.filterIsInstance<SpeedTestState.Downloading>()
        assertTrue("live Mbps is shown while downloading", live.last().currentMbps > 0)
        assertTrue(end is SpeedTestState.Done)
        assertEquals(end, states.last())
    }

    @Test
    fun `result calculation - latency median and post-warm-up Mbps`() = runBlocking {
        // 64 KiB per 10 ms = 52.4288 Mbps steady.
        val f = Fake()
        val done = engine(f).run {} as SpeedTestState.Done
        assertEquals(30L, done.result.latencyMs)
        assertEquals(52.43, done.result.downloadMbps, 0.6)
        assertEquals(SpeedRating.EXCELLENT, done.result.rating)
    }

    @Test
    fun `duration cap - the download stops at maxDownloadMs`() = runBlocking {
        val f = Fake(msPerChunk = 10)
        val done = engine(f, SpeedTestConfig(connections = 1, maxDownloadMs = 2_000, maxBytes = Long.MAX_VALUE)).run {} as SpeedTestState.Done
        assertTrue(done.result.durationMs in 2_000..2_100)
    }

    @Test
    fun `byte cap - never more than maxBytes plus one chunk per connection`() = runBlocking {
        val f = Fake(msPerChunk = 1) // very fast line: the byte cap is hit first
        val cap = 4L * 1024 * 1024
        val cfg = SpeedTestConfig(connections = 3, maxBytes = cap, maxDownloadMs = 60_000)
        val done = engine(f, cfg).run {} as SpeedTestState.Done
        assertTrue("used ${done.result.bytesUsed}", done.result.bytesUsed <= cap + 3 * f.chunk)
        assertTrue(f.downloaded.get() <= cap + 3 * f.chunk)
    }

    @Test
    fun `unreachable endpoint - failed, no download attempted`() = runBlocking {
        val f = Fake(pingOk = false)
        assertEquals(SpeedTestState.Failed(SpeedTestState.Reason.UNREACHABLE), engine(f).run {})
        assertEquals(0L, f.downloaded.get())
    }

    @Test
    fun `timeout - pings answer but no byte ever arrives`() = runBlocking {
        val silent = object : SpeedTestTransport {
            override suspend fun ping() = true
            override suspend fun download(onChunk: (Int) -> Boolean) = awaitCancellation()
        }
        val cfg = SpeedTestConfig(connections = 2, firstByteTimeoutMs = 200, maxDownloadMs = 300)
        assertEquals(SpeedTestState.Failed(SpeedTestState.Reason.TIMEOUT), SpeedTestEngine(silent, cfg).run {})
    }

    @Test
    fun `cancel - stops the download promptly`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reads = AtomicLong(0)
        val endless = object : SpeedTestTransport {
            override suspend fun ping() = true
            override suspend fun download(onChunk: (Int) -> Boolean) {
                started.complete(Unit)
                while (true) { yield(); reads.incrementAndGet(); if (!onChunk(1)) return }
            }
        }
        val job = async(Dispatchers.Default) { SpeedTestEngine(endless, SpeedTestConfig(maxDownloadMs = 60_000)).run {} }
        started.await()
        job.cancel()
        runCatching { job.await() }
        val after = reads.get()
        Thread.sleep(100)
        assertTrue(job.isCancelled)
        assertEquals("no reads after cancel", after, reads.get())
    }

    @Test
    fun `rating bands are conservative`() {
        assertEquals(SpeedRating.POOR, SpeedTestEngine.rate(9.9))
        assertEquals(SpeedRating.FAIR, SpeedTestEngine.rate(10.0))
        assertEquals(SpeedRating.GOOD, SpeedTestEngine.rate(25.0))
        assertEquals(SpeedRating.EXCELLENT, SpeedTestEngine.rate(50.0))
        assertEquals(8.0, SpeedTestEngine.mbps(1_000_000, 1_000), 1e-9)
    }
}
