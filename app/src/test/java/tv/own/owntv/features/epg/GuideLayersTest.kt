package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.epg.GuideNav.Cursor

/**
 * Phase 4 rendering invalidation. Row content sits in a cached layer that only its own data, texts,
 * clock and horizontal scroll redraw; the cursor reaches a row only through [GuideNav.highlightFor],
 * which feeds the overlay drawn outside that layer. So what a move invalidates is exactly the set of
 * rows whose highlight changes.
 */
class GuideLayersTest {

    private val t = 1_800_000_000_000L
    private val rows = 0 until 200

    private fun invalidated(before: Cursor?, after: Cursor?, focused: Boolean = true): List<Int> =
        rows.filter { r -> GuideNav.highlightFor(before, focused, r) != GuideNav.highlightFor(after, focused, r) }

    @Test fun `a vertical move redraws the overlays of the two rows involved and nothing else`() {
        assertEquals(listOf(10, 11), invalidated(Cursor(10, t), Cursor(11, t)))
    }

    @Test fun `a move along a row redraws only that row's overlay`() {
        assertEquals(listOf(10), invalidated(Cursor(10, t), Cursor(10, t + 1_800_000L)))
    }

    @Test fun `a page move redraws two overlays however far it jumps`() {
        assertEquals(listOf(10, 19), invalidated(Cursor(10, t), Cursor(19, t)))
    }

    @Test fun `a held remote redraws two overlays per step`() {
        var c = Cursor(0, t)
        repeat(30) {
            val next = Cursor(c.row + 1, c.timeMs)
            assertEquals(2, invalidated(c, next).size)
            c = next
        }
    }

    @Test fun `focus leaving the grid clears one overlay`() {
        val c = Cursor(10, t)
        val changed = rows.filter { r -> GuideNav.highlightFor(c, true, r) != GuideNav.highlightFor(c, false, r) }
        assertEquals(listOf(10), changed)
    }

    // ---- The A/B switch -------------------------------------------------------------------------------

    @Test fun `layers are on unless a debug build turns them off`() {
        assertTrue(GuideLayers.enabled(debuggable = false, property = "0"))
        assertTrue(GuideLayers.enabled(debuggable = true, property = null))
        assertTrue(GuideLayers.enabled(debuggable = true, property = ""))
        assertTrue(GuideLayers.enabled(debuggable = true, property = "1"))
        assertFalse(GuideLayers.enabled(debuggable = true, property = "0"))
        assertFalse(GuideLayers.enabled(debuggable = true, property = " 0 "))
    }
}
