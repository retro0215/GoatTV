package tv.own.owntv.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class UpdateCheckTest {

    // --- release selection (the existing rules, now testable) ---

    private fun release(tag: String, vararg apks: String, draft: Boolean = false, pre: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("draft", draft).put("prerelease", pre).put("body", "notes $tag")
        .put("assets", JSONArray(apks.map { JSONObject().put("name", it).put("browser_download_url", "https://dl/$it") }))

    /** Newest first, like GitHub: all four brands share one repository's release list. */
    private val releases = JSONArray(
        listOf(
            release("supreme-v4.4.25", "Supreme-TV-v4.4.25.apk", "Supreme-TV-x86_64-v4.4.25.apk", draft = true),
            release("5star-v4.4.26", "5Star-Ultra-v4.4.26.apk", pre = true),
            release("supreme-v4.4.24", "Supreme-TV-v4.4.24.apk", "Supreme-TV-x86_64-v4.4.24.apk"),
            release("allaccess-v4.4.24", "AllAccess-v4.4.24.apk", "AllAccess-x86_64-v4.4.24.apk"),
            release("5star-v4.4.24", "5Star-Ultra-v4.4.24.apk", "5Star-Ultra-x86_64-v4.4.24.apk"),
            release("v4.4.23", "GoatTV-v4.4.23.apk", "GoatTV-x86_64-v4.4.23.apk", "GoatTV.apk"),
            release("allaccess-v4.4.23", "AllAccess-v4.4.23.apk"),
        ),
    ).toString()

    private fun select(prefix: String, apk: String, x86: Boolean = false, pre: Boolean = false) =
        ReleaseSelection.select(releases, prefix, includePrereleases = pre, apkPrefix = apk, wantX86 = x86)

    @Test
    fun `each brand follows only its own release track`() {
        assertEquals("4.4.23", select("v", "GoatTV").version)
        assertEquals("4.4.24", select("allaccess-v", "AllAccess").version)
        assertEquals("4.4.24", select("5star-v", "5Star-Ultra").version)
        assertEquals("4.4.24", select("supreme-v", "Supreme-TV").version)
        assertEquals("https://dl/GoatTV-v4.4.23.apk", select("v", "GoatTV").apkUrl)
        assertEquals("https://dl/AllAccess-v4.4.24.apk", select("allaccess-v", "AllAccess").apkUrl)
        assertEquals("https://dl/5Star-Ultra-v4.4.24.apk", select("5star-v", "5Star-Ultra").apkUrl)
        assertEquals("https://dl/Supreme-TV-v4.4.24.apk", select("supreme-v", "Supreme-TV").apkUrl)
    }

    @Test
    fun `drafts are never offered, pre-releases only to debug or diagnostic builds, x86_64 gets its own APK`() {
        assertEquals("4.4.24", select("supreme-v", "Supreme-TV").version)          // 4.4.25 is a draft
        assertEquals("4.4.24", select("5star-v", "5Star-Ultra").version)           // 4.4.26 is a pre-release
        assertEquals("4.4.26", select("5star-v", "5Star-Ultra", pre = true).version)
        assertEquals("https://dl/GoatTV-x86_64-v4.4.23.apk", select("v", "GoatTV", x86 = true).apkUrl)
    }

    @Test
    fun `current version is up to date, a newer release is available and never suppressed`() {
        assertFalse(ReleaseSelection.isNewer("4.4.24", "4.4.24"))
        assertFalse(ReleaseSelection.isNewer("4.4.23", "4.4.24"))
        assertTrue(ReleaseSelection.isNewer("4.4.25", "4.4.24"))
        assertTrue(ReleaseSelection.isNewer("4.10.0", "4.9.9"))
        assertTrue(ReleaseSelection.isNewer(select("allaccess-v", "AllAccess").version, "4.4.23"))
    }

    @Test
    fun `unusable responses are answers, not network failures`() {
        assertThrows(InvalidReleaseResponseException::class.java) { ReleaseSelection.select("", "v", false, "GoatTV", false) }
        assertThrows(InvalidReleaseResponseException::class.java) { ReleaseSelection.select("<html>", "v", false, "GoatTV", false) }
        assertThrows(NoCompatibleApkException::class.java) { ReleaseSelection.select(releases, "acme-v", false, "Acme", false) }
        assertFalse(UpdateManager.isTransient(InvalidReleaseResponseException()))
        assertFalse(UpdateManager.isTransient(NoCompatibleApkException()))
        assertFalse(UpdateManager.isTransient(CheckHttpException(403)))
        assertTrue(UpdateManager.isTransient(IOException("unexpected end of stream")))
        assertTrue(UpdateManager.isTransient(SocketTimeoutException("timeout")))
    }

    // --- bounded retry ---

    @Test
    fun `transient failure followed by success - retried once`() = runBlocking {
        val calls = AtomicInteger()
        val out = UpdateManager.withNetworkRetry(backoffMs = 1) {
            if (calls.incrementAndGet() == 1) throw IOException("unexpected end of stream") else "ok"
        }
        assertEquals("ok", out)
        assertEquals(2, calls.get())
    }

    @Test
    fun `persistent network failure - gives up after the bounded attempts`() {
        val calls = AtomicInteger()
        assertThrows(IOException::class.java) {
            runBlocking { UpdateManager.withNetworkRetry<String>(backoffMs = 1) { calls.incrementAndGet(); throw IOException("down") } }
        }
        assertEquals(UpdateManager.CHECK_ATTEMPTS, calls.get())
    }

    @Test
    fun `timeout - retried once, then reported`() {
        val calls = AtomicInteger()
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking { UpdateManager.withNetworkRetry<String>(backoffMs = 1) { calls.incrementAndGet(); throw SocketTimeoutException("timeout") } }
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun `HTTP errors are not retried`() {
        val calls = AtomicInteger()
        assertThrows(CheckHttpException::class.java) {
            runBlocking { UpdateManager.withNetworkRetry<String>(backoffMs = 1) { calls.incrementAndGet(); throw CheckHttpException(503) } }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `cancellation stops the check - no retry`() = runBlocking {
        val calls = AtomicInteger()
        val job = async {
            UpdateManager.withNetworkRetry(backoffMs = 60_000) {
                calls.incrementAndGet(); throw IOException("dropped")
            }
        }
        delay(200)
        job.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        assertEquals(1, calls.get())
    }

    // --- the root cause: a pooled connection the server has already closed ---

    private var server: ServerSocket? = null

    @After
    fun stopServer() { server?.close() }

    /**
     * HTTP/1.1 server that, like api.github.com, keeps a connection alive after answering but closes it
     * once it has been idle for [idleCloseMs] (GitHub: ~30 s) — without sending "Connection: close".
     */
    private fun keepAliveServer(idleCloseMs: Long, connections: AtomicInteger): Int {
        val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { server = it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    sock.use {
                        val input = it.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read(); if (b < 0) return@use; head.append(b.toChar())
                        }
                        val body = "[]"
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray(),
                        )
                        it.getOutputStream().flush()
                        Thread.sleep(idleCloseMs)
                    }
                }
            }
        }
        return s.localPort
    }

    /** Same settings as the app's shared client (DataModule): HTTP/1.1, OkHttp retries off. */
    private fun sharedClient() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private fun get(client: OkHttpClient, port: Int) =
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/releases").build()).execute().use { it.body.string() }

    @Test
    fun `shared client reuses a connection the server closed - the reported failure`() {
        val connections = AtomicInteger()
        val port = keepAliveServer(idleCloseMs = 200, connections)
        val client = sharedClient()
        assertEquals("[]", get(client, port))
        Thread.sleep(600) // idle longer than the server keeps the connection
        // Android reports "unexpected end of stream"; other OSes may report a reset/aborted connection.
        assertThrows(IOException::class.java) { get(client, port) }
    }

    @Test
    fun `updater client recovers from a connection the server closed`() {
        val connections = AtomicInteger()
        val port = keepAliveServer(idleCloseMs = 200, connections)
        val client = UpdateManager.updateClient(sharedClient())
        assertEquals("[]", get(client, port))
        Thread.sleep(600)
        assertEquals("[]", get(client, port))
        assertEquals("[]", get(client, port))
        assertTrue(connections.get() >= 2)
    }

    @Test
    fun `updater pool expires idle connections before GitHub closes them`() {
        assertTrue(UpdateManager.KEEP_ALIVE_SECONDS < 30)
    }
}
