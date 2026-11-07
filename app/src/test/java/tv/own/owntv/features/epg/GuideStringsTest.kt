package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Resource text the guide depends on. Android trims unquoted leading/trailing spaces from string
 * resources (Phase 3B's separator lost its spaces on the device), so the separator must be quoted.
 */
class GuideStringsTest {

    private fun rawValue(name: String): String {
        val xml = File("src/main/res/values/strings_content.xml").readText()
        val m = Regex("<string name=\"$name\">(.*?)</string>").find(xml) ?: error("missing $name")
        return m.groupValues[1]
    }

    /** What aapt keeps: text inside quotes verbatim; otherwise trimmed, with the · escape decoded. */
    private fun compiled(raw: String): String =
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1)
        else raw.trim().replace("\\u00b7", "·")

    @Test fun `details separator keeps its spaces after resource compilation`() {
        assertEquals(" · ", compiled(rawValue("content_epg_details_separator")))
    }

    @Test fun `details line reads with spaced separators`() {
        val sep = compiled(rawValue("content_epg_details_separator"))
        assertEquals(
            "NOW · 12:00 PM – 3:00 PM · 180m",
            listOf("NOW", "12:00 PM – 3:00 PM", "180m").joinToString(sep),
        )
    }
}
