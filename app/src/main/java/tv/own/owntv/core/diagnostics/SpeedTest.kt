package tv.own.owntv.core.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * User-started internet speed test (latency + download) for the Diagnostic Center.
 *
 * Bounded by design: the download phase stops at [SpeedTestConfig.maxDownloadMs] or after
 * [SpeedTestConfig.maxBytes] in total, whichever comes first, and the first
 * [SpeedTestConfig.warmupMs] (TCP slow start) are excluded from the average. Cancelling the calling
 * coroutine stops every request at its next read. The network side is a [SpeedTestTransport], so the
 * engine is independent of which endpoint ends up hosting the test file.
 */
interface SpeedTestTransport {
    /** One tiny request; true when it got an answer. Its wall time is one latency sample. */
    suspend fun ping(): Boolean

    /**
     * Streams the test file, calling [onChunk] with each chunk's byte count. Stops as soon as [onChunk]
     * returns false. Must be cancellable.
     */
    suspend fun download(onChunk: (bytes: Int) -> Boolean)
}

data class SpeedTestConfig(
    val latencySamples: Int = 5,
    val connections: Int = 3,
    val warmupMs: Long = 1_000,
    val maxDownloadMs: Long = 6_000,
    /**
     * Bytes counted by the engine. Data already in flight when the cap is hit still arrives (a few MB on
     * a fast line with three streams), so 25 MiB keeps a completed test at roughly 30–32 MB on the wire.
     */
    val maxBytes: Long = 25L * 1024 * 1024,
    /** No byte at all within this long = the endpoint is not answering. */
    val firstByteTimeoutMs: Long = 5_000,
    /** If every stream failed, a measurement shorter than this is too short to trust. */
    val minValidMs: Long = 2_000,
    val liveWindowMs: Long = 1_000,
)

enum class SpeedRating { EXCELLENT, GOOD, FAIR, POOR }

data class SpeedTestResult(
    val latencyMs: Long?,
    val downloadMbps: Double,
    val bytesUsed: Long,
    val durationMs: Long,
    val rating: SpeedRating,
)

sealed interface SpeedTestState {
    data object Idle : SpeedTestState
    data class Latency(val sample: Int, val of: Int) : SpeedTestState
    data class Downloading(
        val currentMbps: Double,
        val elapsedMs: Long,
        val maxMs: Long,
        val bytes: Long,
        val latencyMs: Long? = null,
    ) : SpeedTestState
    data class Done(val result: SpeedTestResult) : SpeedTestState
    data class Failed(val reason: Reason) : SpeedTestState

    /**
     * Why a test could not produce a measurement. None of these says anything about the customer's
     * line — the test endpoint may simply be unavailable or rate-limited — so a failure is never rated.
     */
    enum class Reason {
        /** No latency request got an answer. */
        UNREACHABLE,
        /** The endpoint answered the download with an error (e.g. HTTP 429/403/5xx) or cut every stream early. */
        ENDPOINT_ERROR,
        /** Requests were accepted but not a single byte arrived in time. */
        TIMEOUT,
    }
}

class SpeedTestEngine(
    private val transport: SpeedTestTransport,
    private val config: SpeedTestConfig = SpeedTestConfig(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Runs one test, reporting every state change to [onState]; returns the final state. */
    suspend fun run(onState: (SpeedTestState) -> Unit): SpeedTestState {
        val latency = measureLatency(onState)
        if (latency == null) return SpeedTestState.Failed(SpeedTestState.Reason.UNREACHABLE).also(onState)
        return measureDownload(latency, onState).also(onState)
    }

    private suspend fun measureLatency(onState: (SpeedTestState) -> Unit): Long? {
        val samples = ArrayList<Long>()
        for (i in 1..config.latencySamples) {
            onState(SpeedTestState.Latency(i, config.latencySamples))
            val start = clockMs()
            val ok = runCatchingNonCancel { transport.ping() } == true
            if (ok) samples += clockMs() - start
        }
        if (samples.isEmpty()) return null
        return samples.sorted()[samples.size / 2]
    }

    private suspend fun measureDownload(latencyMs: Long, onState: (SpeedTestState) -> Unit): SpeedTestState {
        val total = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val streamErrors = java.util.concurrent.atomic.AtomicInteger(0)
        val start = clockMs()
        var warmupBytes = -1L
        var warmupAt = 0L
        val window = ArrayDeque<Pair<Long, Long>>() // (timeMs, cumulative bytes)
        val lock = Any()

        fun onBytes(n: Int): Boolean {
            if (stop.get()) return false
            val now = clockMs()
            val elapsed = now - start
            val bytes = total.addAndGet(n.toLong())
            synchronized(lock) {
                // Warm-up (connection set-up + TCP slow start) ends after warmupMs, or earlier on a line
                // fast enough to reach the byte cap first: then the first third of the cap is the warm-up.
                if (warmupBytes < 0 && (elapsed >= config.warmupMs || bytes >= config.maxBytes / WARMUP_CAP_DIVISOR)) {
                    warmupBytes = bytes; warmupAt = now
                }
                window.addLast(now to bytes)
                while (window.size > 1 && now - window.first().first > config.liveWindowMs) window.removeFirst()
                val (t0, b0) = window.first()
                val live = if (now > t0) mbps(bytes - b0, now - t0) else 0.0
                onState(SpeedTestState.Downloading(live, elapsed, config.maxDownloadMs, bytes, latencyMs))
            }
            if (bytes >= config.maxBytes || elapsed >= config.maxDownloadMs) stop.set(true)
            return !stop.get()
        }

        val finished = withTimeoutOrNull(config.maxDownloadMs + config.firstByteTimeoutMs) {
            coroutineScope {
                (1..config.connections).map {
                    async {
                        runCatchingNonCancel { transport.download(::onBytes); true } ?: streamErrors.incrementAndGet()
                    }
                }.let { jobs ->
                    // Fail fast when nothing at all arrives.
                    val gotFirstByte = withTimeoutOrNull(config.firstByteTimeoutMs) {
                        while (total.get() == 0L && jobs.any { it.isActive }) kotlinx.coroutines.delay(20)
                        total.get() > 0
                    } == true
                    if (!gotFirstByte) { stop.set(true); jobs.forEach { it.cancel() }; return@coroutineScope false }
                    jobs.awaitAll()
                    true
                }
            }
        }
        stop.set(true)
        val end = clockMs()
        val bytes = total.get()
        val allStreamsFailed = streamErrors.get() >= config.connections
        if (bytes == 0L && streamErrors.get() > 0) return SpeedTestState.Failed(SpeedTestState.Reason.ENDPOINT_ERROR)
        if (finished == false || bytes == 0L) return SpeedTestState.Failed(SpeedTestState.Reason.TIMEOUT)
        // Every stream was cut off early (throttled / rejected mid-way): too little to rate the line.
        if (allStreamsFailed && end - start < config.minValidMs) return SpeedTestState.Failed(SpeedTestState.Reason.ENDPOINT_ERROR)
        // Average after warm-up; a run too short to pass warm-up (stream ended early) uses all of it.
        val mbps = if (warmupBytes in 0 until bytes && end > warmupAt) mbps(bytes - warmupBytes, end - warmupAt)
        else mbps(bytes, maxOf(1, end - start))
        return SpeedTestState.Done(SpeedTestResult(latencyMs, mbps, bytes, end - start, rate(mbps)))
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val WARMUP_CAP_DIVISOR = 3

        fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / 1_000_000.0 / (ms / 1000.0)

        /**
         * Conservative bands from measured download alone. They describe the line, not playback: the
         * screen always adds that speed is only one factor in streaming performance.
         */
        fun rate(mbps: Double): SpeedRating = when {
            mbps >= 50 -> SpeedRating.EXCELLENT
            mbps >= 25 -> SpeedRating.GOOD
            mbps >= 10 -> SpeedRating.FAIR
            else -> SpeedRating.POOR
        }
    }
}
