package tv.own.owntv.features.sports.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.cancellation.CancellationException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Narrow client for the public, read-only GoatTV Sports API on DigitalOcean. No credentials: the API
 * serves sanitized schedule metadata only. Event requests carry the build's brand (`?brand=goat` in
 * GoatTV, see [SportsChannelFeature]) so events include their matched `channels[]`; the brand id is
 * the only thing sent — never a username, password, server, playlist or stream URL.
 */
class SportsApiClient(
    sharedClient: OkHttpClient,
    private val baseUrl: String = BASE_URL,
    /** `?brand=` for event requests; null = unbranded (no channel data). */
    private val brand: String? = SportsChannelFeature.BRAND,
) {
    // The app's shared client (proxy/DNS/UA honoured) with tighter timeouts: a slow Sports API must
    // degrade to "temporarily unavailable", never hold the screen.
    private val http: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun home(): SportsApiResult<SportsHomePayload> = get(homePath(brand), SportsApiParser::parseHome)

    /** Branded: the backend also returns events whose matched channels changed since [cursor]. */
    suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload> =
        get(sincePath(cursor, brand), SportsApiParser::parseEventsPage)

    suspend fun leagues(): SportsApiResult<List<SportsLeague>> = get("/sports/leagues", SportsApiParser::parseLeaguesPage)

    /**
     * Every enabled competition of one sport in [fromMs, toMs) (`/sports/events?sport=`). Used for
     * sports whose fixtures are days apart (soccer), which the 48h /sports/home window would hide.
     */
    suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> =
        get(eventsForSportPath(sport, fromMs, toMs, brand), SportsApiParser::parseEventsPage)

    /**
     * One event's Game Center (`/sports/events/{evt_id}/game-center`). Cancelling the calling coroutine
     * cancels the HTTP call itself, so a card the user has already left never keeps a request alive.
     */
    internal suspend fun gameCenter(eventId: String): SportsApiResult<GameCenterResponseDto> =
        getCancellable("/sports/events/" + URLEncoder.encode(eventId, "UTF-8") + "/game-center") { GameCenterApi.parse(it, eventId) }

    private fun request(path: String): Request = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .header("Accept", "application/json")
        .get()
        .build()

    private suspend fun <T> getCancellable(path: String, parse: (String) -> T?): SportsApiResult<T> = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request(path))
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return // cancelled by us: nothing to report
                Log.w(TAG, "GET ${path.substringBefore('?')} failed: ${e.javaClass.simpleName}")
                cont.resume(SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                val result: SportsApiResult<T> = try {
                    response.use { r ->
                        interpret(r.code, r.header("Content-Type"), r.header("Retry-After"), r.body.string(), parse)
                    }
                } catch (e: IOException) {
                    SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
                } catch (e: RuntimeException) {
                    SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
                }
                if (result !is SportsApiResult.Success) Log.w(TAG, "GET ${path.substringBefore('?')} → ${result.describe()}")
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    private suspend fun <T> get(path: String, parse: (String) -> T?): SportsApiResult<T> = withContext(Dispatchers.IO) {
        val request = request(path)
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                interpret(
                    code = response.code,
                    contentType = response.header("Content-Type"),
                    retryAfter = response.header("Retry-After"),
                    body = body,
                    parse = parse,
                ).also { if (it !is SportsApiResult.Success) Log.w(TAG, "GET ${path.substringBefore('?')} → ${it.describe()}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "GET ${path.substringBefore('?')} failed: ${e.javaClass.simpleName}")
            SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
        } catch (e: RuntimeException) {
            // Never let an unexpected client error escape into the refresh loop / UI.
            Log.w(TAG, "GET ${path.substringBefore('?')} error: ${e.javaClass.simpleName}")
            SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
        }
    }

    companion object {
        /** Production Sports API (DigitalOcean App Platform). Public, read-only, no secrets. */
        const val BASE_URL = "https://goattv-sports-lnc6f.ondigitalocean.app"
        private const val TAG = "SportsApi"

        internal fun homePath(brand: String?): String = withBrand("/sports/home", brand)

        internal fun sincePath(cursor: String, brand: String?): String =
            withBrand("/sports/events?since=" + URLEncoder.encode(cursor, "UTF-8"), brand)

        internal fun eventsForSportPath(sport: String, fromMs: Long, toMs: Long, brand: String? = null): String = withBrand(
            "/sports/events?sport=" + URLEncoder.encode(sport, "UTF-8") +
                "&from=" + URLEncoder.encode(isoUtc(fromMs), "UTF-8") +
                "&to=" + URLEncoder.encode(isoUtc(toMs), "UTF-8"),
            brand,
        )

        /** Appends `brand=<id>` (validated brand ids only); unbranded paths are unchanged. */
        internal fun withBrand(path: String, brand: String?): String {
            val b = SportsChannelFeature.brandOrNull(brand) ?: return path
            return path + (if ('?' in path) "&" else "?") + "brand=" + b
        }

        /** Epoch millis → `yyyy-MM-ddTHH:mm:ssZ` (UTC, second precision; no java.time on minSdk 24). */
        internal fun isoUtc(ms: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(ms))

        /**
         * Pure response interpretation (unit-tested). DigitalOcean's edge may replace a JSON 503 with an
         * HTML 504 page, so ANY non-JSON reply and ANY 5xx is "temporarily unavailable" — HTML is never
         * parsed or shown.
         */
        internal fun <T> interpret(
            code: Int,
            contentType: String?,
            retryAfter: String?,
            body: String,
            parse: (String) -> T?,
        ): SportsApiResult<T> {
            val isJson = contentType?.contains("json", ignoreCase = true) == true || body.trimStart().startsWith("{")
            return when {
                code == 429 -> SportsApiResult.RateLimited(retryAfter?.trim()?.toLongOrNull()?.coerceIn(1, 600) ?: 60)
                code >= 500 -> SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER)
                !isJson -> SportsApiResult.Unavailable(SportsApiResult.Reason.NOT_JSON)
                code in 400..499 -> SportsApiResult.Rejected(code, SportsApiParser.parseError(body))
                code !in 200..299 -> SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER)
                else -> parse(body)?.let { SportsApiResult.Success(it) } ?: SportsApiResult.Unavailable(SportsApiResult.Reason.MALFORMED)
            }
        }
    }
}

sealed interface SportsApiResult<out T> {
    data class Success<T>(val value: T) : SportsApiResult<T>

    /** Transient: network failure, 5xx, HTML/non-JSON reply or unparseable JSON. Keep last-known-good. */
    data class Unavailable(val reason: Reason) : SportsApiResult<Nothing>

    /** 4xx with a JSON error (e.g. 400 "since is too old; reload /home."). */
    data class Rejected(val code: Int, val error: String?) : SportsApiResult<Nothing>

    data class RateLimited(val retryAfterSeconds: Long) : SportsApiResult<Nothing>

    enum class Reason { NETWORK, SERVER, NOT_JSON, MALFORMED }

    fun describe(): String = when (this) {
        is Success -> "ok"
        is Unavailable -> "unavailable(${reason.name.lowercase()})"
        is Rejected -> "rejected($code)"
        is RateLimited -> "rate-limited(${retryAfterSeconds}s)"
    }
}
