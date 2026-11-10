package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which surface a >1080p video decoded on, and the load route that follows (4.6.1 RC6).
 *
 * RC5 on the onn: after a 4K film was left (its surface destroyed), the next 4K film's brand-new
 * full-screen surface was rebuilt ~40 ms after it attached; the rebuilt view never reported a surface and
 * the load stayed parked until the 10 s T_OPEN watchdog. A rebuild is now asked for only on the surface
 * the 4K video actually used.
 */
class HeavySurfaceTrackerTest {

    private enum class Route { FRESH, DIRECT, WAIT }

    /** The same three-way choice OwnTVPlayer.loadUrl makes, for a film. */
    private fun route(t: HeavySurfaceTracker, attached: Boolean, forceReset: Boolean = false): Route {
        val fresh = OwnTVPlayer.needsFreshMpvSurface(
            heavyOnThisSurface = t.heavyOnAttachedSurface(),
            forceReset = forceReset,
            isLive = false,
            mpvHadFile = false,
            previousFps = null,
            surfaceAttached = attached,
        )
        return when {
            fresh -> Route.FRESH
            attached -> Route.DIRECT
            else -> Route.WAIT
        }
    }

    @Test fun `the first 4K film of a session loads straight onto its surface`() {
        val t = HeavySurfaceTracker()
        t.onAttach()
        assertEquals(Route.DIRECT, route(t, attached = true))
        t.onDecoded(2160)
        assertTrue(t.heavyOnAttachedSurface())
    }

    @Test fun `a second 4K film on a newly created surface is not rebuilt (the RC5 sequence)`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(2160) // Dune 4K plays
        t.onDetach() // Back: the full-screen view and its surface are destroyed
        t.onAttach() // Severance 4K: the full-screen view appears with a new surface
        assertFalse(t.heavyOnAttachedSurface())
        assertEquals(Route.DIRECT, route(t, attached = true)) // loads at once, nothing parked
    }

    @Test fun `a second 4K film on the same reused surface is still rebuilt`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(2160)
        assertEquals(Route.FRESH, route(t, attached = true)) // e.g. auto-play to the next 4K episode
        // The rebuild destroys this surface and attaches a new one, where the parked load then runs.
        t.onDetach(); t.onAttach()
        assertEquals(Route.DIRECT, route(t, attached = true)) // once: no second rebuild, no loop
    }

    @Test fun `with no surface the load waits, and the next attach is a clean one`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(2160); t.onDetach()
        assertEquals(Route.WAIT, route(t, attached = false))
        t.onAttach()
        assertEquals(Route.DIRECT, route(t, attached = true))
    }

    @Test fun `a forced reset still rebuilds any surface`() {
        val t = HeavySurfaceTracker()
        t.onAttach() // a new surface, no 4K history
        assertEquals(Route.FRESH, route(t, attached = true, forceReset = true))
    }

    @Test fun `the low frame-rate live rule is untouched`() {
        assertTrue(
            OwnTVPlayer.needsFreshMpvSurface(
                heavyOnThisSurface = false, forceReset = false, isLive = true,
                mpvHadFile = true, previousFps = 25f, surfaceAttached = true,
            ),
        )
    }

    @Test fun `a player reset forgets the old surface, and its late destroy changes nothing`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(2160)
        t.onDetach() // hardReset: the core is destroyed and the view recreated
        t.onAttach() // the recreated view's new surface
        t.onDetach(); t.onAttach() // a late destroy/create pair for the same view: still new each time
        assertFalse(t.heavyOnAttachedSurface())
        assertEquals(Route.DIRECT, route(t, attached = true))
    }

    @Test fun `a 1080p video on the same surface clears its 4K history, a 4K one elsewhere does not mark it`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(2160); t.onDecoded(1080) // 4K then 1080p on one surface
        assertFalse(t.heavyOnAttachedSurface())
        t.onDecoded(1606)
        assertTrue(t.heavyOnAttachedSurface())
    }

    @Test fun `heights with no attached surface, or no height at all, are ignored`() {
        val t = HeavySurfaceTracker()
        t.onDecoded(2160) // nothing attached (audio-only, or before the view exists)
        t.onAttach()
        assertFalse(t.heavyOnAttachedSurface())
        t.onDecoded(0)
        assertFalse(t.heavyOnAttachedSurface())
    }
}
