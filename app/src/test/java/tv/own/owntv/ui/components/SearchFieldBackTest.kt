package tv.own.owntv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 4.6.1 RC7 on the onn: in the Guide's Category picker, after typing in its search field, Back closed the
 * TV keyboard, the next Back only moved focus from the field to its own pill, and the picker needed a
 * third. A picker now closes on that second Back; a search bar on a screen keeps its old behaviour.
 * (The key events themselves are checked on the device; this pins the rule SearchBar follows.)
 */
class SearchFieldBackTest {

    @Test fun `in a picker, Back after the keyboard closed closes the picker`() {
        assertEquals(SearchFieldBack.Action.CLOSE, SearchFieldBack.afterKeyboardClosed(hasCloseAction = true))
    }

    @Test fun `on a screen, Back after the keyboard closed returns to the search pill, as before`() {
        assertEquals(SearchFieldBack.Action.RETURN_TO_PILL, SearchFieldBack.afterKeyboardClosed(hasCloseAction = false))
    }
}
