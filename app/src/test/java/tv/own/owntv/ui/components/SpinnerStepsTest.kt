package tv.own.owntv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loading spinner turns in 16 steps instead of redrawing every frame (upstream OwnTV 55678114), at
 * the same 0.9 s per turn as before.
 */
class SpinnerStepsTest {

    @Test
    fun `one turn takes the same time as before`() {
        assertEquals(900L, SPINNER_TURN_MS)
        assertTrue(SPINNER_STEP_MS * SPINNER_STEPS in 880L..900L)
    }

    @Test
    fun `about 18 redraws a second instead of 60`() {
        val perSecond = 1000.0 / SPINNER_STEP_MS
        assertTrue("redraws a second: $perSecond", perSecond in 15.0..20.0)
    }

    @Test
    fun `the steps cover a full turn and wrap`() {
        var step = 0
        val angles = ArrayList<Float>()
        repeat(SPINNER_STEPS) {
            angles += spinnerAngle(step)
            step = nextSpinnerStep(step)
        }
        assertEquals(0, step)
        assertEquals(0f, angles.first(), 0f)
        assertEquals(360f - 360f / SPINNER_STEPS, angles.last(), 0.001f)
        assertEquals(angles.sorted(), angles) // always the same direction
    }
}
