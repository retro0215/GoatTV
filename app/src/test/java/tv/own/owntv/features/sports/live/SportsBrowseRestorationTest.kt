package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsBrowseRestorationTest {

    private val events: List<Any> = listOf("evt_a", "evt_b", "evt_c", "evt_d", "evt_e")
    private val channels: List<Any> = listOf(101L, 102L, 103L, 104L)

    @Test
    fun `row start - same first item by stable key, offset kept`() {
        val saved = SportsRowPosition(firstKey = "evt_c", firstIndex = 2, offset = 37)
        assertEquals(2 to 37, SportsBrowseRestoration.rowStart(saved, events))
        // Data refreshed while away: an item before it was removed, the row still starts at evt_c.
        assertEquals(1 to 37, SportsBrowseRestoration.rowStart(saved, events - "evt_a"))
    }

    @Test
    fun `row start - first item gone, old index clamped, offset dropped`() {
        val saved = SportsRowPosition(firstKey = "evt_x", firstIndex = 9, offset = 37)
        assertEquals(4 to 0, SportsBrowseRestoration.rowStart(saved, events))
        assertEquals(0 to 0, SportsBrowseRestoration.rowStart(null, events))
        assertEquals(0 to 0, SportsBrowseRestoration.rowStart(saved, emptyList()))
    }

    @Test
    fun `stable event restoration`() {
        val target = SportsFocusTarget("events:popular", "evt_d", index = 3)
        assertEquals(3, SportsBrowseRestoration.focusIndex(target, events))
        // Reordered by a refresh: still the same event, found by id rather than index.
        assertEquals(0, SportsBrowseRestoration.focusIndex(target, listOf("evt_d", "evt_a", "evt_b")))
    }

    @Test
    fun `stable channel restoration and horizontal position`() {
        val target = SportsFocusTarget("channels:NFL", 103L, index = 2)
        assertEquals(2, SportsBrowseRestoration.focusIndex(target, channels))
        assertEquals(3, SportsBrowseRestoration.focusIndex(target, listOf(100L) + channels))
        assertEquals(1 to 12, SportsBrowseRestoration.rowStart(SportsRowPosition(102L, 1, 12), channels))
    }

    @Test
    fun `item removed while away - nearest item in the same row, never a crash`() {
        val target = SportsFocusTarget("events:league:nfl", "evt_e", index = 4)
        assertEquals(2, SportsBrowseRestoration.focusIndex(target, listOf("evt_a", "evt_b", "evt_c")))
        assertEquals(1, SportsBrowseRestoration.focusIndex(SportsFocusTarget("r", "gone", 1), events))
        assertNull(SportsBrowseRestoration.focusIndex(target, emptyList()))
    }

    @Test
    fun `restorer - only the target row restores, only while pending, finishes once`() {
        val state = SportsBrowseState().apply { focus = SportsFocusTarget("channels:NFL", 103L, 2) }
        var done = 0
        val restorer = SportsBrowseRestorer(state, restoring = true) { done++ }
        assertTrue(restorer.pending)
        assertNull(restorer.targetFor("events:popular"))
        assertEquals(103L, restorer.targetFor("channels:NFL")?.itemKey)
        restorer.finish()
        restorer.finish()
        assertEquals(1, done)
        assertFalse(restorer.pending)
        assertNull(restorer.targetFor("channels:NFL")) // later recompositions never re-focus
    }

    @Test
    fun `entering from the menu does not restore`() {
        val state = SportsBrowseState().apply { focus = SportsFocusTarget("channels:NFL", 103L, 2) }
        val restorer = SportsBrowseRestorer(state, restoring = false) {}
        assertFalse(restorer.pending)
        assertNull(restorer.targetFor("channels:NFL"))
    }

    @Test
    fun `focus is recorded by stable identity, clear resets everything`() {
        val state = SportsBrowseState()
        val restorer = SportsBrowseRestorer(state, restoring = false) {}
        restorer.onItemFocused("events:popular", "evt_b", 1)
        assertEquals(SportsFocusTarget("events:popular", "evt_b", 1), state.focus)
        state.scrollY = 900
        state.rows["events:popular"] = SportsRowPosition("evt_b", 1, 0)
        state.clear()
        assertNull(state.focus)
        assertEquals(0, state.scrollY)
        assertTrue(state.rows.isEmpty())
    }
}
