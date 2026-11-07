package tv.own.owntv.features.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4B guards on the UI source, for behaviour that only shows on a device:
 *  - customers get one guide: the old Live TV bottom-strip guide is gone from the app;
 *  - a reminder banner button is ONE focus target, so the first OK acts (a separate .focusable() before
 *    .clickable made two, and the first press was swallowed);
 *  - the player's TV Guide button no longer depends on mpv's non-observable isLiveContent.
 * Unit tests run from the app module, so paths are relative to it.
 */
class Phase4bSourceGuardsTest {

    private val main = File("src/main/java/tv/own/owntv")
    private fun read(path: String) = File(main, path).readText().replace("\r\n", "\n")

    @Test fun `the old bottom-strip guide is not in the app any more`() {
        assertFalse(File(main, "features/shell/components/BottomTvGuideOverlay.kt").exists())
        assertFalse(File(main, "features/shell/components/LiveTvGuideOverlay.kt").exists())
        val users = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().lines().any { l -> !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") && "BottomTvGuideOverlay(" in l } }
            .toList()
        assertEquals(emptyList<File>(), users)
    }

    @Test fun `the player offers no quick-guide strip button`() {
        val shell = read("features/shell/OwnTVShell.kt")
        val hud = shell.substringAfter("PlayerHud(").substringBefore("modifier = Modifier.fillMaxSize(),\n                    )")
        assertFalse("onOpenGuide = " in hud)
        assertTrue("onOpenFullGuide = " in hud)
    }

    @Test fun `the TV Guide button does not read mpv's isLiveContent`() {
        val line = read("features/shell/OwnTVShell.kt").lines().first { "onOpenFullGuide = " in it }
        assertFalse(line, "isLiveStream" in line || "isLiveContent" in line)
    }

    @Test fun `reminder banner buttons have a single focus target`() {
        val shell = read("features/shell/OwnTVShell.kt")
        val button = shell.substringAfter("private fun ReminderWarningButton(").substringBefore("\n}\n")
        assertTrue(".clickable" in button)
        assertFalse(".focusable()" in button)
    }
}
