package tv.own.owntv.features.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.shell.ReminderDelivery.Place
import tv.own.owntv.features.shell.ReminderDelivery.Start

/**
 * Phase 4: reminder delivery outside the full-screen player. The safe channel comes from
 * ProgramReminders.tuneChannelId (its profile/playlist/missing-channel rules are in ProgramRemindersTest and
 * ReminderRelinkerTest); here, what each place does with it.
 */
class ReminderDeliveryTest {

    private val start = 1_800_000_000_000L

    // ---- Where the customer is -----------------------------------------------------------------------

    @Test fun `places`() {
        assertEquals(Place.MULTISCREEN, ReminderDelivery.place(isMultiscreen = true, playerShown = false))
        assertEquals(Place.PLAYER, ReminderDelivery.place(isMultiscreen = false, playerShown = true))
        assertEquals(Place.BROWSE, ReminderDelivery.place(isMultiscreen = false, playerShown = false))
    }

    // ---- Automatic tuning -----------------------------------------------------------------------------

    @Test fun `Live TV, Guide, Home and Settings tune the reminder's channel`() {
        // Every browse section is the same place: the listener no longer needs the player mounted.
        assertEquals(Start.Tune(42), ReminderDelivery.onStart(Place.BROWSE, channelId = 42, alreadyHandled = false))
    }

    @Test fun `the full-screen player still tunes as before`() {
        assertEquals(Start.Tune(42), ReminderDelivery.onStart(Place.PLAYER, channelId = 42, alreadyHandled = false))
    }

    // ---- Multiscreen ------------------------------------------------------------------------------------

    @Test fun `Multiscreen asks first and never leaves its layout by itself`() {
        assertEquals(Start.Confirm(42), ReminderDelivery.onStart(Place.MULTISCREEN, channelId = 42, alreadyHandled = false))
    }

    @Test fun `Multiscreen shows nothing for a reminder that could not tune`() {
        assertEquals(Start.Ignore, ReminderDelivery.onStart(Place.MULTISCREEN, channelId = null, alreadyHandled = false))
    }

    // ---- Profile isolation and unsafe channels ------------------------------------------------------------

    @Test fun `another profile's reminder, or a missing or ambiguous channel, tunes nothing anywhere`() {
        // tuneChannelId is null in each of those cases.
        for (place in Place.values()) {
            assertEquals(place.name, Start.Ignore, ReminderDelivery.onStart(place, channelId = null, alreadyHandled = false))
        }
    }

    @Test fun `the warning banner is for the active profile only`() {
        assertTrue(ReminderDelivery.showWarning(belongsToActiveProfile = true))
        assertFalse(ReminderDelivery.showWarning(belongsToActiveProfile = false))
    }

    // ---- No duplicate tuning ------------------------------------------------------------------------------

    @Test fun `a reminder already tuned or watched is not tuned again`() {
        for (place in Place.values()) {
            assertEquals(place.name, Start.Ignore, ReminderDelivery.onStart(place, channelId = 42, alreadyHandled = true))
        }
    }

    @Test fun `a reminder is claimed once`() {
        val handled = ReminderDelivery.Handled()
        assertTrue(handled.claim(42, start))
        assertFalse("the same event again", handled.claim(42, start))
        assertTrue(handled.contains(42, start))
        assertTrue("another programme on the channel", handled.claim(42, start + 3_600_000L))
        assertTrue("another channel at the same time", handled.claim(43, start))
    }

    @Test fun `Watch on the warning claims the reminder its start event then skips`() {
        val handled = ReminderDelivery.Handled()
        assertTrue(handled.claim(42, start)) // Watch
        val atStart = ReminderDelivery.onStart(Place.BROWSE, channelId = 42, alreadyHandled = handled.contains(42, start))
        assertEquals(Start.Ignore, atStart)
    }

    @Test fun `the handled set stays bounded and forgets the oldest first`() {
        val handled = ReminderDelivery.Handled(capacity = 3)
        for (i in 1L..4L) handled.claim(i, start)
        assertFalse(handled.contains(1, start))
        assertTrue(handled.contains(2, start))
        assertTrue(handled.contains(4, start))
    }
}
