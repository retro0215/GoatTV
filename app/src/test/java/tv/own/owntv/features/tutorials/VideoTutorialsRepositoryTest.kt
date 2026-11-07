package tv.own.owntv.features.tutorials

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTutorialsRepositoryTest {

    private class MapStore : VideoTutorialsCacheStore {
        val map = HashMap<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) { map[key] = value }
    }

    private val production = VideoTutorialsEndpoint("https://aaaaaaaaaaaaaaaaaaaa.supabase.co", "prod-key", isStaging = false)
    private val staging = VideoTutorialsEndpoint("https://bbbbbbbbbbbbbbbbbbbb.supabase.co", "staging-key", isStaging = true)

    private fun body(vararg ids: String) = ids.joinToString(",", "[", "]") {
        """{"id":"$it","title":"T $it","youtube_video_id":"dQw4w9WgXcQ","sort_order":0}"""
    }

    private class Harness(
        val store: MapStore = MapStore(),
        var endpoint: VideoTutorialsEndpoint,
        var brand: String = "goat",
        var now: Long = 1_000_000L,
        var answer: (VideoTutorialsEndpoint, String) -> VideoTutorialsFetch,
    ) {
        val calls = ArrayList<Pair<String, String>>()
        val repo = VideoTutorialsRepository(
            fetcher = { ep, b -> calls += ep.cacheScope to b; answer(ep, b) },
            store = store,
            endpoint = { endpoint },
            brandId = { brand },
            clock = { now },
        )
    }

    private fun success(body: String) = VideoTutorialsFetch.Success(VideoTutorialsParser.parse(body)!!, body)

    @Test
    fun `a successful refresh is cached for that brand and endpoint only`() = runBlocking {
        val h = Harness(endpoint = production, answer = { _, _ -> success(body("g1")) })
        val r = h.repo.refresh(force = false, online = true)
        assertTrue(r is VideoTutorialsRefresh.Updated)
        assertEquals(listOf("production" to "goat"), h.calls)
        assertEquals(listOf("g1"), h.repo.cached()!!.tutorials.map { it.id })

        h.brand = "supreme" // another brand never sees goat's cache
        assertNull(h.repo.cached())
        h.brand = "goat"
        h.endpoint = staging // staging never sees production's cache
        assertNull(h.repo.cached())
    }

    @Test
    fun `a cache entry copied under another brand's key is still refused`() {
        val store = MapStore()
        val goatKey = VideoTutorialsRepository.cacheKey(production, "goat")
        store.write(VideoTutorialsRepository.cacheKey(production, "supreme"),
            VideoTutorialsRepository.encodeEntry("production", "goat", 1L, body("x")))
        val repo = VideoTutorialsRepository({ _, _ -> error("no network") }, store, { production }, { "supreme" }, { 2L })
        assertNull(repo.cached())
        assertNull(store.read(goatKey))
    }

    @Test
    fun `re-entering within a minute does not refetch, Retry does, and changes replace the cache`() = runBlocking {
        var server = body("a")
        val h = Harness(endpoint = production, answer = { _, _ -> success(server) })
        h.repo.refresh(force = false, online = true)
        h.now += 30_000
        assertEquals(VideoTutorialsRefresh.UpToDate, h.repo.refresh(force = false, online = true))
        assertEquals(1, h.calls.size)

        server = body("a", "b")
        val forced = h.repo.refresh(force = true, online = true) as VideoTutorialsRefresh.Updated
        assertEquals(listOf("a", "b"), forced.tutorials.map { it.id })
        assertEquals(2, h.calls.size)

        h.now += VideoTutorialsRepository.MIN_REFRESH_INTERVAL_MS
        server = body("b")
        h.repo.refresh(force = false, online = true)
        assertEquals(3, h.calls.size)
        assertEquals(listOf("b"), h.repo.cached()!!.tutorials.map { it.id })
    }

    @Test
    fun `offline makes no request and keeps the cached catalog`() = runBlocking {
        val h = Harness(endpoint = production, answer = { _, _ -> success(body("a")) })
        h.repo.refresh(force = false, online = true)
        h.now += 10 * 60_000
        assertEquals(VideoTutorialsRefresh.Offline, h.repo.refresh(force = true, online = false))
        assertEquals(1, h.calls.size)
        assertEquals(listOf("a"), h.repo.cached()!!.tutorials.map { it.id })
    }

    @Test
    fun `server failures keep the cache and a missing RPC reads as empty without being cached`() = runBlocking {
        val h = Harness(endpoint = production, answer = { _, _ -> success(body("a")) })
        h.repo.refresh(force = false, online = true)
        h.answer = { _, _ -> VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.SERVER) }
        assertEquals(VideoTutorialsRefresh.Failed(VideoTutorialsFetch.Reason.SERVER), h.repo.refresh(force = true, online = true))
        assertEquals(listOf("a"), h.repo.cached()!!.tutorials.map { it.id })

        val fresh = Harness(endpoint = production, answer = { _, _ -> VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.NOT_AVAILABLE) })
        assertEquals(VideoTutorialsRefresh.Updated(emptyList()), fresh.repo.refresh(force = false, online = true))
        assertNull(fresh.repo.cached())
    }

    @Test
    fun `an unreachable server is a network failure, not a crash`() = runBlocking {
        val api = VideoTutorialsApi(OkHttpClient())
        val unreachable = VideoTutorialsEndpoint("http://127.0.0.1:1", "key", isStaging = false)
        assertEquals(VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.NETWORK), api.fetch(unreachable, "goat"))
    }

    @Test
    fun `screen state transitions`() {
        val cached = CachedVideoTutorials(VideoTutorialsParser.parse(body("a"))!!, 1L)
        val loading = VideoTutorialsState.initial(null, staging = false)
        assertEquals(VideoTutorialsUiState.Phase.LOADING, loading.phase)
        assertEquals(VideoTutorialsUiState.Phase.CONTENT, VideoTutorialsState.initial(cached, false).phase)

        val updated = VideoTutorialsState.after(loading, null, VideoTutorialsRefresh.Updated(cached.tutorials))
        assertEquals(VideoTutorialsUiState.Phase.CONTENT, updated.phase)
        assertEquals(VideoTutorialsUiState.Phase.EMPTY, VideoTutorialsState.after(loading, null, VideoTutorialsRefresh.Updated(emptyList())).phase)
        assertEquals(VideoTutorialsUiState.Phase.OFFLINE, VideoTutorialsState.after(loading, null, VideoTutorialsRefresh.Offline).phase)
        val failed = VideoTutorialsRefresh.Failed(VideoTutorialsFetch.Reason.NETWORK)
        assertEquals(VideoTutorialsUiState.Phase.ERROR, VideoTutorialsState.after(loading, null, failed).phase)

        val savedOffline = VideoTutorialsState.after(loading, cached, VideoTutorialsRefresh.Offline)
        assertEquals(VideoTutorialsUiState.Phase.CONTENT, savedOffline.phase)
        assertEquals(VideoTutorialsUiState.SavedNotice.OFFLINE, savedOffline.savedNotice)
        assertEquals(VideoTutorialsUiState.SavedNotice.ERROR, VideoTutorialsState.after(loading, cached, failed).savedNotice)
        assertNull(VideoTutorialsState.after(loading, cached, VideoTutorialsRefresh.UpToDate).savedNotice)
        assertTrue(VideoTutorialsState.initial(null, staging = true).let { VideoTutorialsState.after(it, null, VideoTutorialsRefresh.Offline).staging })
    }
}
