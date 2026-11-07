package tv.own.owntv.features.epg

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 4.6.0: the larger TV Guide preview (GuidePreviewSize) and its one video view. */
class GuidePreviewSizeTest {

    /** Whole channel rows that fit under the band, the gap and the time axis. */
    private fun rowsVisible(available: Dp): Int {
        val w = GuidePreviewSize.width(available)
        val grid = available - GuidePreviewSize.bandHeight(w) - GuidePreviewSize.GapBelow - GuidePreviewSize.Axis
        val stride = GuideGridDefaults.RowHeight + GuideGridDefaults.RowGap
        return ((grid + GuideGridDefaults.RowGap) / stride + 0.001f).toInt()
    }

    // The onn at 1080p and the app's 90% UI zoom: ~600 dp of screen, ~457 dp for the band and the grid.
    private val onn = 457.dp

    @Test fun `the target is 25 to 30 percent larger than the Phase 4 preview`() {
        val ratio = GuidePreviewSize.Target / GuidePreviewSize.Base
        assertTrue("ratio $ratio", ratio in 1.25f..1.30f)
    }

    @Test fun `on the onn the preview grows by at least 25 percent and keeps three whole rows`() {
        val w = GuidePreviewSize.width(onn)
        assertTrue("width $w", w / GuidePreviewSize.Base >= 1.25f)
        assertTrue("rows ${rowsVisible(onn)}", rowsVisible(onn) >= GuidePreviewSize.MIN_ROWS)
    }

    @Test fun `with more room it stops at the target and more rows show`() {
        assertEquals(GuidePreviewSize.Target, GuidePreviewSize.width(560.dp))
        assertTrue(rowsVisible(560.dp) >= 4)
    }

    @Test fun `with little room it never shrinks below the Phase 4 size`() {
        assertEquals(GuidePreviewSize.Base, GuidePreviewSize.width(396.dp))
        assertEquals(GuidePreviewSize.Base, GuidePreviewSize.width(0.dp))
    }

    @Test fun `whenever the larger preview is used, at least three rows stay visible`() {
        var h = 380
        while (h <= 900) {
            val w = GuidePreviewSize.width(h.dp)
            if (w > GuidePreviewSize.Base) assertTrue("at $h dp", rowsVisible(h.dp) >= GuidePreviewSize.MIN_ROWS)
            h += 7
        }
    }

    @Test fun `the video stays 16 by 9`() {
        listOf(GuidePreviewSize.Base, GuidePreviewSize.Target, 300.dp).forEach { w ->
            assertEquals(w.value * 9f / 16f, GuidePreviewSize.videoHeight(w).value, 0.01f)
            assertEquals(GuidePreviewSize.videoHeight(w) + GuidePreviewSize.Caption, GuidePreviewSize.bandHeight(w))
        }
    }

    @Test fun `the Guide hosts exactly one preview video view`() {
        // Unit tests run from the app module. A second video(...) call would mean a second surface for the
        // same player: another handoff for the decoder that already freezes after them on the onn.
        val screen = File("src/main/java/tv/own/owntv/features/epg/EpgScreen.kt").readText().replace("\r\n", "\n")
        assertEquals(1, Regex("""\bvideo\(Modifier""").findAll(screen).count())
        assertEquals(1, Regex("""GuidePreview\(channel = """).findAll(screen).count())
    }
}
