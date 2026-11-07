package tv.own.owntv.ui.components

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.6.0: when the reminder warning's window takes focus while OK is held, Android sends the window below an
 * ACTION_UP with FLAG_CANCELED ("focus left window", InputDispatcher). Compose treated it as a release and
 * the held dialog button fired (seen on the onn: Close ran mid-hold). Canceled OK and Back releases are now
 * dropped at the top of every window that can sit under the warning.
 */
class CanceledKeyReleaseTest {

    private val ok = KeyEvent.KEYCODE_DPAD_CENTER
    private fun ignore(action: Int, flags: Int, key: Int = ok) = CanceledKeyRelease.ignore(action, flags, key)

    private fun src(path: String) = File("src/main/java/tv/own/owntv/$path").readText().replace("\r\n", "\n")
    private val shell by lazy { src("features/shell/OwnTVShell.kt") }
    private val popup by lazy { src("ui/components/OwnTVPopup.kt") }
    private val screen by lazy { src("features/epg/EpgScreen.kt") }

    // ---- The rule ---------------------------------------------------------------------------------------

    @Test fun `held OK interrupted by a window taking focus - the canceled release is dropped`() {
        // The press itself reached the window below as usual...
        assertFalse(ignore(KeyEvent.ACTION_DOWN, 0))
        assertFalse("repeats while held", ignore(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS))
        // ...then focus moved to the warning, and Android canceled it there: it must not press anything.
        assertTrue(ignore(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED))
        // Synthesized cancels keep the original press's flags.
        assertTrue(ignore(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED or KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_TRACKING))
    }

    @Test fun `every key Compose clicks on, and Back, is covered`() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            assertTrue("key $key", ignore(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED, key))
        }
    }

    @Test fun `other keys keep their canceled release - it clears held digit and paging state`() {
        for (key in listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MENU)) {
            assertFalse("key $key", ignore(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED, key))
        }
    }

    @Test fun `a normal OK release still activates`() {
        assertFalse(ignore(KeyEvent.ACTION_UP, 0))
        assertFalse(ignore(KeyEvent.ACTION_UP, KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_TRACKING))
        assertFalse(ignore(KeyEvent.ACTION_UP, 0, KeyEvent.KEYCODE_ENTER))
        assertFalse("Back release", ignore(KeyEvent.ACTION_UP, 0, KeyEvent.KEYCODE_BACK))
    }

    @Test fun `the release after a long press is not mistaken for a canceled one`() {
        // Android marks it FLAG_CANCELED_LONG_PRESS (0x100), a different bit from FLAG_CANCELED (0x20).
        assertEquals(0x20, KeyEvent.FLAG_CANCELED)
        assertFalse(ignore(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED_LONG_PRESS))
        assertFalse(ignore(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED_LONG_PRESS or KeyEvent.FLAG_TRACKING))
    }

    // ---- Where it applies: every window that can sit under the warning --------------------------------

    @Test fun `every popup drops canceled releases before its buttons see them`() {
        // The programme dialog and every other popup are OwnTVPopup windows; a preview handler at the top of
        // the window runs before the focused button's own key handling.
        val root = popup.substringAfter("Box(Modifier.fillMaxSize()").substringBefore("{")
        assertTrue(".ignoreCanceledKeyReleases()" in root)
        assertTrue("fun Modifier.ignoreCanceledKeyReleases(): Modifier = onPreviewKeyEvent" in src("ui/components/CanceledKeyRelease.kt"))
    }

    @Test fun `the app window drops them too - Guide, Home, Live TV and Multiscreen`() {
        // The shell's root box holds every section and Multiscreen.
        val rootBox = shell.substringAfter("CompositionLocalProvider(LocalContentScrolled provides contentScrolled) {")
            .substringBefore("if (isMultiscreen) {")
        assertTrue(".ignoreCanceledKeyReleases()" in rootBox)
        assertTrue(shell.indexOf("MultiscreenScreen(") > shell.indexOf(rootBox))
    }

    @Test fun `the warning itself drops them and still swallows an OK held from below`() {
        val banner = shell.substringAfter("private fun ReminderBannerContent(").substringBefore("private fun ReminderWarningButton(")
        assertTrue(".ignoreCanceledKeyReleases()" in banner)
        assertTrue(".longPressMenuGuard()" in banner)
        assertTrue(banner.indexOf(".ignoreCanceledKeyReleases()") < banner.indexOf(".onPreviewKeyEvent"))
    }

    @Test fun `the Guide grid ends a press when its window loses focus`() {
        // Its canceled release never arrives, so OK and Back state is cleared on the focus change instead.
        assertTrue("LocalWindowInfo.current.isWindowFocused" in screen)
        assertTrue("LaunchedEffect(gridWindowFocused) { if (!gridWindowFocused) { okDown = false; okLongPressed = false; backDown = false } }" in screen)
    }

    @Test fun `long press still opens channel options`() {
        // The hold is driven by key repeats (DOWN events), which pass through untouched.
        assertTrue("okLongPressed = true" in screen)
        assertTrue("matchChooser = channel // hold OK: favourite, EPG match, time offset" in screen)
        assertFalse(ignore(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS))
    }
}
