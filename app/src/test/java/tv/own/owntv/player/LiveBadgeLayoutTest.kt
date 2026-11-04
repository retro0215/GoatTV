package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.6.0: the player's top bar on mpv. Its chip list is longer than ExoPlayer's (the measured bitrate, the
 * audio layout), and measured first it took the whole width: the LIVE badge after it got a few pixels and
 * "LIVE" wrapped letter by letter into a thin red bar (onn, Big Ten Network on mpv). The badge is now
 * measured first; the chips share what is left, one line each.
 */
class LiveBadgeLayoutTest {

    private val chrome = File("src/main/java/tv/own/owntv/player/PlayerHudChrome.kt").readText().replace("\r\n", "\n")
    private val chipRow = chrome.substringAfter("val chipRow: @Composable () -> Unit = {").substringBefore("// Live stacks the technical chips")

    @Test fun `the chips are a weighted group, so the badge after them is measured first at its own size`() {
        val group = chipRow.indexOf("Modifier.weight(1f, fill = false)")
        val badge = chipRow.indexOf("LiveBadge()")
        assertTrue("the chips sit in a weighted row", group > 0)
        assertTrue("the badge is outside it, after it", badge > chipRow.indexOf("parts.forEachIndexed") && badge > group)
        assertFalse("the badge itself is not weighted", "LiveBadge(Modifier.weight" in chipRow)
    }

    @Test fun `chips stay on one line and end in an ellipsis when short of room`() {
        val chip = chipRow.substringAfter("parts.forEachIndexed").substringBefore("if (isLive)")
        assertTrue("maxLines = 1" in chip && "softWrap = false" in chip && "overflow = TextOverflow.Ellipsis" in chip)
    }

    @Test fun `LIVE never wraps`() {
        val badge = chrome.substringAfter("private fun LiveBadge()").substringBefore("\n}\n")
        assertTrue("stringResource(R.string.player_live)" in badge)
        assertTrue("maxLines = 1" in badge && "softWrap = false" in badge)
    }

    @Test fun `both engines use the same top bar`() {
        // The fix applies to ExoPlayer and mpv alike: one TopBar, fed each engine's chips.
        assertTrue("internal fun TopBar(" in chrome)
        assertTrue(Regex("fun LiveBadge\\(").findAll(chrome).count() == 1)
    }
}
