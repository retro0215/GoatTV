package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Title, bell and badge placement inside a guide cell, relative to the visible timeline. */
class GuideCellLayoutTest {

    private val view = 1200f
    private val pad = 10f
    private val gap = 6f
    private val minText = 28f

    private fun layout(x: Float, w: Float, bell: Float = 0f, badge: Float = 0f) =
        GuideCellLayout.layout(x, w, view, pad, gap, bell, badge, minText)

    @Test fun `a fully visible cell starts its title at its own left edge`() {
        val c = layout(x = 300f, w = 400f)
        assertEquals(310f, c.textX)
        assertEquals(380f, c.textWidth)
    }

    @Test fun `a programme that began off-screen keeps its title at the visible edge`() {
        val c = layout(x = -500f, w = 900f) // on air since before the visible timeline
        assertEquals(pad, c.textX)
        assertEquals(400f - 2 * pad, c.textWidth)
        assertTrue("title must not start outside the viewport", c.textX >= 0f)
    }

    @Test fun `a cell running past the right edge is measured to the edge`() {
        val c = layout(x = 1000f, w = 800f)
        assertEquals(1010f, c.textX)
        assertEquals(view - pad - 1010f, c.textWidth)
    }

    @Test fun `too little visible width draws no title`() {
        assertEquals(0f, layout(x = -880f, w = 900f).textWidth) // 20 px visible
    }

    @Test fun `the reminder bell leads the visible title`() {
        val c = layout(x = -500f, w = 900f, bell = 24f)
        assertEquals(pad, c.bellX)
        assertEquals(pad + 24f + gap, c.textX)
    }

    @Test fun `the catch-up badge stays at the visible right end and the title stops before it`() {
        val c = layout(x = 900f, w = 900f, badge = 16f) // cell runs off the right edge
        assertEquals(view - pad - 16f, c.badgeX)
        assertEquals(c.badgeX!! - gap - c.textX, c.textWidth)
    }

    @Test fun `bell and badge together on an on-air programme`() {
        val c = layout(x = -300f, w = 700f, bell = 24f, badge = 16f)
        assertNotNull(c.bellX)
        assertNotNull(c.badgeX)
        assertTrue(c.bellX!! < c.textX && c.textX + c.textWidth <= c.badgeX!!)
    }

    @Test fun `no bell or badge when the programme has none`() {
        val c = layout(x = 0f, w = 300f)
        assertNull(c.bellX)
        assertNull(c.badgeX)
    }

    @Test fun `a sliver too narrow for the bell drops it rather than overlapping`() {
        val c = layout(x = 0f, w = 30f, bell = 24f)
        assertNull(c.bellX)
    }
}
