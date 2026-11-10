package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an mpv load waits for a recreated SurfaceView. New in 4.6.1: after a live channel of 30 fps or
 * less that mpv was still playing, the next live load gets one — on the same surface a 50 fps channel
 * presented at about 37 fps on Realtek panels (upstream OwnTV_Core 1de78b1e).
 */
class MpvFreshSurfaceTest {

    private fun fresh(
        heavyOnThisSurface: Boolean = false,
        forceReset: Boolean = false,
        isLive: Boolean = true,
        mpvHadFile: Boolean = true,
        previousFps: Float? = 50f,
        surfaceAttached: Boolean = true,
    ) = OwnTVPlayer.needsFreshMpvSurface(heavyOnThisSurface, forceReset, isLive, mpvHadFile, previousFps, surfaceAttached)

    @Test
    fun `a live zap from a 25 or 30 fps channel gets a fresh surface`() {
        assertTrue(fresh(previousFps = 25f))
        assertTrue(fresh(previousFps = 29.97f))
        assertTrue(fresh(previousFps = 30f))
    }

    @Test
    fun `a live zap from a 50 or 60 fps channel keeps the surface`() {
        assertFalse(fresh(previousFps = 50f))
        assertFalse(fresh(previousFps = 59.94f))
    }

    @Test
    fun `films, a stopped player and an unknown rate keep the surface`() {
        assertFalse("films are unaffected", fresh(isLive = false, previousFps = 25f))
        assertFalse("mpv was not playing the last item", fresh(mpvHadFile = false, previousFps = 25f))
        assertFalse("no frame rate known", fresh(previousFps = null))
        assertFalse("no frame rate known", fresh(previousFps = 0f))
    }

    @Test
    fun `the existing reasons still apply`() {
        assertTrue("after a 4K item on this surface", fresh(heavyOnThisSurface = true))
        assertTrue("after an engine handover", fresh(forceReset = true))
        assertTrue("4K film to film on the same surface", fresh(heavyOnThisSurface = true, isLive = false, previousFps = null))
    }

    @Test
    fun `never without an attached surface`() {
        assertFalse(fresh(surfaceAttached = false, previousFps = 25f))
        assertFalse(fresh(surfaceAttached = false, heavyOnThisSurface = true, forceReset = true))
    }

    @Test
    fun `a 4K film after a 4K film on a surface created since keeps the new surface`() {
        // 4.6.1 RC5 on the onn: rebuilding that brand-new surface parked the load until the watchdog.
        assertFalse(fresh(heavyOnThisSurface = false, isLive = false, previousFps = null))
    }
}
