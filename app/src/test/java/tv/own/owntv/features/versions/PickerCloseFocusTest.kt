package tv.own.owntv.features.versions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.6.1 RC2 on the onn: closing a version picker dropped focus on the top bar's Search chip, and a quick
 * second OK after choosing opened Search behind the film. The picker now hands focus back before it
 * closes ([ModalFocusHandoff]) and a choice arms a short app-wide OK guard ([ConfirmKeyGuard]).
 * (Compose focus itself is checked on the device; these pin the rules it relies on.)
 */
class PickerCloseFocusTest {

    private val armedAt = 10_000L

    @Test fun `nothing is swallowed until a version is chosen`() {
        val guard = ConfirmKeyGuard()
        assertFalse(guard.swallows(isConfirmKey = true, eventUptimeMs = 5_000))
    }

    @Test fun `a quick second OK after choosing is swallowed, press and release alike`() {
        val guard = ConfirmKeyGuard()
        guard.arm(armedAt)
        assertTrue(guard.swallows(true, armedAt + 1))      // second press down
        assertTrue(guard.swallows(true, armedAt + 180))    // its release
        assertTrue(guard.swallows(true, armedAt + ConfirmKeyGuard.WINDOW_MS - 1))
    }

    @Test fun `other keys and later presses pass`() {
        val guard = ConfirmKeyGuard()
        guard.arm(armedAt)
        assertFalse(guard.swallows(isConfirmKey = false, eventUptimeMs = armedAt + 50)) // D-pad, Back
        assertFalse(guard.swallows(true, armedAt + ConfirmKeyGuard.WINDOW_MS))
        assertFalse(guard.swallows(true, armedAt + 5_000))
    }

    @Test fun `a press from before the choice is not swallowed`() {
        val guard = ConfirmKeyGuard()
        guard.arm(armedAt)
        assertFalse(guard.swallows(true, armedAt - 30))
    }

    @Test fun `the picker keeps focus until it closes, then lets it out`() {
        val handoff = ModalFocusHandoff()
        assertFalse(handoff.released) // directional moves stay inside while open
        assertFalse(handoff.handBack(null)) // no opener: nothing asked to focus...
        assertTrue(handoff.released) // ...but focus may now leave the closing picker
    }

    @Test fun `confirm keys are OK and the two Enters, nothing else`() {
        assertTrue(ConfirmKeyGuard.isConfirm(androidx.compose.ui.input.key.Key.DirectionCenter))
        assertTrue(ConfirmKeyGuard.isConfirm(androidx.compose.ui.input.key.Key.Enter))
        assertTrue(ConfirmKeyGuard.isConfirm(androidx.compose.ui.input.key.Key.NumPadEnter))
        assertFalse(ConfirmKeyGuard.isConfirm(androidx.compose.ui.input.key.Key.Back))
        assertFalse(ConfirmKeyGuard.isConfirm(androidx.compose.ui.input.key.Key.DirectionDown))
    }
}
