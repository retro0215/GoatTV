package tv.own.owntv.core.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Where the Diagnostic Center's speed test downloads from: two public, unauthenticated objects under
 * one base URL. The base URL is the only thing to change when moving hosts (BuildConfig
 * SPEED_TEST_BASE_URL); the object names stay the same. Never shown to customers or put in reports.
 */
data class SpeedTestEndpoint(val baseUrl: String) {
    val pingUrl: String get() = baseUrl.trimEnd('/') + SEPARATOR + PING_OBJECT
    val downloadUrl: String get() = baseUrl.trimEnd('/') + SEPARATOR + DOWNLOAD_OBJECT

    companion object {
        /** 1 KiB object: one latency sample per request. */
        const val PING_OBJECT = "ping.bin"
        /** 32 MiB of incompressible data: larger than the 25 MiB test cap, so a stream never runs dry. */
        const val DOWNLOAD_OBJECT = "speedtest.bin"
        private const val SEPARATOR = "/"

        fun configured(): SpeedTestEndpoint = SpeedTestEndpoint(tv.own.owntv.BuildConfig.SPEED_TEST_BASE_URL)
    }
}

/**
 * OkHttp transport for [SpeedTestEngine], on the app's shared client (so the proxy / custom DNS
 * settings apply) with its own small connection pool and short connect/read timeouts.
 *
 * Caching: every request uses [CacheControl.FORCE_NETWORK], so an HTTP cache on the device can never
 * answer a repeat test, and asks for `Accept-Encoding: identity`, so the counted bytes are the bytes
 * that crossed the network. No cache-busting query string is added: the CDN edge serving the same
 * object is exactly the network path being measured, and a nonce would push every test to the origin.
 *
 * Cancellation: cancelling the calling coroutine cancels the HTTP call and stops the read loop at the
 * next chunk, so a cancelled test stops consuming bandwidth almost immediately.
 */
class OkHttpSpeedTestTransport(
    sharedClient: OkHttpClient,
    private val endpoint: SpeedTestEndpoint,
) : SpeedTestTransport {

    private val client: OkHttpClient = sharedClient.newBuilder()
        .connectionPool(ConnectionPool(MAX_IDLE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .cacheControl(CacheControl.FORCE_NETWORK)
        .header(ACCEPT_ENCODING, IDENTITY)
        .get()
        .build()

    override suspend fun ping(): Boolean = try {
        execute(request(endpoint.pingUrl)) { response ->
            if (!response.isSuccessful) return@execute false
            response.body.bytes() // 1 KiB: the full round trip is the latency sample
            true
        }
    } catch (e: IOException) {
        false
    }

    override suspend fun download(onChunk: (bytes: Int) -> Boolean) {
        execute(request(endpoint.downloadUrl)) { response ->
            if (!response.isSuccessful) throw IOException(HTTP_ERROR + response.code)
            val buffer = ByteArray(CHUNK)
            response.body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) return@execute
                    if (!onChunk(n)) return@execute
                }
            }
        }
    }

    /**
     * Runs the blocking call and [block] on IO. Cancelling the caller interrupts the wait at once and
     * cancels the call, which aborts the socket and so unblocks a connect or a pending read: a cancelled
     * test stops downloading immediately instead of running on to its time or byte limit.
     */
    private suspend fun <T> execute(req: Request, block: (Response) -> T): T = coroutineScope {
        val call = client.newCall(req)
        val work = async(Dispatchers.IO) { call.execute().use(block) }
        try {
            work.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    /**
     * Closes the sockets left by a finished or cancelled test. Safe to call from the main thread and
     * from the `finally` of a cancelled test (Cancel / Back): closing a TLS socket writes to the
     * network, so the close is handed to OkHttp's own background executor and this returns at once.
     */
    fun release() {
        client.dispatcher.executorService.execute { client.connectionPool.evictAll() }
    }

    internal fun openConnections(): Int = client.connectionPool.connectionCount()

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_IDLE = 3
        const val KEEP_ALIVE_SECONDS = 30L
        const val ACCEPT_ENCODING = "Accept-Encoding"
        const val IDENTITY = "identity"
        const val HTTP_ERROR = "HTTP "
    }
}
