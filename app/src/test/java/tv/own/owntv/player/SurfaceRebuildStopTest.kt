package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.6.1 RC7: Next from one 4K episode to the next froze the UI for ~2.8 s on the onn. The next episode's
 * load waits for a rebuilt surface, the outgoing file stayed loaded, and attaching the new surface set
 * mpv's `vo` from the main thread, which rebuilt the video chain (a new 4K HEVC decoder) for the episode
 * being left. The outgoing file is now stopped on the mpv thread before the rebuild.
 *
 * The rules are pinned here with the player's own pieces (the rebuild decision, [HeavySurfaceTracker],
 * [PendingStopCredits]) and a model of its one-thread command queue; Compose surfaces and mpv itself are
 * checked on the device.
 */
class SurfaceRebuildStopTest {

    // ---- When the outgoing item is stopped first ----

    @Test fun `next 4K episode on the same surface stops the outgoing one before the rebuild`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(1606) // S1E2 playing
        val fresh = OwnTVPlayer.needsFreshMpvSurface(t.heavyOnAttachedSurface(), false, false, true, 23.976f, true)
        assertTrue(fresh)
        assertTrue(OwnTVPlayer.stopsOutgoingBeforeRebuild(fresh, mpvHasFile = true))
    }

    @Test fun `a 4K film on a new surface (RC6) neither rebuilds nor stops anything early`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(2160); t.onDetach(); t.onAttach()
        val fresh = OwnTVPlayer.needsFreshMpvSurface(t.heavyOnAttachedSurface(), false, false, false, null, true)
        assertFalse(fresh)
        assertFalse(OwnTVPlayer.stopsOutgoingBeforeRebuild(fresh, mpvHasFile = false))
    }

    @Test fun `autoplay after a natural end has no file to stop`() {
        // The episode already ended: mpv has no file, so the rebuild has nothing to tear down.
        assertFalse(OwnTVPlayer.stopsOutgoingBeforeRebuild(needsFreshSurface = true, mpvHasFile = false))
    }

    @Test fun `forced and low frame-rate rebuilds also stop the outgoing item first`() {
        assertTrue(OwnTVPlayer.stopsOutgoingBeforeRebuild(OwnTVPlayer.needsFreshMpvSurface(false, true, false, true, null, true), true))
        assertTrue(OwnTVPlayer.stopsOutgoingBeforeRebuild(OwnTVPlayer.needsFreshMpvSurface(false, false, true, true, 25f, true), true))
    }

    @Test fun `no rebuild, no early stop`() {
        assertFalse(OwnTVPlayer.stopsOutgoingBeforeRebuild(needsFreshSurface = false, mpvHasFile = true))
    }

    // ---- The mpv command queue, modelled: one thread, END_FILE credits only for a loaded file ----

    /** The player's command thread as it behaves for these commands (see OwnTVPlayer.startLoad/stop). */
    private class Core {
        val credits = PendingStopCredits(8)
        var hasFile = true // the outgoing episode
        var loadGeneration = 1
        val commands = mutableListOf<String>()

        fun stop() { if (hasFile) credits.credit(); hasFile = false; commands += "stop" }
        fun loadfile(gen: Int, url: String) {
            if (gen != loadGeneration) { commands += "skip $url"; return } // superseded while queued
            if (hasFile) credits.credit()
            hasFile = true
            commands += "loadfile $url"
        }
    }

    @Test fun `stop then load leaves exactly one expected END_FILE, and a real failure still counts`() {
        val core = Core()
        core.stop() // queued ahead of the rebuild
        core.loadfile(core.loadGeneration, "e3") // the parked load, once the new surface attaches
        assertEquals(listOf("stop", "loadfile e3"), core.commands)
        assertTrue("the stop's END_FILE is the app's own", core.credits.consume())
        assertFalse("the new episode failing is NOT swallowed", core.credits.consume())
    }

    @Test fun `Back during the transition stops once and the parked load never runs`() {
        val core = Core()
        core.stop() // rebuild stop
        core.loadGeneration++ // Back: OwnTVPlayer.stop() bumps the generation and clears the pending url
        core.stop() // the user's stop: no file any more, so no second credit
        core.loadfile(1, "e3") // had it been queued, it is skipped as superseded
        assertEquals(listOf("stop", "stop", "skip e3"), core.commands)
        assertTrue(core.credits.consume())
        assertFalse("one END_FILE, one credit — nothing left to swallow a later real one", core.credits.consume())
        assertFalse(core.hasFile) // no stream left open
    }

    // ---- The parked load: one slot, so rapid Next never queues two streams ----

    @Test fun `rapid Next while the surface is being rebuilt loads only the last episode, once`() {
        var pendingUrl: String? = null
        val loads = mutableListOf<String>()
        fun next(url: String) { pendingUrl = url } // route "fresh" or "wait": the load parks
        fun surfaceAttached() { pendingUrl?.let { loads += it }; pendingUrl = null } // attachSurface's flush
        next("e3"); next("e4") // two Next presses before the new surface arrives
        surfaceAttached()
        surfaceAttached() // a later attach (e.g. the Guide) flushes nothing
        assertEquals(listOf("e4"), loads)
        assertNull(pendingUrl)
    }

    @Test fun `the rebuilt surface is new, so the parked episode is not rebuilt a second time`() {
        val t = HeavySurfaceTracker()
        t.onAttach(); t.onDecoded(1606)
        t.onDetach(); t.onAttach() // the rebuild
        assertFalse(t.heavyOnAttachedSurface()) // the next 4K episode after this one will rebuild again, once
    }
}
