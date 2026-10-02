package tv.own.owntv.features.sports.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Narrow client for the public, read-only GoatTV Sports API on DigitalOcean. No credentials: the API
 * serves sanitized schedule metadata only. Channel data is NOT requested in Phase C1 (no `?brand=`,
 * see [SportsChannelFeature]).
 */
class SportsApiClient(
    sharedClient: OkHttpClient,
    private val baseUrl: String = BASE_URL,
) {
    // The app's shared client (proxy/DNS/UA honoured) with tighter timeouts: a slow Sports API must
    // degrade to "temporarily unavailable", never hold the screen.
    private val http: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun home(): SportsApiResult<SportsHomePayload> = get("/sports/home", SportsApiParser::parseHome)

    suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload> =
        get("/sports/events?since=${URLEncoder.encode(cursor, "UTF-8")}", SportsApiParser::parseEventsPage)

    suspend fun leagues(): SportsApiResult<List<SportsLeague>> = get("/sports/leagues", SportsApiParser::parseLeaguesPage)

    /**
     * Every enabled competition of one sport in [fromMs, toMs) (`/sports/events?sport=`). Used for
     * sports whose fixtures are days apart (soccer), which the 48h /sports/home window would hide.
     */
    suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> =
        get(eventsForSportPath(sport, fromMs, toMs), SportsApiParser::parseEventsPage)

    private suspend fun <T> get(path: String, parse: (String) -> T?): SportsApiResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .get()
            .build()
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

        internal fun eventsForSportPath(sport: String, fromMs: Long, toMs: Long): String =
            "/sports/events?sport=" + URLEncoder.encode(sport, "UTF-8") +
                "&from=" + URLEncoder.encode(isoUtc(fromMs), "UTF-8") +
                "&to=" + URLEncoder.encode(isoUtc(toMs), "UTF-8")

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
