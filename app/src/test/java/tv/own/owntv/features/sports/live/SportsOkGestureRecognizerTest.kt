package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.features.sports.live.SportsOkGesture.DOUBLE
import tv.own.owntv.features.sports.live.SportsOkGesture.LONG
import tv.own.owntv.features.sports.live.SportsOkGesture.SINGLE

class SportsOkGestureRecognizerTest {

    private val r = SportsOkGestureRecognizer(doubleTapMs = 280, longPressMs = 500)

    /** Runs a key script; ticks every 10 ms like the deadline timer would. Returns emitted gestures. */
    private fun run(vararg script: Pair<String, Long>, until: Long = 2000): List<SportsOkGesture> {
        val out = ArrayList<SportsOkGesture>()
        val events = script.toList()
        var i = 0
        var t = 0L
        while (t <= until) {
            while (i < events.size && events[i].second == t) {
                val g = if (events[i].first == "down") r.onDown(t) else r.onUp(t)
                if (g != null) out += g
                i++
            }
            val deadline = r.nextDeadline()
            if (deadline != null && t >= deadline) r.onTick(t)?.let { out += it }
            t += 10
        }
        return out
    }

    @Test
    fun `single OK - Select Channel only, after the double-tap window`() {
        assertEquals(listOf(SINGLE), run("down" to 0L, "up" to 80L))
        val r2 = SportsOkGestureRecognizer(280, 500)
        r2.onDown(0); r2.onUp(80)
        assertNull("nothing before the window closes", r2.onTick(300))
        assertEquals(SINGLE, r2.onTick(361))
    }

    @Test
    fun `double OK - toggle only, the first tap never opens Select Channel`() {
        assertEquals(listOf(DOUBLE), run("down" to 0L, "up" to 70L, "down" to 200L, "up" to 260L))
    }

    @Test
    fun `slow two taps are two singles, never a double`() {
        assertEquals(listOf(SINGLE, SINGLE), run("down" to 0L, "up" to 70L, "down" to 500L, "up" to 570L))
    }

    @Test
    fun `second press just outside the window flushes the first single at once`() {
        val g = SportsOkGestureRecognizer(280, 500)
        g.onDown(0); g.onUp(50)
        assertEquals(SINGLE, g.onDown(400)) // timer not yet run: the stale tap is reported, not merged
        assertNull(g.onUp(450))
        assertEquals(SINGLE, g.onTick(800))
    }

    @Test
    fun `long press - Multiscreen only, release fires no tap`() {
        assertEquals(listOf(LONG), run("down" to 0L, "up" to 900L))
    }

    @Test
    fun `long press reported on release if the timer was late`() {
        val g = SportsOkGestureRecognizer(280, 500)
        g.onDown(0)
        assertEquals(LONG, g.onUp(650))
        assertNull(g.nextDeadline())
    }

    @Test
    fun `tap then long hold - only the long press`() {
        assertEquals(listOf(LONG), run("down" to 0L, "up" to 60L, "down" to 150L, "up" to 1000L))
    }

    @Test
    fun `system long-press flag on the held repeat - LONG at once, release fires nothing`() {
        val g = SportsOkGestureRecognizer(280, 500)
        assertNull(g.onDown(0))
        assertEquals(LONG, g.onSystemLongPress(20)) // remote / injected repeat flagged FLAG_LONG_PRESS
        assertNull(g.onSystemLongPress(30)) // further flagged repeats: once only
        assertNull(g.onUp(40))
        assertNull(g.onTick(2000))
    }

    @Test
    fun `a stray long-press flag with no key held is ignored`() {
        assertNull(SportsOkGestureRecognizer(280, 500).onSystemLongPress(0))
    }

    @Test
    fun `auto-repeat while held is ignored`() {
        val g = SportsOkGestureRecognizer(280, 500)
        g.onDown(0)
        assertNull(g.onDown(50)) // repeat
        assertNull(g.onDown(100))
        assertEquals(LONG, g.onTick(500))
        assertNull(g.onUp(700))
    }

    @Test
    fun `focus leaving the card drops a pending tap`() {
        val g = SportsOkGestureRecognizer(280, 500)
        g.onDown(0); g.onUp(50)
        g.reset()
        assertNull(g.onTick(1000))
        assertNull(g.nextDeadline())
    }

    @Test
    fun `rapid OK presses - two pairs are two doubles`() {
        assertEquals(
            listOf(DOUBLE, DOUBLE),
            run("down" to 0L, "up" to 40L, "down" to 120L, "up" to 160L, "down" to 600L, "up" to 640L, "down" to 720L, "up" to 760L),
        )
    }

    @Test
    fun `only OK keys are handled`() {
        assertEquals(true, SportsOkGestureRecognizer.isOkKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(true, SportsOkGestureRecognizer.isOkKey(android.view.KeyEvent.KEYCODE_ENTER))
        assertEquals(false, SportsOkGestureRecognizer.isOkKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(false, SportsOkGestureRecognizer.isOkKey(android.view.KeyEvent.KEYCODE_BACK))
        assertEquals(280L, SportsOkGestureRecognizer.DOUBLE_TAP_MS)
    }
}
