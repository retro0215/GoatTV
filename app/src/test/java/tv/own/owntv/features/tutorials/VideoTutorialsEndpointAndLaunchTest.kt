package tv.own.owntv.features.tutorials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTutorialsEndpointAndLaunchTest {
    private val prodUrl = "https://aaaaaaaaaaaaaaaaaaaa.supabase.co"
    private val stagingUrl = "https://bbbbbbbbbbbbbbbbbbbb.supabase.co"

    private fun resolve(debug: Boolean, on: Boolean, url: String = stagingUrl, key: String = "sb_publishable_x") =
        VideoTutorialsEndpoints.resolve(debug, on, url, key, prodUrl, "prod-key")

    @Test
    fun `release builds always read production, whatever the switch says`() {
        val ep = resolve(debug = false, on = true)
        assertEquals(prodUrl, ep.baseUrl)
        assertEquals("prod-key", ep.anonKey)
        assertFalse(ep.isStaging)
    }

    @Test
    fun `debug builds read staging only when switched on with valid staging settings`() {
        assertFalse(resolve(debug = true, on = false).isStaging)
        val ep = resolve(debug = true, on = true)
        assertTrue(ep.isStaging)
        assertEquals(stagingUrl, ep.baseUrl)
        assertEquals("staging", ep.cacheScope)
        assertFalse(resolve(debug = true, on = true, url = "", key = "").isStaging)
        assertFalse(resolve(debug = true, on = true, url = prodUrl).isStaging)
        assertFalse(resolve(debug = true, on = true, url = "$prodUrl/").isStaging)
        assertFalse(resolve(debug = true, on = true, key = "sb_secret_abc").isStaging)
        assertFalse(resolve(debug = true, on = true, url = "http://bbbbbbbbbbbbbbbbbbbb.supabase.co").isStaging)
        assertFalse(resolve(debug = true, on = true, url = "https://evil.example").isStaging)
    }

    @Test
    fun `launch order prefers the YouTube TV app and ends with a browser link`() {
        val c = YouTubeLauncher.candidates("_y_Xpps46Uc")
        assertEquals(
            listOf(YouTubeLauncher.YOUTUBE_ANDROID_TV, YouTubeLauncher.YOUTUBE_FIRE_TV, YouTubeLauncher.YOUTUBE_MOBILE, null, null),
            c.map { it.packageName },
        )
        assertEquals("https://www.youtube.com/watch?v=_y_Xpps46Uc", c[0].uri)
        assertEquals("vnd.youtube:_y_Xpps46Uc", c[3].uri)
        assertEquals("https://www.youtube.com/watch?v=_y_Xpps46Uc", c[4].uri)
    }

    @Test
    fun `the first app that accepts the link is used`() {
        val tried = ArrayList<YouTubeLaunchCandidate>()
        val result = YouTubeLauncher.launch("_y_Xpps46Uc") { tried += it; it.packageName == YouTubeLauncher.YOUTUBE_MOBILE }
        assertEquals(3, tried.size)
        assertEquals(YouTubeLauncher.YOUTUBE_MOBILE, (result as YouTubeLaunchResult.Launched).candidate.packageName)
    }

    @Test
    fun `no compatible app is reported after every candidate was tried`() {
        var tries = 0
        assertEquals(YouTubeLaunchResult.NoCompatibleApp, YouTubeLauncher.launch("_y_Xpps46Uc") { tries++; false })
        assertEquals(5, tries)
    }

    @Test
    fun `invalid ids never reach an intent`() {
        for (bad in listOf("", "short", "https://evil.example/x", "dQw4w9WgXcQ&x=1", "dQw4w9WgXc/", "javascript:x")) {
            var called = false
            assertEquals(bad, YouTubeLaunchResult.InvalidVideo, YouTubeLauncher.launch(bad) { called = true; true })
            assertFalse(bad, called)
        }
    }
}
