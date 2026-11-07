package tv.own.owntv.core.diagnostics

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.player.LiveDiagnosticsLog

/** Synthetic credentials only: nothing here is, or resembles, a real account. */
class SensitiveTextTest {
    private val user = "synthUser42"
    private val pass = "synthPass99"
    private val secrets = listOf(user, pass, "synthTok3n77", "AA:BB:CC:11:22:33", "synthCookie5")

    private fun assertClean(text: String) {
        for (s in secrets) assertFalse("leaked '$s' in: $text", text.contains(s, ignoreCase = true))
    }

    @Test
    fun `Xtream path credentials are masked in every URL form`() {
        val urls = listOf(
            "http://panel.invalid:8080/live/$user/$pass/12345.ts",
            "http://panel.invalid:8080/live/$user/$pass/12345.m3u8",
            "https://panel.invalid/movie/$user/$pass/987.mkv",
            "https://panel.invalid/series/$user/$pass/55.mp4",
            "http://panel.invalid/timeshift/$user/$pass/30/2026-11-05:18-00/32609.ts",
            "http://panel.invalid:8080/$user/$pass/12345",
            "http://panel.invalid:8080/$user/$pass/12345.ts?token=synthTok3n77",
        )
        for (u in urls) {
            val r = SensitiveText.redact(u)
            assertClean(r)
            assertTrue(r, r.contains("panel.invalid")) // host stays for diagnostics
        }
        assertEquals(
            "http://panel.invalid:8080/live/•••/•••/12345.ts",
            SensitiveText.redact("http://panel.invalid:8080/live/$user/$pass/12345.ts"),
        )
        assertEquals("http://panel.invalid:8080/•••/•••/12345", SensitiveText.redact("http://panel.invalid:8080/$user/$pass/12345"))
    }

    @Test
    fun `query parameters, userinfo, headers and bearer tokens are masked`() {
        val inputs = listOf(
            "http://panel.invalid/get.php?username=$user&password=$pass&type=m3u_plus",
            "http://panel.invalid/player_api.php?user=$user&pass=$pass&action=get_live_streams",
            "http://panel.invalid/timeshift.php?username=$user&password=$pass&stream=1&start=2026-11-05:18-00",
            "http://$user:$pass@proxy.invalid:3128",
            "socks5://$user:$pass@proxy.invalid:1080/",
            "http://portal.invalid/play/live.php?mac=AA:BB:CC:11:22:33&stream=77&extension=ts&play_token=synthTok3n77",
            "https://cdn.invalid/seg.ts?access_token=synthTok3n77&expires=1700000000",
            "Authorization: Bearer synthTok3n77",
            "authorization=Basic synthTok3n77xyz",
            "Cookie: PHPSESSID=synthCookie5; mac=AA:BB:CC:11:22:33",
            "http-header-fields: Authorization: Bearer synthTok3n77",
            "device AA:BB:CC:11:22:33 handshake",
        )
        for (i in inputs) assertClean(SensitiveText.redact(i))
    }

    @Test
    fun `mpv log lines keep their diagnostic value`() {
        val opening = SensitiveText.redact("cplayer: Opening http://panel.invalid:8080/live/$user/$pass/12345.ts")
        assertClean(opening)
        assertTrue(opening.startsWith("cplayer: Opening http://panel.invalid:8080/live/"))
        assertTrue(opening.endsWith("/12345.ts"))
        for (benign in listOf(
            "ffmpeg: http: HTTP error 404 Not Found",
            "playback stats: container-fps=59.940060 frame-drops=0 cache=9.568000s",
            "playback restart complete @ 00:12:34.567, audio=playing, video=playing",
            "decode check: hwdec-current='mediacodec' height=1080px direct=true",
            "vd: Using hardware decoding (mediacodec). 1920x1080 yuv420p",
        )) assertEquals(benign, SensitiveText.redact(benign))
    }

    @Test
    fun `throwables are redacted through the whole cause chain`() {
        val t = IOException(
            "open failed: http://panel.invalid/live/$user/$pass/1.ts",
            IllegalStateException("retry http://panel.invalid/get.php?username=$user&password=$pass"),
        )
        val trace = SensitiveText.stackTrace(t)
        assertClean(trace)
        assertTrue(trace.contains("IOException"))
        assertTrue(trace.contains("IllegalStateException"))
        assertTrue(trace.contains("SensitiveTextTest")) // frames are kept
        val described = SensitiveText.describe(t)
        assertClean(described)
        assertTrue(described.contains("IOException: open failed: http://panel.invalid/live/•••/•••/1.ts"))
    }

    @Test
    fun `existing call sites get the shared redactor`() {
        assertClean(HttpClient.redactUrl("http://panel.invalid:8080/$user/$pass/12345"))
        assertClean(HttpClient.redactUrl("http://portal.invalid/play/live.php?mac=AA:BB:CC:11:22:33&play_token=synthTok3n77"))
    }

    @Test
    fun `the live diagnostics ring never stores credentials`() {
        LiveDiagnosticsLog.event("play() url=http://panel.invalid/live/$user/$pass/12345.ts muted=false")
        LiveDiagnosticsLog.event("mpv level=20 ffmpeg: Opening http://$user:$pass@panel.invalid/x?password=$pass")
        val snap = LiveDiagnosticsLog.snapshot()
        assertClean(snap)
        assertTrue(snap.contains("muted=false"))
    }
}
