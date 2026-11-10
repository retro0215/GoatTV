package tv.own.owntv.features.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.6.1 RC3 on the onn: after switching a show's version, focus sat on Play Trailer (Play appears only
 * once the new version's episodes load), and leaving a series page dropped focus on the Search chip.
 * Compose focus itself is checked on the device; these pin the rules the page and catalog follow.
 */
class SeriesPageFocusTest {

    @Test fun `Play takes focus when it appears after the page has opened`() {
        val rule = PlayFocusOnArrival()
        assertFalse(rule.takePlayFocus(playShown = false)) // the new version's episodes are still loading
        assertTrue(rule.takePlayFocus(playShown = true)) // Play is there: focus moves to it
    }

    @Test fun `it happens once, so later reloads never pull focus back`() {
        val rule = PlayFocusOnArrival()
        assertTrue(rule.takePlayFocus(true))
        assertFalse(rule.takePlayFocus(true))
        assertFalse(rule.armed)
    }

    @Test fun `a key pressed first leaves focus wherever the customer put it`() {
        val rule = PlayFocusOnArrival()
        rule.onUserKey() // D-pad to the Versions button before the episodes arrived
        assertFalse(rule.takePlayFocus(true))
    }

    @Test fun `a fresh page, such as the version switched to, starts armed`() {
        val old = PlayFocusOnArrival()
        old.onUserKey() // the OK that chose the version
        assertTrue(PlayFocusOnArrival().takePlayFocus(true)) // the page keyed on the new show's id
    }

    @Test fun `back on the catalog, focus goes to the show's card, or the first card if it is not listed`() {
        assertEquals(7, SeriesGridReturn.target(7))
        assertEquals(0, SeriesGridReturn.target(0))
        assertEquals(0, SeriesGridReturn.target(-1)) // opened from Home or another category
    }
}
