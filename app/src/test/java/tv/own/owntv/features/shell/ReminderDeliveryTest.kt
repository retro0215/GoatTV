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

    // ---- 4.6.0: the banner over a programme dialog -------------------------------------------------------

    private val shell by lazy { java.io.File("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt").readText().replace("\r\n", "\n") }
    private val banner by lazy { shell.substringAfter("private fun ReminderBanner(").substringBefore("private fun ReminderBannerContent(") }
    private val bannerContent by lazy { shell.substringAfter("private fun ReminderBannerContent(").substringBefore("private fun ReminderWarningButton(") }

    @Test fun `the banner is its own window, so it takes the remote over a programme dialog`() {
        // A programme dialog is a window of its own; a banner drawn in the app's window sat under it.
        assertTrue("androidx.compose.ui.window.Dialog(" in banner)
        assertTrue("dismissOnBackPress = false" in banner)
        assertTrue("dismissOnClickOutside = false" in banner)
        // It still asks for focus on Watch when it opens, and keeps its own Back handling (release dismisses).
        assertTrue("runCatching { watchFocus.requestFocus() }" in bannerContent)
        assertTrue("bannerBackKeys.onRelease(reminderDialogOpen = true)" in bannerContent)
    }

    @Test fun `an OK held from the screen below cannot press Watch`() {
        assertTrue(".longPressMenuGuard()" in bannerContent)
        assertTrue(bannerContent.indexOf(".longPressMenuGuard()") < bannerContent.indexOf(".onPreviewKeyEvent"))
    }

    @Test fun `Dismiss leaves focus where it was, under the banner`() {
        // A programme dialog, the grid or the player kept focus under the banner's window.
        assertFalse(ReminderDelivery.restoreFocusOnDismiss(playerShown = false, shellHasFocus = true))
        assertFalse(ReminderDelivery.restoreFocusOnDismiss(playerShown = true, shellHasFocus = false))
        assertFalse(ReminderDelivery.restoreFocusOnDismiss(playerShown = true, shellHasFocus = true))
        // Browsing with focus lost meanwhile: the saved control comes back (else the sidebar).
        assertTrue(ReminderDelivery.restoreFocusOnDismiss(playerShown = false, shellHasFocus = false))
        assertTrue("ReminderDelivery.restoreFocusOnDismiss(playerShownNow, shellHasFocus)" in shell)
    }

    @Test fun `Watch and Multiscreen keep their rules`() {
        // Watch still goes through the claimed, profile-checked tune; Multiscreen still leaves its layout first.
        val watch = shell.substringAfter("val watchReminder:").substringBefore("val sidebarLabel")
        assertTrue("programReminders.tuneChannelId(reminder)" in watch)
        assertTrue("handledReminders.claim(reminder.channelId, reminder.programStartMs)" in watch)
        assertEquals(Start.Confirm(42), ReminderDelivery.onStart(Place.MULTISCREEN, channelId = 42, alreadyHandled = false))
        val tune = shell.substringAfter("suspend fun tuneReminderChannel(channelId: Long): Boolean {").substringBefore("LaunchedEffect(Unit)")
        assertTrue(tune.indexOf("onSelectSection(MainSection.LIVE_TV)") < tune.indexOf("ensurePlayingByIdAsync"))
    }

    // ---- 4.6.0: held OK when the warning opens -----------------------------------------------------------

    @Test fun `Multiscreen - a canceled OK release cannot commit a tile move or open a tile`() {
        // The warning's window takes focus over Multiscreen; Multiscreen is inside the shell's root box, which
        // drops canceled releases, so a held OK on a tile ends without acting. Starting still needs Watch.
        val root = shell.substringAfter("CompositionLocalProvider(LocalContentScrolled provides contentScrolled) {")
            .substringBefore("MultiscreenScreen(")
        assertTrue(".ignoreCanceledKeyReleases()" in root)
        assertEquals(Start.Confirm(42), ReminderDelivery.onStart(Place.MULTISCREEN, channelId = 42, alreadyHandled = false))
    }

    @Test fun `Dismiss and Back keep their focus rules with the canceled-release filter`() {
        // Back on the warning still acts on its own release (press consumed first); Dismiss still leaves focus on
        // the control under the warning.
        assertTrue("bannerBackKeys.onRelease(reminderDialogOpen = true)" in bannerContent)
        assertFalse(ReminderDelivery.restoreFocusOnDismiss(playerShown = false, shellHasFocus = true))
        assertTrue(ReminderDelivery.restoreFocusOnDismiss(playerShown = false, shellHasFocus = false))
    }
}

