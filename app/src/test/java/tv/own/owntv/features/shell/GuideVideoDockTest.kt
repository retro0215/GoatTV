package tv.own.owntv.features.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.shell.GuideVideoDock.Placement
import tv.own.owntv.player.SurfaceOwners
import tv.own.owntv.player.SurfaceOwnership.Role
import tv.own.owntv.player.VideoDockSlot
import java.io.File

/**
 * 4.6.0: the TV Guide's live ExoPlayer preview is the full-screen player's own video view, resized into the
 * preview box. Phase 4D on the onn: every transition moved the decoder to another surface and 3 of 30 moves
 * froze the Amlogic decoder; with one view resized, no transition moves it.
 */
class GuideVideoDockTest {

    /** The engine's side: one decoder; a move is any change of the surface it renders to. */
    private class Engine {
        val owners = SurfaceOwners<String>()
        var attached: String? = null
        var moves = 0
        fun owner(r: Role?) = owners.setOwner(r) { true }?.let { attach(it) }
        fun offer(r: Role, s: String) { if (owners.offer(r, s)) attach(s) }
        private fun attach(s: String) { if (attached != null && attached != s) moves++; attached = s }
    }

    /** The shell's video layer, as a list of frames: whether its one view exists, and where it is drawn. */
    private data class Frame(val fullscreen: Boolean, val docked: Boolean) {
        val shown get() = GuideVideoDock.layerShown(fullscreen, mini = false, docked = docked)
        val owner get() = GuideVideoDock.owner(fullscreen, docked)
    }

    @Test fun `thirty Guide and full-screen transitions never move the decoder`() {
        val e = Engine()
        var view: String? = null
        var created = 0
        fun frame(f: Frame) {
            e.owner(f.owner)
            if (f.shown && view == null) { view = "player-view-${++created}"; e.offer(Role.PLAYER, view!!) }
            if (!f.shown) view = null
        }
        frame(Frame(fullscreen = true, docked = false))
        repeat(15) {
            frame(Frame(fullscreen = false, docked = true)) // TV Guide button: the same view shrinks
            frame(Frame(fullscreen = true, docked = false)) // Back: it grows again
        }
        assertEquals("one view for the whole session", 1, created)
        assertEquals("no surface move", 0, e.moves)
        assertEquals("player-view-1", e.attached)
    }

    @Test fun `the layer stays composed through both transitions, so its view is never rebuilt`() {
        val open = listOf(Frame(true, false), Frame(false, true), Frame(true, false))
        assertTrue(open.all { it.shown })
    }

    @Test fun `docked only for the Guide's ExoPlayer preview`() {
        assertTrue(GuideVideoDock.docked(guideOnScreen = true, previewSurface = GuidePlayback.Surface.EXO))
        assertFalse("mpv keeps its own Guide view", GuideVideoDock.docked(true, GuidePlayback.Surface.MPV))
        assertFalse("nothing playing", GuideVideoDock.docked(true, GuidePlayback.Surface.PLACEHOLDER))
        assertFalse("another section", GuideVideoDock.docked(false, GuidePlayback.Surface.EXO))
        // The Guide's own surface rules: a player on screen (full screen, mini) means no live preview.
        assertFalse(GuideVideoDock.docked(true, GuidePlayback.previewSurface(GuidePlayback.Session(live = true), playerShown = true, liveOnExo = true)))
        assertTrue(GuideVideoDock.docked(true, GuidePlayback.previewSurface(GuidePlayback.Session(live = true), playerShown = false, liveOnExo = true)))
    }

    @Test fun `placement - full screen and mini as before, the preview box once the Guide has laid it out`() {
        assertEquals(Placement.FULL_SCREEN, GuideVideoDock.placement(fullscreen = true, mini = false, docked = false, previewKnown = true))
        assertEquals(Placement.MINI, GuideVideoDock.placement(false, mini = true, docked = false, previewKnown = false))
        assertEquals(Placement.GUIDE_PREVIEW, GuideVideoDock.placement(false, false, docked = true, previewKnown = true))
        assertEquals("the Guide is still building: the picture stays full screen meanwhile",
            Placement.FULL_SCREEN, GuideVideoDock.placement(false, false, docked = true, previewKnown = false))
        assertFalse(GuideVideoDock.layerShown(fullscreen = false, mini = false, docked = false))
    }

    @Test fun `controls only in real full screen, and the player view owns the picture there and in the Guide`() {
        assertTrue(GuideVideoDock.controlsShown(fullscreen = true))
        assertFalse(GuideVideoDock.controlsShown(fullscreen = false))
        assertEquals(Role.PLAYER, GuideVideoDock.owner(fullscreen = true, docked = false))
        assertEquals(Role.PLAYER, GuideVideoDock.owner(fullscreen = false, docked = true))
        assertNull("pages and the mini player: the last surface offered wins, as before", GuideVideoDock.owner(false, false))
    }

    // ---- The preview box ---------------------------------------------------------------------------------

    @Test fun `the slot follows its target and forgets it when it leaves`() {
        val slot = VideoDockSlot()
        val a = Any(); val b = Any()
        slot.report(a, Rect(1254f, 210f, 1860f, 550f))
        assertEquals(Rect(1254f, 210f, 1860f, 550f), slot.bounds)
        slot.report(b, Rect(10f, 10f, 20f, 20f)) // a new Guide composition took over
        slot.clear(a) // the old one leaving afterwards does not clear the new one's box
        assertEquals(Rect(10f, 10f, 20f, 20f), slot.bounds)
        slot.clear(b)
        assertNull(slot.bounds)
    }

    @Test fun `the view lands on the box in whole pixels, relative to the shell`() {
        assertEquals(IntRect(1254, 180, 1860, 520), VideoDockSlot.placement(Rect(1254.4f, 210.2f, 1859.6f, 550.4f), Offset(0f, 30f)))
        assertEquals(IntRect(5, 5, 5, 5), VideoDockSlot.placement(Rect(5f, 5f, 4f, 4f), Offset.Zero))
    }

    // ---- Wiring --------------------------------------------------------------------------------------

    private fun src(path: String) = File("src/main/java/tv/own/owntv/$path").readText().replace("\r\n", "\n")
    private val shell = src("features/shell/OwnTVShell.kt")

    @Test fun `the Guide gets an empty box and the shell's one ExoPlayer view is drawn over it`() {
        assertTrue("GuidePlayback.Surface.EXO -> { m -> tv.own.owntv.player.VideoDockTarget(guideDock, m) }" in shell)
        assertFalse("the Guide has no ExoPlayer view of its own", "Role.GUIDE" in shell)
        assertEquals("one ExoPlayer view in the shell", 1, Regex("ExoPreviewSurface\\(").findAll(shell).count())
        val layer = shell.substringAfter("if (GuideVideoDock.layerShown(").substringBefore("if (controlsShown) {")
        assertTrue("Modifier.dockedAt(dockRect, shellOrigin)" in layer)
        assertTrue("role = tv.own.owntv.player.SurfaceOwnership.Role.PLAYER" in layer)
        assertTrue("SideEffect { liveVm.previewEngine.setSurfaceOwner(surfaceOwner) }" in shell)
        assertTrue("val surfaceOwner = GuideVideoDock.owner(fullscreen = playerMode == PlayerMode.FULLSCREEN, docked = guideDocked)" in shell)
    }

    @Test fun `navigation is unchanged - the TV Guide button opens the Guide, Back returns at once`() {
        val open = shell.substringAfter("val openGuideFromPlayer = {").substringBefore("\n    }\n")
        assertTrue("playerMode = PlayerMode.NONE" in open && "onSelectSection(MainSection.EPG)" in open && "guideSession = session" in open)
        val back = shell.substringAfter("val returnToPlayerFromGuide = {").substringBefore("\n    }\n")
        assertTrue("playerMode = PlayerMode.FULLSCREEN" in back && "onSelectSection(back)" in back && "guideSession = GuidePlayback.NONE" in back)
        assertTrue("GuidePlayback.back(guideSession) == GuidePlayback.Back.RETURN_TO_PLAYER -> returnToPlayerFromGuide()" in shell)
        assertFalse("no handoff, no held keys", "GuideHandoff" in shell || "keysHeld" in shell)
        assertFalse(File("src/main/java/tv/own/owntv/features/shell/GuideHandoff.kt").exists())
    }

    @Test fun `the Guide's in-window dialogs open in their own window, above the docked picture, at full size`() {
        val epg = src("features/epg/EpgScreen.kt")
        for (name in listOf("private fun GuideOptionsDialog(", "private fun EpgMatchChooserDialog(")) {
            val body = epg.substringAfter(name).substringBefore("\n}\n")
            assertTrue(name, "OwnTVPopup(onDismissRequest = onDismiss, scale = 1f, fontScale = 1f)" in body)
        }
        val popup = src("ui/components/OwnTVPopup.kt")
        assertTrue("scale: Float = 0.70f" in popup && "val popupScale = scale" in popup)
    }

    @Test fun `the Guide's preview box draws nothing over the picture`() {
        val target = src("player/VideoDock.kt").substringAfter("fun VideoDockTarget(").substringBefore("\n}\n")
        assertFalse("background" in target || "drawBehind" in target || "Text(" in target)
    }
}
