package tv.own.owntv.core.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.network.HttpClient

/**
 * Runs on Android's own regex engine (ICU), which the JVM unit tests do not use: in 83c72047 SensitiveText's
 * MAC rule compiled on the JVM but threw on every device, which made the class unloadable and stopped every
 * HttpClient request. Synthetic credentials only.
 */
@RunWith(AndroidJUnit4::class)
class SensitiveTextAndroidTest {
    private val user = "synthUser42"
    private val pass = "synthPass99"
    private val secrets = listOf(user, pass, "synthTok3n77", "AA:BB:CC:11:22:33", "AA-BB-CC-11-22-33", "synthCookie5")

    private fun assertClean(text: String) {
        for (s in secrets) assertFalse("leaked '$s' in: $text", text.contains(s, ignoreCase = true))
    }

    @Test
    fun thisEngineRejectsThe83c72047MacPattern() {
        // Proves the test runs on ICU: the JVM accepts this pattern.
        try {
            Pattern.compile("""(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[:\-]){5}[0-9a-f]{2}(?![0-9a-f:])""")
            fail("expected ICU to reject the 83c72047 MAC pattern")
        } catch (expected: PatternSyntaxException) {
        }
    }

    @Test
    fun everyRuleCompilesAndSensitiveTextInitializes() {
        val failed = SensitiveText.PATTERNS.mapNotNull { (name, p) ->
            try { Pattern.compile(p); null } catch (e: PatternSyntaxException) { "$name: ${e.description}" }
        }
        assertEquals(emptyList<String>(), failed)
        assertTrue(SensitiveText.available)
        assertNull(SensitiveText.unavailableRule)
    }

    @Test
    fun syntheticCredentialsAreMasked() {
        val inputs = listOf(
            "http://panel.invalid:8080/live/$user/$pass/12345.ts",
            "https://panel.invalid/movie/$user/$pass/987.mkv",
            "http://panel.invalid/timeshift/$user/$pass/30/2026-11-05:18-00/32609.ts",
            "http://panel.invalid:8080/$user/$pass/12345.ts?token=synthTok3n77",
            "http://panel.invalid/get.php?username=$user&password=$pass&type=m3u_plus",
            "http://$user:$pass@proxy.invalid:3128",
            "http://portal.invalid/play/live.php?mac=AA:BB:CC:11:22:33&stream=77&play_token=synthTok3n77",
            "Authorization: Bearer synthTok3n77",
            "Cookie: PHPSESSID=synthCookie5; mac=AA:BB:CC:11:22:33",
            "device AA:BB:CC:11:22:33 handshake",
            "device AA-BB-CC-11-22-33 handshake",
        )
        for (i in inputs) {
            val r = SensitiveText.redact(i)
            assertClean(r)
            assertFalse(r, r == SensitiveText.WITHHELD)
        }
        assertEquals("http://panel.invalid:8080/live/•••/•••/12345.ts", SensitiveText.redact(inputs[0]))
        val benign = "vd: Using hardware decoding (mediacodec). 1920x1080 yuv420p @ 00:12:34.567"
        assertEquals(benign, SensitiveText.redact(benign))
        val t = IOException("open failed: ${inputs[0]}", IllegalStateException(inputs[4]))
        assertClean(SensitiveText.stackTrace(t))
        assertTrue(SensitiveText.describe(t).contains("IOException: open failed: http://panel.invalid:8080/live/•••/•••/"))
    }

    @Test
    fun aRuleIcuRejectsWithholdsTextWithoutThrowing() {
        val shippedMac = """(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[:\-]){5}[0-9a-f]{2}(?![0-9a-f:])"""
        val broken = Redactor(SensitiveText.PATTERNS.map { (n, p) -> if (n == "MAC_ADDRESS") n to shippedMac else n to p })
        assertFalse(broken.available)
        assertEquals("MAC_ADDRESS", broken.unavailableRule)
        assertEquals(SensitiveText.WITHHELD, broken.redact("http://panel.invalid/live/$user/$pass/1.ts"))
        val trace = broken.stackTrace(IOException("password=$pass"))
        assertClean(trace)
        assertTrue(trace.contains("java.io.IOException"))
    }

    @Test
    fun aRequestWithCredentialsInItsUrlStillCompletes() = runBlocking {
        var seen: String? = null
        val ok = OkHttpClient.Builder().addInterceptor { chain ->
            seen = chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val url = "http://panel.invalid/player_api.php?username=$user&password=$pass&action=get_live_categories"
        assertEquals("[]", HttpClient(ok).getText(url))
        assertEquals(url, seen) // the request itself is untouched; only log text is redacted
    }
}
