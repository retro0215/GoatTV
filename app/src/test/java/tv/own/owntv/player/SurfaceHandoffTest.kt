package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.SurfaceHandoff.Action
import tv.own.owntv.player.SurfaceOwnership.Role
import java.io.File

/**
 * Phase 4C: the live picture across TV Guide preview <-> full-screen switches. One player and decoder;
 * one view owns the picture; a picture that stops after a switch gets a new decoder on the same stream.
 */
class SurfaceHandoffTest {

    // ---- Watching frames after a switch -----------------------------------------------------------------

    @Test fun `frames moving keep the watch going, then it ends quietly`() {
        assertEquals(Action.WATCH, SurfaceHandoff.afterCheck(300, 372, playingVideo = true, check = 0))
        assertEquals(Action.WATCH, SurfaceHandoff.afterCheck(372, 444, playingVideo = true, check = SurfaceHandoff.WATCH_CHECKS - 2))
        assertEquals(Action.NONE, SurfaceHandoff.afterCheck(444, 516, playingVideo = true, check = SurfaceHandoff.WATCH_CHECKS - 1))
    }

    @Test fun `a picture that stops a moment after the switch gets a new decoder`() {
        // The onn: 4504 -> 4508 frames just after the switch, then nothing. The check that sees no new frame
        // acts, whichever check it is (Phase 4B only compared with the count at the switch, and missed it).
        assertEquals(Action.REBUILD_DECODER, SurfaceHandoff.afterCheck(4508, 4508, playingVideo = true, check = 0))
        assertEquals(Action.REBUILD_DECODER, SurfaceHandoff.afterCheck(4508, 4508, playingVideo = true, check = 2))
    }

    @Test fun `paused, buffering or audio-only playback is never treated as frozen`() {
        assertEquals(Action.WATCH, SurfaceHandoff.afterCheck(300, 300, playingVideo = false, check = 0))
        assertEquals(Action.NONE, SurfaceHandoff.afterCheck(300, 300, playingVideo = false, check = SurfaceHandoff.WATCH_CHECKS - 1))
    }

    @Test fun `the watch covers the time the onn took to freeze, well before the 30 s fallback`() {
        val watchMs = SurfaceHandoff.CHECK_MS * SurfaceHandoff.WATCH_CHECKS
        assertTrue(watchMs in 3_000L..8_000L)
        assertTrue(SurfaceHandoff.CHECK_MS <= 1_500L)
    }

    // ---- Decoder recovery ------------------------------------------------------------------------------

    @Test fun `one decoder rebuild per minute, then the regular ladder and mpv`() {
        assertTrue("first freeze", SurfaceHandoff.mayRebuild(lastRebuildMs = 0L, nowMs = 5_000L))
        assertFalse("again soon after", SurfaceHandoff.mayRebuild(lastRebuildMs = 5_000L, nowMs = 30_000L))
        assertTrue("a minute later", SurfaceHandoff.mayRebuild(lastRebuildMs = 5_000L, nowMs = 65_000L))
    }

    @Test fun `a switch is a real, different surface while something already played`() {
        val a = Any(); val b = Any()
        assertTrue(SurfaceHandoff.isSwitch(previous = a, next = b, hasPlayed = true))
        assertFalse(SurfaceHandoff.isSwitch(previous = null, next = b, hasPlayed = true))
        assertFalse(SurfaceHandoff.isSwitch(previous = a, next = a, hasPlayed = true))
        assertFalse(SurfaceHandoff.isSwitch(previous = a, next = null, hasPlayed = true))
        assertFalse(SurfaceHandoff.isSwitch(previous = a, next = b, hasPlayed = false))
    }

    // ---- Surface ownership -----------------------------------------------------------------------------

    @Test fun `a frozen rebuilt decoder is still seen - the count survives the new player`() {
        // 4.6.0, the onn: after a rebuild the per-player count restarted at 0, below the count at the switch,
        // and the watch logged "-8176 frames since the switch" and passed. The total never goes back.
        val atSwitch = 8_391L
        val afterRebuild = atSwitch + 3 // three frames from the new decoder, then nothing
        assertEquals(Action.WATCH, SurfaceHandoff.afterCheck(atSwitch, afterRebuild, playingVideo = true, check = 0))
        assertEquals(Action.REBUILD_DECODER, SurfaceHandoff.afterCheck(afterRebuild, afterRebuild, playingVideo = true, check = 1))
    }

    /** Replays the attaches logged on the onn for one Guide <-> full-screen round trip. */
    @Test fun `a round trip attaches each owner's surface once and never the old preview again`() {
        val owners = SurfaceOwners<String>()
        val attached = mutableListOf<String>()
        fun owner(r: Role?) = owners.setOwner(r) { true }?.let { attached += it }
        fun offer(r: Role, s: String) { if (owners.offer(r, s)) attached += s }

        owner(Role.PLAYER); offer(Role.PLAYER, "full-1")
        // TV Guide button: the Guide's preview owns the picture; the player's view goes.
        owner(Role.GUIDE); owners.withdraw(Role.PLAYER, "full-1"); offer(Role.GUIDE, "preview")
        // Back: the player again. Its new view arrives, then (as logged ~0.2 s later) the preview's surface
        // is offered once more while the Guide is being torn down.
        owner(Role.PLAYER); offer(Role.PLAYER, "full-2"); offer(Role.GUIDE, "preview")
        owners.withdraw(Role.GUIDE, "preview")

        assertEquals(listOf("full-1", "preview", "full-2"), attached)
    }

    @Test fun `an owner whose view already exists gets it attached at once, a gone one does not`() {
        val owners = SurfaceOwners<String>()
        owners.setOwner(Role.PLAYER) { true }
        assertFalse("not the owner: kept off", owners.offer(Role.GUIDE, "preview"))
        assertEquals("preview", owners.setOwner(Role.GUIDE) { true })
        assertNull("same owner again", owners.setOwner(Role.GUIDE) { true })
        owners.withdraw(Role.GUIDE, "preview")
        owners.setOwner(Role.PLAYER) { true }
        assertNull("withdrawn", owners.setOwner(Role.GUIDE) { true })
        owners.offer(Role.PLAYER, "full")
        owners.setOwner(Role.GUIDE) { true }
        assertNull("no longer valid", owners.setOwner(Role.PLAYER) { false })
    }

    @Test fun `with no owner the last surface offered wins, as before`() {
        val owners = SurfaceOwners<String>()
        assertTrue(owners.offer(Role.PAGE, "pane"))
        assertTrue(owners.offer(Role.PLAYER, "mini"))
    }

    // ---- Wiring --------------------------------------------------------------------------------------

    private fun src(path: String) = File("src/main/java/tv/own/owntv/$path").readText().replace("\r\n", "\n")

    @Test fun `the shell names the owner of its one player view`() {
        val shell = src("features/shell/OwnTVShell.kt")
        assertTrue("SideEffect { liveVm.previewEngine.setSurfaceOwner(surfaceOwner) }" in shell)
        assertTrue("role = tv.own.owntv.player.SurfaceOwnership.Role.PLAYER" in shell)
    }

    @Test fun `the switch watch counts frames across players, never with the per-player count`() {
        val engine = src("player/LivePreviewEngine.kt")
        assertTrue("frameCounter.incrementAndGet(); renderedTotal.incrementAndGet()" in engine)
        assertFalse("renderedTotal.set(" in engine)
        val check = engine.substringAfter("private val handoffCheck = Runnable {").substringBefore("\n    }\n")
        assertTrue("val framesNow = renderedTotal.get()" in check)
        val arm = engine.substringAfter("private fun armHandoffCheck(").substringBefore("\n    }\n")
        assertTrue("handoffFrames = renderedTotal.get()" in arm)
    }

    @Test fun `views offer and withdraw their surfaces through ownership`() {
        val view = src("player/MpvVideoSurface.kt").substringAfter("fun ExoPreviewSurface(").substringBefore("\n}\n")
        assertTrue("engine.offerSurface(role, holder.surface)" in view)
        assertTrue("engine.withdrawSurface(role, holder.surface)" in view)
        assertFalse("engine.setSurface(holder.surface)" in view)
    }

    @Test fun `a frozen picture tries a new decoder before the reconnect ladder`() {
        val engine = src("player/LivePreviewEngine.kt")
        val frozen = engine.substringAfter("progressWatchdog: picture frozen").substringBefore("} else {")
        assertTrue(frozen.indexOf("rebuildDecoderForFreeze(") in 1 until frozen.indexOf("reconnect(\"picture frozen\")"))
        // The rebuild reuses the before-first-frame rebuild: the player and its decoder go, then the same URL.
        assertTrue("rebuildDecoder(url)" in engine.substringAfter("private fun rebuildDecoderForFreeze("))
    }
}
