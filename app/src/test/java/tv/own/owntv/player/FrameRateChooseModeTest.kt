package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.player.FrameRateController.MATCH_ALWAYS
import tv.own.owntv.player.FrameRateController.MATCH_NEVER
import tv.own.owntv.player.FrameRateController.MATCH_SEAMLESS_ONLY
import tv.own.owntv.player.FrameRateController.ModeSpec

/**
 * Auto frame rate's mode choice and the request that follows it (upstream OwnTV 8f47da68, 26a1a7e9):
 * a video that matches no mode releases the mode the last one switched to, and Android TV's own
 * "Match content frame rate" setting is respected.
 */
class FrameRateChooseModeTest {

    private val uhd60 = ModeSpec(1, 3840, 2160, 60f)
    private val uhd50 = ModeSpec(2, 3840, 2160, 50f)
    private val uhd24 = ModeSpec(3, 3840, 2160, 24f)
    private val uhd30 = ModeSpec(4, 3840, 2160, 30f)
    private val fhd50 = ModeSpec(5, 1920, 1080, 50f)
    private val uhd5994 = ModeSpec(6, 3840, 2160, 59.94f)

    private fun choose(
        fps: Float,
        modes: List<ModeSpec> = listOf(uhd60, uhd50, uhd24, uhd30, fhd50),
        current: ModeSpec = uhd60,
        seamless: List<Float> = emptyList(),
        preference: Int = MATCH_ALWAYS,
    ) = FrameRateController.chooseMode(modes, current, seamless, preference, fps)

    @Test
    fun `the usual matches are unchanged`() {
        assertEquals(uhd24.id, choose(23.976f))
        assertEquals(uhd50.id, choose(25f))
        assertEquals(uhd50.id, choose(50f))
        assertEquals(uhd30.id, choose(30f)) // the lowest multiple wins: 30 Hz over 60 Hz
        assertEquals(uhd60.id, choose(60f))
    }

    @Test
    fun `the resolution is never changed to reach a rate`() {
        // Only the 1080p panel mode has 50 Hz: a 4K output stays 4K and finds no match.
        assertNull(choose(25f, modes = listOf(uhd60, fhd50)))
    }

    @Test
    fun `a drifting measured rate snaps before choosing`() {
        assertEquals(uhd50.id, choose(24.6f))
        assertEquals(uhd50.id, choose(25.1f))
    }

    @Test
    fun `a seamless mode is preferred when several match`() {
        val modes = listOf(uhd60, uhd30)
        assertEquals(uhd30.id, choose(30f, modes = modes))
        assertEquals(uhd60.id, choose(30f, modes = modes, seamless = listOf(60f)))
    }

    @Test
    fun `the TV set to never match content switches nothing`() {
        assertNull(choose(25f, preference = MATCH_NEVER))
        assertNull(choose(24f, preference = MATCH_NEVER))
    }

    @Test
    fun `the TV set to seamless only takes seamless modes or stays put`() {
        // 50 Hz needs an HDMI re-sync here: not allowed.
        assertNull(choose(25f, seamless = emptyList(), preference = MATCH_SEAMLESS_ONLY))
        // Listed as a seamless alternative: allowed.
        assertEquals(uhd50.id, choose(25f, seamless = listOf(50f), preference = MATCH_SEAMLESS_ONLY))
        // Staying on the current mode is seamless even though Android lists only the OTHER rates.
        assertEquals(uhd30.id, choose(30f, current = uhd30, seamless = listOf(60f), preference = MATCH_SEAMLESS_ONLY))
    }

    @Test
    fun `fractional and whole rates both land on the panel's rate`() {
        assertEquals(uhd5994.id, choose(59.94f, modes = listOf(uhd5994, uhd50)))
        assertEquals(uhd5994.id, choose(29.97f, modes = listOf(uhd5994, uhd50)))
    }

    // ---- What apply() requests next ----

    @Test
    fun `a video matching nothing releases the mode the last one switched to`() {
        // A 50p channel after a 30p film on a 60/30 Hz panel: nothing matches, so 30 Hz must go.
        val afterFilm = choose(50f, modes = listOf(uhd60, uhd30), current = uhd30)
        assertNull(afterFilm)
        assertEquals(0, FrameRateController.nextModeId(chosenId = afterFilm, requestedId = uhd30.id))
    }

    @Test
    fun `the TV set to never match releases a mode this app had switched to`() {
        val chosen = choose(25f, preference = MATCH_NEVER)
        assertEquals(0, FrameRateController.nextModeId(chosenId = chosen, requestedId = uhd50.id))
    }

    @Test
    fun `nothing is requested when nothing would change`() {
        assertNull(FrameRateController.nextModeId(chosenId = null, requestedId = 0))
        assertNull(FrameRateController.nextModeId(chosenId = 7, requestedId = 7))
        assertEquals(7, FrameRateController.nextModeId(chosenId = 7, requestedId = 0))
        assertEquals(7, FrameRateController.nextModeId(chosenId = 7, requestedId = 3))
    }
}
