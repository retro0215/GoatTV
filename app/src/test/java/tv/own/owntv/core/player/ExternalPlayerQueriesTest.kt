package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android 11+ package visibility for "Play in external player" (upstream OwnTV_Core 1a79756a, 99d09664).
 *
 * queryIntentActivities only sees apps matching a manifest `<queries>` entry, so every intent shape the
 * launcher tries must be declared, or VLC / MX Player are reported as "not installed".
 */
class ExternalPlayerQueriesTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    /** (mimeType, scheme) of each `<queries><intent>` with action VIEW. */
    private val viewQueries: List<Pair<String?, String?>> = run {
        val queries = manifest.getElementsByTagName("queries")
        assertEquals("one <queries> block", 1, queries.length)
        val intents = (queries.item(0) as Element).getElementsByTagName("intent")
        (0 until intents.length).map { intents.item(it) as Element }.filter { intent ->
            val actions = intent.getElementsByTagName("action")
            (0 until actions.length).any {
                (actions.item(it) as Element).getAttributeNS(androidNs, "name") == "android.intent.action.VIEW"
            }
        }.map { intent ->
            val data = intent.getElementsByTagName("data").item(0) as Element
            data.getAttributeNS(androidNs, "mimeType").ifBlank { null } to data.getAttributeNS(androidNs, "scheme").ifBlank { null }
        }
    }

    private fun mimeCovered(mime: String) = viewQueries.any { (m, _) ->
        m == mime || (m != null && m.endsWith("/*") && mime.startsWith(m.removeSuffix("*")))
    }

    @Test
    fun `every network scheme the launcher hands over is queryable`() {
        val schemes = viewQueries.mapNotNull { it.second }.toSet()
        ExternalPlayerLauncher.NETWORK_SCHEMES.forEach { assertTrue("scheme $it", it in schemes) }
    }

    @Test
    fun `every MIME the launcher offers is queryable`() {
        listOf(
            "http://h.invalid/live/u/p/1.ts", "http://h.invalid/live/u/p/1.m3u8",
            "http://h.invalid/movie/u/p/2.mp4", "http://h.invalid/movie/u/p/2.mkv",
            "http://h.invalid/series/u/p/3.avi", "http://h.invalid/x.mts?token=1",
        ).flatMap { ExternalPlayerLauncher.mimeCandidates(it) }.filterNotNull().distinct().forEach {
            assertTrue("mime $it", mimeCovered(it))
        }
    }

    @Test
    fun `live and VOD URLs keep their MIME ladder`() {
        assertEquals(listOf("video/mp2t", "video/*", null), ExternalPlayerLauncher.mimeCandidates("http://h.invalid/live/u/p/1.ts"))
        assertEquals(listOf("application/x-mpegURL", "video/*", null), ExternalPlayerLauncher.mimeCandidates("http://h.invalid/1.m3u8?x=1"))
        assertEquals(listOf("video/*", null), ExternalPlayerLauncher.mimeCandidates("http://h.invalid/movie/u/p/2.mkv"))
    }

    @Test
    fun `the manifest asks for visibility only, never for every package`() {
        val perms = manifest.getElementsByTagName("uses-permission")
        val names = (0 until perms.length).map { (perms.item(it) as Element).getAttributeNS(androidNs, "name") }
        assertFalse("QUERY_ALL_PACKAGES" in names.joinToString())
    }
}
