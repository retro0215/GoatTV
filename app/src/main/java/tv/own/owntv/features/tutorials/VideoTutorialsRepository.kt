package tv.own.owntv.features.tutorials

import android.content.Context
import org.json.JSONObject
import tv.own.owntv.rooms.BrandResolver

/** Key/value storage for the cached catalog (SharedPreferences in the app, a map in tests). */
interface VideoTutorialsCacheStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class SharedPrefsVideoTutorialsCacheStore(context: Context) : VideoTutorialsCacheStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    private companion object { const val FILE = "video_tutorials_cache" }
}

/** A catalog loaded from the cache, with the time it was fetched. */
data class CachedVideoTutorials(val tutorials: List<VideoTutorial>, val fetchedAtMs: Long)

/** What a refresh produced. */
sealed interface VideoTutorialsRefresh {
    /** Fresh catalog from the server (already cached). */
    data class Updated(val tutorials: List<VideoTutorial>) : VideoTutorialsRefresh
    /** The cached catalog is recent enough; no request was made. */
    data object UpToDate : VideoTutorialsRefresh
    /** The device has no connection; no request was made. */
    data object Offline : VideoTutorialsRefresh
    data class Failed(val reason: VideoTutorialsFetch.Reason) : VideoTutorialsRefresh
}

/**
 * The brand's tutorial catalog with a small offline cache.
 *
 * - The cache entry is keyed by endpoint scope AND brand, and stores both inside the entry; a read
 *   that does not match both is ignored, so another brand's (or staging's) catalog is never shown.
 * - [refresh] skips the request when the cached copy is younger than [minRefreshIntervalMs] (unless
 *   forced by Retry) or when the device is offline, so re-opening the screen does not re-download.
 * - A function missing on the server (404) is treated as an empty catalog and is not cached.
 */
class VideoTutorialsRepository(
    private val fetcher: suspend (VideoTutorialsEndpoint, String) -> VideoTutorialsFetch,
    private val store: VideoTutorialsCacheStore,
    private val endpoint: () -> VideoTutorialsEndpoint = VideoTutorialsEndpoints::current,
    private val brandId: () -> String = { BrandResolver.resolveBrandId() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val minRefreshIntervalMs: Long = MIN_REFRESH_INTERVAL_MS,
) {
    constructor(api: VideoTutorialsApi, store: VideoTutorialsCacheStore) : this(api::fetch, store)

    val isStaging: Boolean get() = endpoint().isStaging

    fun cached(): CachedVideoTutorials? {
        val ep = endpoint()
        val brand = brandId()
        val raw = store.read(cacheKey(ep, brand)) ?: return null
        return decodeEntry(raw, ep.cacheScope, brand)
    }

    suspend fun refresh(force: Boolean, online: Boolean): VideoTutorialsRefresh {
        val ep = endpoint()
        val brand = brandId()
        if (!online) return VideoTutorialsRefresh.Offline
        if (!force) {
            val cached = cached()
            val age = cached?.let { clock() - it.fetchedAtMs }
            if (age != null && age in 0 until minRefreshIntervalMs) return VideoTutorialsRefresh.UpToDate
        }
        return when (val result = fetcher(ep, brand)) {
            is VideoTutorialsFetch.Success -> {
                store.write(cacheKey(ep, brand), encodeEntry(ep.cacheScope, brand, clock(), result.body))
                VideoTutorialsRefresh.Updated(result.tutorials)
            }
            is VideoTutorialsFetch.Failure ->
                if (result.reason == VideoTutorialsFetch.Reason.NOT_AVAILABLE) VideoTutorialsRefresh.Updated(emptyList())
                else VideoTutorialsRefresh.Failed(result.reason)
        }
    }

    companion object {
        const val MIN_REFRESH_INTERVAL_MS = 60_000L

        internal fun cacheKey(endpoint: VideoTutorialsEndpoint, brandId: String) = "catalog_${endpoint.cacheScope}_$brandId"

        internal fun encodeEntry(scope: String, brandId: String, fetchedAtMs: Long, body: String): String =
            JSONObject().put("scope", scope).put("brand", brandId).put("fetchedAt", fetchedAtMs).put("body", body).toString()

        internal fun decodeEntry(raw: String, scope: String, brandId: String): CachedVideoTutorials? = runCatching {
            val entry = JSONObject(raw)
            if (entry.optString("scope") != scope || entry.optString("brand") != brandId) return null
            val tutorials = VideoTutorialsParser.parse(entry.optString("body")) ?: return null
            CachedVideoTutorials(tutorials, entry.optLong("fetchedAt", 0L))
        }.getOrNull()
    }
}
