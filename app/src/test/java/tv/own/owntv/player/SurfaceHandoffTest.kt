package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.SurfaceHandoff.Action

/**
 * Phase 4B: the live picture after a Guide preview <-> full-screen switch. Recovery uses the same decoder
 * and stream: re-attach, then one fresh surface; never a re-tune or a second decoder.
 */
class SurfaceHandoffTest {

    private val a = Any()
    private val b = Any()

    @Test fun `a different surface while playing is a switch to check`() {
        assertTrue(SurfaceHandoff.isSwitch(previous = a, next = b, hasPlayed = true))
    }

    @Test fun `first attach, the same surface, a detach, or nothing playing yet are not`() {
        assertFalse("first attach", SurfaceHandoff.isSwitch(previous = null, next = b, hasPlayed = true))
        assertFalse("same surface", SurfaceHandoff.isSwitch(previous = a, next = a, hasPlayed = true))
        assertFalse("detach", SurfaceHandoff.isSwitch(previous = a, next = null, hasPlayed = true))
        assertFalse("not started", SurfaceHandoff.isSwitch(previous = a, next = b, hasPlayed = false))
    }

    @Test fun `frames moving on after the switch need nothing`() {
        assertEquals(Action.NONE, SurfaceHandoff.afterSwitch(framesAtSwitch = 300, framesNow = 330, playingVideo = true, attempt = 0))
    }

    @Test fun `a frozen picture is re-attached first, then given one fresh surface`() {
        assertEquals(Action.REATTACH, SurfaceHandoff.afterSwitch(300, 300, playingVideo = true, attempt = 0))
        assertEquals(Action.NEW_SURFACE, SurfaceHandoff.afterSwitch(300, 300, playingVideo = true, attempt = 1))
        // After that the regular freeze watchdog and the mpv fallback stay in charge.
        assertEquals(Action.NONE, SurfaceHandoff.afterSwitch(300, 300, playingVideo = true, attempt = 2))
    }

    @Test fun `paused, buffering or audio-only playback is never treated as frozen`() {
        assertEquals(Action.NONE, SurfaceHandoff.afterSwitch(300, 300, playingVideo = false, attempt = 0))
        assertEquals(Action.NONE, SurfaceHandoff.afterSwitch(300, 300, playingVideo = false, attempt = 1))
    }

    @Test fun `at most one fresh surface per freeze`() {
        assertTrue(SurfaceHandoff.mayRecreate(0))
        assertFalse(SurfaceHandoff.mayRecreate(1))
    }

    @Test fun `the check comes quickly, well before the 30 s fallback`() {
        assertTrue(SurfaceHandoff.CHECK_MS in 500L..3_000L)
    }
}
