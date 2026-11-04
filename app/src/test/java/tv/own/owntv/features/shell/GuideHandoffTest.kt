package tv.own.owntv.features.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.shell.GuideHandoff.Phase
import tv.own.owntv.player.SurfaceOwners
import tv.own.owntv.player.SurfaceOwnership
import tv.own.owntv.player.SurfaceOwnership.Role
import java.io.File

/**
 * 4.6.0: full screen <-> TV Guide preview moves the live ExoPlayer picture ONCE. The onn log showed two
 * moves per transition: the outgoing view went first, ExoPlayer parked the decoder on a stand-in surface,
 * then the incoming view took it. The player controls never took part.
 */
class GuideHandoffTest {

    /** The engine's surface rules (LivePreviewEngine): ownership, and a stand-in when the attached view goes. */
    private class Engine {
        val owners = SurfaceOwners<String>()
        var attached: String? = null
        val moves = mutableListOf<String>()
        fun owner(r: Role?) = owners.setOwner(r) { true }?.let { attach(it) }
        fun created(r: Role, s: String) { if (owners.offer(r, s)) attach(s) }
        fun destroyed(r: Role, s: String) {
            owners.withdraw(r, s)
            if (attached == s) attach("stand-in") // ExoPlayer clears the surface: decoder parked off screen
        }
        private fun attach(s: String) { if (attached != s) { attached = s; moves += s } }
    }

    @Test fun `old order - each transition moved the decoder twice, through a stand-in`() {
        val e = Engine()
        e.owner(Role.PLAYER); e.created(Role.PLAYER, "full-1")
        // Open: the full-screen view went first, the Guide's preview came ~1 s later.
        e.owner(Role.GUIDE); e.destroyed(Role.PLAYER, "full-1"); e.created(Role.GUIDE, "preview")
        // Back: the preview went first, the new full-screen view came ~0.2 s later.
        e.owner(Role.PLAYER); e.destroyed(Role.GUIDE, "preview"); e.created(Role.PLAYER, "full-2")
        assertEquals(listOf("full-1", "stand-in", "preview", "stand-in", "full-2"), e.moves)
    }

    @Test fun `with the handoff - the outgoing view stays until the incoming one is attached, one move each way`() {
        val e = Engine()
        e.owner(Role.PLAYER); e.created(Role.PLAYER, "full-1")
        // TO_GUIDE: the full-screen view stays while the Guide builds; it goes once the preview is attached.
        e.owner(Role.GUIDE); e.created(Role.GUIDE, "preview"); e.destroyed(Role.PLAYER, "full-1")
        // TO_PLAYER: the full-screen view is created over the Guide; the Guide goes once it is attached.
        e.owner(Role.PLAYER); e.created(Role.PLAYER, "full-2"); e.destroyed(Role.GUIDE, "preview")
        assertEquals(listOf("full-1", "preview", "full-2"), e.moves)
    }

    // ---- Rules ---------------------------------------------------------------------------------------

    @Test fun `a handoff starts only on ExoPlayer and never over another one`() {
        assertEquals(Phase.TO_GUIDE, GuideHandoff.start(onExo = true, current = Phase.NONE, to = Phase.TO_GUIDE))
        assertEquals(Phase.NONE, GuideHandoff.start(onExo = false, current = Phase.NONE, to = Phase.TO_GUIDE))
        assertEquals(Phase.TO_PLAYER, GuideHandoff.start(onExo = true, current = Phase.TO_PLAYER, to = Phase.TO_PLAYER))
    }

    @Test fun `each handoff ends on its incoming view, with a bounded wait`() {
        assertEquals(Role.GUIDE, GuideHandoff.target(Phase.TO_GUIDE))
        assertEquals(Role.PLAYER, GuideHandoff.target(Phase.TO_PLAYER))
        assertNull(GuideHandoff.target(Phase.NONE))
        assertTrue(GuideHandoff.timeoutMs(Phase.TO_PLAYER) <= 2_000L)
        assertTrue(GuideHandoff.timeoutMs(Phase.TO_GUIDE) <= 5_000L)
    }

    @Test fun `the video layer stays full screen through a handoff, without controls`() {
        for (phase in listOf(Phase.TO_GUIDE, Phase.TO_PLAYER)) {
            assertTrue(GuideHandoff.videoLayerShown(playerFullscreenOrMini = false, phase = phase))
            assertTrue(GuideHandoff.videoLayerFullScreen(playerFullscreen = false, phase = phase))
            assertFalse(GuideHandoff.controlsShown(playerFullscreen = true, phase = phase))
        }
        assertTrue(GuideHandoff.controlsShown(playerFullscreen = true, phase = Phase.NONE))
        assertFalse(GuideHandoff.videoLayerShown(playerFullscreenOrMini = false, phase = Phase.NONE))
    }

    @Test fun `the full-screen player owns the picture as soon as the return starts`() {
        assertEquals(Role.PLAYER, SurfaceOwnership.ownerFor(GuideHandoff.playerOwnsPicture(false, Phase.TO_PLAYER), guidePreview = true))
        assertEquals(Role.GUIDE, SurfaceOwnership.ownerFor(GuideHandoff.playerOwnsPicture(false, Phase.TO_GUIDE), guidePreview = true))
    }

    @Test fun `keys wait only while returning to full screen`() {
        assertTrue(GuideHandoff.keysHeld(Phase.TO_PLAYER))
        assertFalse(GuideHandoff.keysHeld(Phase.TO_GUIDE))
        assertFalse(GuideHandoff.keysHeld(Phase.NONE))
    }

    // ---- Wiring: the controls never touch the surface -------------------------------------------------

    private val shell = File("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt").readText().replace("\r\n", "\n")

    @Test fun `opening the player controls does not change surface ownership`() {
        // The owner depends on the player mode, the handoff and the Guide only.
        val owner = shell.substringAfter("val surfaceOwner = tv.own.owntv.player.SurfaceOwnership.ownerFor(").substringBefore("\n    )")
        for (word in listOf("hud", "Hud", "controls", "Controls", "showChannelList", "showHistoryList")) {
            assertFalse("owner reads $word", word in owner)
        }
        // The video view is composed before, and independently of, the controls: one slot, one SurfaceView.
        val layer = shell.substringAfter("if (GuideHandoff.videoLayerShown(").substringBefore("if (controlsShown) {")
        assertTrue("role = tv.own.owntv.player.SurfaceOwnership.Role.PLAYER" in layer)
        assertFalse("PlayerHud(" in layer)
        assertTrue("PlayerHud(" in shell.substringAfter("if (controlsShown) {"))
    }

    @Test fun `both transitions go through the handoff, which ends on the attached surface`() {
        assertTrue("videoHandoff = GuideHandoff.start(liveOnExo, videoHandoff, GuideHandoff.Phase.TO_GUIDE)" in shell)
        assertTrue("GuideHandoff.Phase.TO_PLAYER -> videoHandoff = GuideHandoff.Phase.TO_PLAYER" in shell)
        assertTrue("liveVm.previewEngine.attachedRole.first { it == target }" in shell)
        assertTrue(".onPreviewKeyEvent { GuideHandoff.keysHeld(videoHandoff) }" in shell)
    }
}
