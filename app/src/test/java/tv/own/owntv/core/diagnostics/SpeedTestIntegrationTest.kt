package tv.own.owntv.core.diagnostics

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The real OkHttp transport against a local HTTP server standing in for the R2 objects, plus the engine
 * behaviours added for the R2 integration (endpoint failure vs slow line, warm-up, parallel streams).
 */
class SpeedTestIntegrationTest {

    private lateinit var server: HttpServer
    private val requests = ConcurrentLinkedQueue<Map<String, String>>()
    private val served = AtomicLong(0)
    @Volatile private var downloadStatus = 200
    @Volatile private var pingStatus = 200
    /** > 0: the download is served slowly (one 64 KiB chunk per this many ms), like a slow line. */
    @Volatile private var chunkDelayMs = 0L
    private val fileSize = 32L * 1024 * 1024

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ping.bin") { ex ->
            requests += ex.requestHeaders.mapValues { it.value.joinToString() } + ("path" to ex.requestURI.toString())
            val body = ByteArray(1024)
            ex.sendResponseHeaders(pingStatus, if (pingStatus == 200) body.size.toLong() else -1)
            if (pingStatus == 200) ex.responseBody.use { it.write(body) } else ex.close()
        }
        server.createContext("/speedtest.bin") { ex ->
            requests += ex.requestHeaders.mapValues { it.value.joinToString() } + ("path" to ex.requestURI.toString())
            if (downloadStatus != 200) { ex.sendResponseHeaders(downloadStatus, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(200, fileSize)
            val chunk = ByteArray(64 * 1024)
            try {
                ex.responseBody.use { out ->
                    var sent = 0L
                    while (sent < fileSize) {
                        out.write(chunk); out.flush(); sent += chunk.size; served.addAndGet(chunk.size.toLong())
                        if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                    }
                }
            } catch (e: IOException) { /* client hung up: expected on cap / cancel */ }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val endpoint get() = SpeedTestEndpoint("http://127.0.0.1:${server.address.port}/")
    private fun transport() = OkHttpSpeedTestTransport(OkHttpClient(), endpoint)

    // --- endpoint configuration ---

    @Test
    fun `endpoint - both objects derive from one base URL, trailing slash or not`() {
        val a = SpeedTestEndpoint("https://speed.example.org")
        val b = SpeedTestEndpoint("https://speed.example.org/")
        assertEquals("https://speed.example.org/ping.bin", a.pingUrl)
        assertEquals("https://speed.example.org/speedtest.bin", a.downloadUrl)
        assertEquals(a.pingUrl, b.pingUrl)
        assertEquals(a.downloadUrl, b.downloadUrl)
    }

    @Test
    fun `configured endpoint is public HTTPS with no credentials, keys or query`() {
        val url = SpeedTestEndpoint.configured().baseUrl
        assertTrue(url.startsWith("https://"))
        assertFalse("no userinfo", url.substringAfter("://").contains('@'))
        assertFalse("no query", url.contains('?'))
        listOf("token", "key", "secret", "account", "access").forEach { assertFalse(url.lowercase().contains(it)) }
    }

    // --- real transport ---

    @Test
    fun `full test over the real transport - bounded bytes, forced network, identity encoding, no nonce`() = runBlocking {
        val cap = 4L * 1024 * 1024
        val t = transport()
        val end = SpeedTestEngine(t, SpeedTestConfig(maxBytes = cap, maxDownloadMs = 4_000)).run {}
        t.release()
        assertTrue("$end", end is SpeedTestState.Done)
        val r = (end as SpeedTestState.Done).result
        assertTrue("counted ${r.bytesUsed}", r.bytesUsed <= cap + 3 * 64 * 1024)
        val downloads = requests.filter { it["path"]!!.startsWith("/speedtest.bin") }
        assertEquals(3, downloads.size)
        requests.forEach { h ->
            assertEquals("identity", h["Accept-encoding"] ?: h["Accept-Encoding"])
            assertEquals("no-cache", h["Cache-control"] ?: h["Cache-Control"])
            assertFalse("no cache-busting query", h["path"]!!.contains('?'))
        }
        assertEquals(5, requests.count { it["path"] == "/ping.bin" })
    }

    @Test
    fun `rate limited download (HTTP 429) is an endpoint failure, never a slow line`() = runBlocking {
        downloadStatus = 429
        val end = SpeedTestEngine(transport()).run {}
        assertEquals(SpeedTestState.Failed(SpeedTestState.Reason.ENDPOINT_ERROR), end)
    }

    @Test
    fun `rejected latency requests (HTTP 403) - unreachable, no download attempted`() = runBlocking {
        pingStatus = 403
        val end = SpeedTestEngine(transport()).run {}
        assertEquals(SpeedTestState.Failed(SpeedTestState.Reason.UNREACHABLE), end)
        assertTrue(requests.none { it["path"]!!.startsWith("/speedtest.bin") })
    }

    @Test
    fun `release in the finally of a cancelled test returns at once, lets cleanup run and closes every socket`() {
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "main") }
        val dispatcher = main.asCoroutineDispatcher()
        try {
            runBlocking(dispatcher) {
                val t = transport()
                val started = CompletableDeferred<Unit>()
                val cleanedUp = CompletableDeferred<String>()
                val job = launch {
                    try {
                        SpeedTestEngine(t, SpeedTestConfig(maxDownloadMs = 60_000, maxBytes = Long.MAX_VALUE)).run { st ->
                            if (st is SpeedTestState.Downloading) started.complete(Unit)
                        }
                    } finally {
                        // As in the ViewModel: release, then reset the UI state, in the finally of a
                        // cancelled coroutine on the main thread. The reset must still run.
                        t.release()
                        cleanedUp.complete(Thread.currentThread().name)
                    }
                }
                withTimeout(10_000) { started.await() }
                job.cancelAndJoin()
                val cleanupThread = withTimeout(1_000) { cleanedUp.await() }
                assertTrue(cleanupThread, cleanupThread.startsWith("main"))
                val deadline = System.currentTimeMillis() + 5_000
                while (t.openConnections() > 0 && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
                assertEquals(0, t.openConnections())
            }
        } finally {
            main.shutdownNow()
        }
    }

    @Test
    fun `cancel on a slow line aborts the in-flight download at once - no reading on to the time limit`() = runBlocking {
        chunkDelayMs = 20 // ~26 Mbps per stream: far below the cap, so only cancel can end it early
        val downloading = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            SpeedTestEngine(transport(), SpeedTestConfig(maxDownloadMs = 60_000, maxBytes = Long.MAX_VALUE)).run { st ->
                if (st is SpeedTestState.Downloading) downloading.complete(Unit)
            }
        }
        withTimeout(10_000) { downloading.await() }
        val cancelledAt = System.currentTimeMillis()
        job.cancel()
        runCatching { job.join() }
        assertTrue("cancel took ${System.currentTimeMillis() - cancelledAt} ms", System.currentTimeMillis() - cancelledAt < 1_000)
        Thread.sleep(300) // let the server notice the closed sockets
        val settled = served.get()
        Thread.sleep(1_000)
        assertEquals("server kept sending after cancel", settled, served.get())
    }

    @Test
    fun `cancel stops the real download promptly`() = runBlocking {
        val downloading = CompletableDeferred<Unit>()
        val counted = AtomicLong(0)
        val job = async(Dispatchers.Default) {
            SpeedTestEngine(transport(), SpeedTestConfig(maxDownloadMs = 60_000, maxBytes = Long.MAX_VALUE)).run { st ->
                if (st is SpeedTestState.Downloading) { counted.set(st.bytes); downloading.complete(Unit) }
            }
        }
        withTimeout(10_000) { downloading.await() }
        job.cancel()
        runCatching { job.await() }
        val atCancel = served.get()
        Thread.sleep(500)
        assertTrue(job.isCancelled)
        // The server may flush what was already in socket buffers, but no further sustained transfer.
        assertTrue("kept serving after cancel: ${served.get() - atCancel}", served.get() - atCancel < 8L * 1024 * 1024)
    }

    // --- engine behaviours (fake transport) ---

    private class Scripted(
        val clock: AtomicLong,
        val chunkMs: (connection: Int, sent: Long) -> Long,
        val failAfterBytes: Long = Long.MAX_VALUE,
        val failConnections: Set<Int> = emptySet(),
    ) : SpeedTestTransport {
        private val next = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun ping(): Boolean { clock.addAndGet(20); return true }
        override suspend fun download(onChunk: (Int) -> Boolean) {
            val id = next.getAndIncrement()
            var sent = 0L
            while (true) {
                yield()
                if (id in failConnections && sent >= failAfterBytes) throw IOException("cut")
                clock.addAndGet(chunkMs(id, sent))
                sent += 65_536
                if (!onChunk(65_536)) return
            }
        }
    }

    @Test
    fun `all streams cut off early - endpoint failure, not a Poor rating`() = runBlocking {
        val clock = AtomicLong()
        val t = Scripted(clock, { _, _ -> 10 }, failAfterBytes = 128 * 1024, failConnections = setOf(0, 1, 2))
        val end = SpeedTestEngine(t, SpeedTestConfig()) { clock.get() }.run {}
        assertEquals(SpeedTestState.Failed(SpeedTestState.Reason.ENDPOINT_ERROR), end)
    }

    @Test
    fun `one stream failing while others continue still gives a result`() = runBlocking {
        val clock = AtomicLong()
        val t = Scripted(clock, { _, _ -> 10 }, failAfterBytes = 0, failConnections = setOf(1))
        val end = SpeedTestEngine(t, SpeedTestConfig(maxDownloadMs = 3_000)) { clock.get() }.run {}
        assertTrue("$end", end is SpeedTestState.Done)
    }

    @Test
    fun `warm-up is excluded - a slow first second does not drag the average down`() = runBlocking {
        val clock = AtomicLong()
        // First second: 100 ms per chunk (~5 Mbps); afterwards 10 ms per chunk (~52 Mbps).
        val t = Scripted(clock, { _, _ -> if (clock.get() < 1_100) 100 else 10 })
        val done = SpeedTestEngine(t, SpeedTestConfig(connections = 1, maxDownloadMs = 4_000)) { clock.get() }.run {} as SpeedTestState.Done
        assertTrue("got ${done.result.downloadMbps}", done.result.downloadMbps > 40)
    }

    @Test
    fun `fast line reaching the byte cap before 1 s - connection set-up is still excluded`() = runBlocking {
        val clock = AtomicLong()
        // 350 ms of set-up before the first byte, then 1 ms per 64 KiB chunk (~524 Mbps); the 30 MiB cap
        // is reached well before the 1 s warm-up would end.
        var first = true
        val t = Scripted(clock, { _, _ -> if (first) { first = false; 350 } else 1 })
        val done = SpeedTestEngine(t, SpeedTestConfig(connections = 1)) { clock.get() }.run {} as SpeedTestState.Done
        assertTrue("took ${done.result.durationMs} ms", done.result.durationMs < 1_000)
        assertTrue("got ${done.result.downloadMbps}", done.result.downloadMbps > 480)
    }

    @Test
    fun `parallel streams add up`() = runBlocking {
        val one = AtomicLong(); val three = AtomicLong()
        val r1 = SpeedTestEngine(Scripted(one, { _, _ -> 10 }), SpeedTestConfig(connections = 1, maxDownloadMs = 3_000, maxBytes = Long.MAX_VALUE)) { one.get() }.run {} as SpeedTestState.Done
        val r3 = SpeedTestEngine(Scripted(three, { _, _ -> 10 }), SpeedTestConfig(connections = 3, maxDownloadMs = 3_000, maxBytes = Long.MAX_VALUE)) { three.get() / 3 }.run {} as SpeedTestState.Done
        assertTrue("1 stream ${r1.result.downloadMbps}, 3 streams ${r3.result.downloadMbps}", r3.result.downloadMbps > r1.result.downloadMbps * 2)
    }

    @Test
    fun `run again is a completely new measurement - no stale counters`() = runBlocking {
        val clock = AtomicLong()
        val t = Scripted(clock, { _, _ -> 10 })
        val engine = { SpeedTestEngine(t, SpeedTestConfig(connections = 1, maxDownloadMs = 2_000)) { clock.get() } }
        val first = engine().run {} as SpeedTestState.Done
        val firstStates = mutableListOf<SpeedTestState>()
        val second = engine().run { firstStates += it } as SpeedTestState.Done
        val firstDownload = firstStates.filterIsInstance<SpeedTestState.Downloading>().first()
        assertTrue("second run starts from zero", firstDownload.bytes <= 65_536)
        assertEquals(first.result.bytesUsed, second.result.bytesUsed, 3 * 65_536.0.toLong().toDouble())
    }

    @Test
    fun `download progress carries the measured latency`() = runBlocking {
        val clock = AtomicLong()
        val states = mutableListOf<SpeedTestState>()
        SpeedTestEngine(Scripted(clock, { _, _ -> 10 }), SpeedTestConfig(connections = 1, maxDownloadMs = 1_500)) { clock.get() }.run { states += it }
        val d = states.filterIsInstance<SpeedTestState.Downloading>().last()
        assertEquals(20L, d.latencyMs)
        assertNotNull(d)
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta)
}
