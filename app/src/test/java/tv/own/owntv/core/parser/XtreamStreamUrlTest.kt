package tv.own.owntv.core.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.diagnostics.SensitiveText
import tv.own.owntv.core.epg.CatchupUrl
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import java.util.TimeZone

/**
 * Xtream stream URLs carry the account as two path segments. Credentials with `#`, `?`, `/` or `%` used
 * to cut the URL short, so nothing played (upstream OwnTV_Core 6d656bd1). Synthetic accounts only.
 */
class XtreamStreamUrlTest {

    private val client = XtreamClient(HttpClient(OkHttpClient()))
    private val host = "http://panel.invalid:8080"

    private fun source(user: String, pass: String) =
        SourceEntity(id = 1, name = "Synthetic", type = SourceType.XTREAM, url = "$host/", username = user, password = pass)

    @Test
    fun `plain credentials keep exactly the URLs they had`() {
        val s = source("synthUser42", "synth.Pass-99_x~")
        assertEquals("$host/live/synthUser42/synth.Pass-99_x~/1234.ts", client.liveUrl(s, "1234"))
        assertEquals("$host/movie/synthUser42/synth.Pass-99_x~/55.mkv", client.movieUrl(s, "55", "mkv"))
        assertEquals("$host/series/synthUser42/synth.Pass-99_x~/77.mp4", client.seriesEpisodeUrl(s, "77", null))
        assertEquals(
            "$host/timeshift/synthUser42/synth.Pass-99_x~/60/2026-01-01:00-00/1234.ts",
            client.timeshiftUrl(s, "1234", 1_767_225_600_000L, 60),
        )
    }

    @Test
    fun `usual punctuation that a path segment allows stays raw`() {
        assertEquals("a+b!c\$d&e'f(g)h*i,j;k=l:m@n", xtreamPathSegment("a+b!c\$d&e'f(g)h*i,j;k=l:m@n"))
    }

    @Test
    fun `characters that would cut the URL are percent-encoded`() {
        assertEquals("p%23ss%2Fw%3Frd%25", xtreamPathSegment("p#ss/w?rd%"))
        assertEquals("two%20words", xtreamPathSegment("two words"))
        assertEquals("caf%C3%A9", xtreamPathSegment("café"))
        assertEquals("", xtreamPathSegment(null))

        val s = source("synth user", "Synth#Pass/9?%")
        assertEquals("$host/live/synth%20user/Synth%23Pass%2F9%3F%25/1234.ts", client.liveUrl(s, "1234"))
        assertEquals("$host/movie/synth%20user/Synth%23Pass%2F9%3F%25/55.mp4", client.movieUrl(s, "55", null))
        assertEquals("$host/series/synth%20user/Synth%23Pass%2F9%3F%25/77.mp4", client.seriesEpisodeUrl(s, "77", "mp4"))
        assertEquals(
            "$host/timeshift/synth%20user/Synth%23Pass%2F9%3F%25/60/2026-01-01:00-00/1234.ts",
            client.timeshiftUrl(s, "1234", 1_767_225_600_000L, 60),
        )
    }

    @Test
    fun `encoded credentials are still fully masked by the redaction rules`() {
        val s = source("synthUser42", "Synth#Pass/9?%")
        val urls = listOf(
            client.liveUrl(s, "1234"),
            client.movieUrl(s, "55", "mkv"),
            client.seriesEpisodeUrl(s, "77", null),
            client.timeshiftUrl(s, "1234", 1_767_225_600_000L, 60),
        )
        for (url in urls) {
            val masked = SensitiveText.redact("GET failed url=$url")
            assertTrue(masked, masked.contains(SensitiveText.MASK))
            for (leak in listOf("synthUser42", "Synth", "Pass", "%23", "%2F")) {
                assertFalse("leaked '$leak' in: $masked", masked.contains(leak))
            }
        }
    }

    @Test
    fun `timeshift php fallback re-encodes the credentials as query values`() {
        val s = source("synth user", "a+b&c=d#e/f")
        val path = client.timeshiftUrl(s, "1234", 1_767_225_600_000L, 15)
        assertEquals(
            "$host/streaming/timeshift.php?username=synth+user&password=a%2Bb%26c%3Dd%23e%2Ff" +
                "&stream=1234&start=2026-01-01:00-00&duration=15",
            CatchupUrl.timeshiftPhpAlternate(path),
        )
    }

    @Test
    fun `M3U xc catch-up re-encodes credentials taken from the live URL`() {
        val url = CatchupUrl.forM3u(
            "$host/live/synthUser42/a%2Bb%26c/1234.ts", "xc", null,
            1_767_225_600_000L, 1_767_229_200_000L, TimeZone.getTimeZone("UTC"),
        )
        assertEquals(
            "$host/streaming/timeshift.php?username=synthUser42&password=a%2Bb%26c&stream=1234&start=2026-01-01:00-00&duration=60",
            url,
        )
    }
}
