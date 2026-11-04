package tv.own.owntv.features.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.shell.components.SidebarLabelPlacement
import tv.own.owntv.features.shell.components.SidebarLabelState
import java.io.File

/** 4.6.0: the compact rail's floating focus label. */
class SidebarFocusLabelTest {

    // The onn at 1080p: the rail's Guide item, ~120 px wide, centered at y = 799.
    private val item = Rect(left = 12f, top = 770f, right = 132f, bottom = 828f)

    @Test fun `the label sits beside the icon, vertically centered on it`() {
        val p = SidebarLabelPlacement.position(item, labelWidth = 150, labelHeight = 40, origin = Offset.Zero,
            containerWidth = 1920, containerHeight = 1080, gap = 12, rtl = false)
        assertEquals(IntOffset(132 + 12, 799 - 20), p)
    }

    @Test fun `right-to-left layouts put it on the other side`() {
        val rtlItem = Rect(left = 1788f, top = 770f, right = 1908f, bottom = 828f)
        val p = SidebarLabelPlacement.position(rtlItem, 150, 40, Offset.Zero, 1920, 1080, gap = 12, rtl = true)
        assertEquals(1788 - 12 - 150, p.x)
    }

    @Test fun `it never leaves the screen at the top or bottom item`() {
        val top = Rect(12f, -10f, 132f, 20f)
        val bottom = Rect(12f, 1060f, 132f, 1100f)
        assertEquals(0, SidebarLabelPlacement.position(top, 150, 40, Offset.Zero, 1920, 1080, 12, false).y)
        assertEquals(1080 - 40, SidebarLabelPlacement.position(bottom, 150, 40, Offset.Zero, 1920, 1080, 12, false).y)
    }

    @Test fun `positions are taken relative to the overlay`() {
        val p = SidebarLabelPlacement.position(item, 150, 40, origin = Offset(0f, 30f), 1920, 1050, 12, false)
        assertEquals(799 - 30 - 20, p.y)
    }

    @Test fun `the label follows focus and goes when focus leaves`() {
        val s = SidebarLabelState()
        s.focus(MainSection.LIVE_TV, item)
        assertEquals(MainSection.LIVE_TV, s.section)
        // Moving down: the next icon gains focus, the previous one then reports it lost it (either order).
        s.focus(MainSection.MOVIES, item.translate(0f, 60f))
        s.clear(MainSection.LIVE_TV)
        assertEquals(MainSection.MOVIES, s.section)
        assertEquals(item.translate(0f, 60f), s.anchor)
        s.move(MainSection.LIVE_TV, item) // another icon's layout pass does not move the label
        assertEquals(item.translate(0f, 60f), s.anchor)
        s.clear() // focus left the rail
        assertNull(s.section)
    }

    // ---- Wiring ------------------------------------------------------------------------------------

    private fun src(path: String) = File("src/main/java/tv/own/owntv/$path").readText().replace("\r\n", "\n")

    @Test fun `the rail stays a fixed icon rail and the label is drawn over the content`() {
        val sidebar = src("features/shell/components/Sidebar.kt")
        assertTrue("val expanded = false" in sidebar)
        assertTrue(".width(Dimens.SidebarWidthCollapsed)" in sidebar)
        assertTrue("if (!it.hasFocus) focusLabel?.clear()" in sidebar)
        val shell = src("features/shell/OwnTVShell.kt")
        assertTrue("focusLabel = sidebarLabel" in shell)
        // In the shell's root overlay, only while the rail has focus, never in full screen.
        val overlay = shell.substringBefore("SidebarFocusLabel(sidebarLabel").substringAfterLast("if (playerMode != PlayerMode.FULLSCREEN && !isMultiscreen) {")
        assertTrue("if (focusedLayer == ShellLayer.SIDEBAR)" in overlay)
    }

    @Test fun `the label takes no focus and no keys`() {
        val label = src("features/shell/components/SidebarFocusLabel.kt")
        assertFalse(".focusable(" in label)
        assertFalse("clickable" in label)
        assertFalse("onKeyEvent" in label)
        assertTrue("stringResource(section.labelRes)" in label) // the existing localized section names
    }
}
