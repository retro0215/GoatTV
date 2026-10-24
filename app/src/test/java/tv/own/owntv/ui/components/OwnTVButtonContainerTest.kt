package tv.own.owntv.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tv.own.owntv.features.settings.REMOVE_REMINDER_CONFIRM_STYLE

/** Focused and unfocused buttons must look different where a choice is made (Keep / Remove reminder). */
class OwnTVButtonContainerTest {
    private val primary = Color(0xFF26D0B5)
    private val card = Color(0xFF2A2A2A)
    private val container = Color(0xFF004F46)

    @Test fun `a secondary button changes fill when focused`() {
        assertNotEquals(
            ownTvButtonContainer(OwnTVButtonStyle.SECONDARY, focused = false, primary, card, container),
            ownTvButtonContainer(OwnTVButtonStyle.SECONDARY, focused = true, primary, card, container),
        )
    }

    @Test fun `a primary button keeps its fill when focused, which is why the reminder dialog avoids it`() {
        assertEquals(
            ownTvButtonContainer(OwnTVButtonStyle.PRIMARY, focused = false, primary, card, container),
            ownTvButtonContainer(OwnTVButtonStyle.PRIMARY, focused = true, primary, card, container),
        )
        assertEquals(OwnTVButtonStyle.SECONDARY, REMOVE_REMINDER_CONFIRM_STYLE)
    }

    @Test fun `in the reminder dialog only the focused option carries the focus fill`() {
        val keepFocused = ownTvButtonContainer(OwnTVButtonStyle.SECONDARY, focused = true, primary, card, container)
        val removeUnfocused = ownTvButtonContainer(REMOVE_REMINDER_CONFIRM_STYLE, focused = false, primary, card, container)
        assertNotEquals(keepFocused, removeUnfocused)
        assertEquals(card, removeUnfocused)
    }
}
