package tv.own.owntv.features.multiscreen

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.features.multiscreen.MultiscreenDialogKeys.Action

/**
 * On the onn, Back in the Multiscreen channel picker and tile menu only moved focus onto the invisible
 * backdrop (Compose turns an unhandled Back into a focus "exit" before any BackHandler runs). The dialogs
 * now handle Back themselves; these are the rules they apply to each key.
 */
class MultiscreenDialogKeysTest {

    @Test
    fun `Back press is consumed so Compose never turns it into a focus move`() {
        assertEquals(Action.CONSUME, MultiscreenDialogKeys.backAction(Key.Back, KeyEventType.KeyDown))
    }

    @Test
    fun `Back release dismisses - one press closes the dialog`() {
        assertEquals(Action.DISMISS, MultiscreenDialogKeys.backAction(Key.Back, KeyEventType.KeyUp))
    }

    @Test
    fun `Escape behaves like Back for keyboards`() {
        assertEquals(Action.CONSUME, MultiscreenDialogKeys.backAction(Key.Escape, KeyEventType.KeyDown))
        assertEquals(Action.DISMISS, MultiscreenDialogKeys.backAction(Key.Escape, KeyEventType.KeyUp))
    }

    @Test
    fun `D-pad, OK and typing keys are left to the dialog's controls`() {
        val keys = listOf(
            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
            Key.DirectionCenter, Key.Enter, Key.A, Key.Spacebar,
        )
        for (key in keys) for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            assertEquals("$key $type", Action.PASS, MultiscreenDialogKeys.backAction(key, type))
        }
    }
}
