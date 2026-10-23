package tv.own.owntv.features.tutorials

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of one catalog request. */
sealed interface VideoTutorialsFetch {
    /** [body] is the validated JSON array, kept for the offline cache. */
    data class Success(val tutorials: List<VideoTutorial>, val body: String) : VideoTutorialsFetch
    data class Failure(val reason: Reason) : VideoTutorialsFetch

    enum class Reason {
        /** No connection, timeout, DNS or TLS failure. */
        NETWORK,
        /** The RPC is not installed on this project yet (PostgREST 404 / PGRST202). */
        NOT_AVAILABLE,
        /** Any other non-2xx answer. */
        SERVER,
        /** 2xx, but not the expected JSON array. */
        BAD_RESPONSE,
    }
}

/**
 * Reads `public.get_video_tutorials(p_brand_id)` over PostgREST with the build's public anon /
 * publishable key — the same key every customer APK already carries. Sends only the brand id; no user
 * token, profile or device data. Uses the app's shared OkHttpClient (proxy / DNS / trust anchors
 * honoured) with tighter timeouts so a slow server degrades to "couldn't load" quickly.
 */
class VideoTutorialsApi(sharedClient: OkHttpClient) {
    private val http: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(endpoint: VideoTutorialsEndpoint, brandId: String): VideoTutorialsFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.baseUrl + RPC_PATH)
            .header("apikey", endpoint.anonKey)
            .header("Accept", "application/json")
            .post(requestBody(brandId).toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                interpret(response.code, response.body.string())
                    .also { if (it is VideoTutorialsFetch.Failure) Log.w(TAG, "catalog → ${it.reason} (HTTP ${response.code})") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "catalog request failed: ${e.javaClass.simpleName}")
            VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.NETWORK)
        } catch (e: RuntimeException) {
            Log.w(TAG, "catalog request error: ${e.javaClass.simpleName}")
            VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.NETWORK)
        }
    }

    companion object {
        internal const val RPC_PATH = "/rest/v1/rpc/get_video_tutorials"
        private const val TAG = "VideoTutorials"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun requestBody(brandId: String): String = JSONObject().put("p_brand_id", brandId).toString()

        internal fun interpret(code: Int, body: String): VideoTutorialsFetch = when {
            code in 200..299 -> VideoTutorialsParser.parse(body)
                ?.let { VideoTutorialsFetch.Success(it, body) }
                ?: VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.BAD_RESPONSE)
            code == 404 -> VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.NOT_AVAILABLE)
            else -> VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.SERVER)
        }
    }
}
