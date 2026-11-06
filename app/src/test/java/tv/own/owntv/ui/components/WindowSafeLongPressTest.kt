package tv.own.owntv.ui.components

import android.view.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.shell.ReminderDelivery
import java.io.File

/**
 * 4.6.0: a long-press timer must not fire after the reminder warning takes focus (seen on the onn: holding
 * OK on a Live TV channel row as the warning opened 248 ms in still opened channel options behind it, and
 * Dismiss then left focus in that menu). [KeyLongPressGate] + [waitWhileWindowFocused] replace Compose's
 * combinedClickable key timer, which only a release or a Compose focus change could stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowSafeLongPressTest {

    private val longPressMs = 500L

    private fun src(path: String) = File("src/main/java/tv/own/owntv/$path").readText().replace("\r\n", "\n")

    /** Simulates one press the way windowSafeCombinedClickable runs it; returns what fired. */
    private class Press(val gate: KeyLongPressGate = KeyLongPressGate()) {
        var longs = 0
        var clicks = 0
        fun timerRan(id: Int, windowFocused: Boolean) { if (gate.onTimer(id, windowFocused)) longs++ }
        fun click() { if (gate.allowClick()) clicks++ }
    }

    // ---- Warning before the threshold -------------------------------------------------------------------

    @Test fun `warning before the threshold - the wait ends at the focus loss, not at 500 ms`() = runTest {
        val focused = MutableStateFlow(true)
        val wait = async { waitWhileWindowFocused(longPressMs, focused) }
        advanceTimeBy(248); runCurrent()
        focused.value = false // the warning's window takes focus
        runCurrent()
        assertFalse("long press abandoned", wait.await())
        assertEquals("stopped when focus left, not at the threshold", 248, currentTime)
    }

    @Test fun `warning before the threshold - no long action and no click`() {
        val p = Press()
        val id = p.gate.onDown(0)!!
        p.gate.cancel() // focus lost at 248 ms
        p.timerRan(id, windowFocused = false)
        // The canceled release is dropped at the window root, so no KeyUp and no click arrive.
        assertEquals(0, p.longs)
        assertEquals(0, p.clicks)
    }

    @Test fun `a late timer callback re-checks the window, even if nothing canceled it`() {
        val p = Press()
        val id = p.gate.onDown(0)!!
        p.timerRan(id, windowFocused = false)
        assertEquals(0, p.longs)
    }

    @Test fun `window focus back before the old timer ends does not revive it`() {
        // Warning shown and dismissed within the remaining time: the canceled press stays canceled.
        val p = Press()
        val id = p.gate.onDown(0)!!
        p.gate.cancel()
        p.timerRan(id, windowFocused = true)
        assertEquals(0, p.longs)
    }

    @Test fun `already unfocused when the press starts - the wait returns at once`() = runTest {
        assertFalse(waitWhileWindowFocused(longPressMs, MutableStateFlow(false)))
        assertEquals(0, currentTime)
    }

    // ---- Warning after the threshold --------------------------------------------------------------------

    @Test fun `warning after the threshold - the long press already ran once, before the warning`() = runTest {
        val focused = MutableStateFlow(true)
        val wait = async { waitWhileWindowFocused(longPressMs, focused) }
        advanceTimeBy(longPressMs + 1)
        assertTrue(wait.await())
        val p = Press()
        val id = p.gate.onDown(0)!!
        p.timerRan(id, windowFocused = true)
        p.gate.cancel() // warning at 900 ms: nothing left to stop
        p.timerRan(id, windowFocused = true)
        assertEquals("exactly one long action", 1, p.longs)
        assertEquals(0, p.clicks)
    }

    // ---- After Dismiss ---------------------------------------------------------------------------------

    @Test fun `after Dismiss the next OK on the same row is a normal click`() {
        // Compose's timer left its job behind after an interrupted press and ate the next click; this gate
        // starts every press fresh.
        val p = Press()
        val first = p.gate.onDown(0)!!
        p.gate.cancel() // warning; the canceled release is dropped
        p.timerRan(first, windowFocused = false)
        val second = p.gate.onDown(0)!!
        p.gate.onUp()
        p.click()
        p.timerRan(second, windowFocused = true) // a stale callback must not fire either
        assertEquals(1, p.clicks)
        assertEquals(0, p.longs)
    }

    @Test fun `after Dismiss a long press that did fire does not swallow the next click`() {
        val p = Press()
        p.timerRan(p.gate.onDown(0)!!, windowFocused = true)
        p.gate.onDown(0)
        p.gate.onUp()
        p.click()
        assertEquals(1, p.clicks)
    }

    @Test fun `Dismiss leaves focus where it was when the app window still holds it`() {
        // The interrupted row keeps Compose focus (no menu opened over it), so Dismiss does not move focus:
        // the user is back on the channel they were holding.
        assertFalse(ReminderDelivery.restoreFocusOnDismiss(playerShown = false, shellHasFocus = true))
    }

    // ---- Normal use without a warning ------------------------------------------------------------------

    @Test fun `normal long press - fires once and the release is not also a click`() = runTest {
        val p = Press()
        val id = p.gate.onDown(0)!!
        assertNull("repeats don't restart the timer", p.gate.onDown(1))
        assertTrue(waitWhileWindowFocused(longPressMs, MutableStateFlow(true)))
        assertEquals(longPressMs, currentTime)
        p.timerRan(id, windowFocused = true)
        p.gate.onUp()
        p.click()
        assertEquals(1, p.longs)
        assertEquals(0, p.clicks)
    }

    @Test fun `short press - click only`() {
        val p = Press()
        val id = p.gate.onDown(0)!!
        p.gate.onUp()
        p.click()
        p.timerRan(id, windowFocused = true) // canceled timer racing the release
        assertEquals(1, p.clicks)
        assertEquals(0, p.longs)
    }

    @Test fun `OK keys match the keys Compose clicks on`() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)) {
            assertTrue("key $k", KeyLongPressGate.isOkKey(k))
        }
        for (k in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU)) {
            assertFalse("key $k", KeyLongPressGate.isOkKey(k))
        }
    }

    // ---- Where it applies -------------------------------------------------------------------------------

    @Test fun `no control uses Compose's combinedClickable key timer any more`() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""\.combinedClickable\(|import androidx\.compose\.foundation\.combinedClickable""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `Live TV rows, buttons, posters and Multiscreen tiles use the window-safe long press`() {
        val surface = src("ui/components/FocusableSurface.kt")
        assertTrue("Modifier.windowSafeCombinedClickable(" in surface)
        assertTrue("onLongClick = onLongClick," in surface.substringAfter("Modifier.windowSafeCombinedClickable("))
        // Live TV channel options come only from the row's long click.
        val live = src("features/live/LiveScreen.kt")
        assertTrue("onLongClick = { contextChannel = channel; contextChannelId = channel.id }" in live)
        assertTrue("FocusableSurface(\n        onClick = onClick,\n        onLongClick = onLongClick," in live)
        val tile = src("features/multiscreen/MultiscreenScreen.kt")
        assertTrue(".windowSafeCombinedClickable(" in tile)
    }

    @Test fun `the modifier waits on window focus and re-checks it before acting`() {
        val m = src("ui/components/WindowSafeLongPress.kt")
        assertTrue("waitWhileWindowFocused(longPressMs, snapshotFlow { windowInfo.isWindowFocused })" in m)
        assertTrue("gate.onTimer(id, windowInfo.isWindowFocused)" in m)
        assertTrue(".onFocusChanged { if (!it.hasFocus) stop() }" in m)
    }

    @Test fun `Sports cards stop their OK timer when the window loses focus`() {
        val s = src("features/sports/live/SportsOkGestures.kt")
        assertTrue("if (!waitWhileWindowFocused(wait, snapshotFlow { windowInfo.isWindowFocused })) {" in s)
        assertTrue("recognizer.reset()\n                return@launch" in s)
    }

    @Test fun `the Guide has no timer - its long press needs a key repeat, which the warning takes`() {
        val screen = src("features/epg/EpgScreen.kt")
        assertTrue("} else if (okDown && !okLongPressed) {" in screen)
        assertNotNull(Regex("""LaunchedEffect\(gridWindowFocused\)""").find(screen))
    }
}
