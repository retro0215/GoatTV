package tv.own.owntv.features.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.shell.GuidePlayback.Back
import tv.own.owntv.features.shell.GuidePlayback.FocusOwner
import tv.own.owntv.features.shell.GuidePlayback.Surface
import tv.own.owntv.features.shell.GuidePlayback.Teardown

/** Phase 4: live playback between the full-screen player and the TV Guide's preview. */
class GuidePlaybackTest {

    // ---- Live TV -> Guide -> Live TV ---------------------------------------------------------------------

    @Test fun `the player's TV Guide button keeps the channel playing in the Guide`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertTrue(s.live)
        assertTrue(s.backToPlayer)
        assertEquals(MainSection.LIVE_TV, s.returnSection)
        // Nothing is torn down by going to the Guide itself.
        assertNull(GuidePlayback.onSectionChanged(s, MainSection.EPG, playerShown = false))
    }

    @Test fun `Back in a Guide opened from the player returns to the player`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        // From the header (Back in the grid first steps to Categories): the shell returns to the player.
        assertEquals(Back.RETURN_TO_PLAYER, GuidePlayback.back(s))
    }

    @Test fun `the return goes back over the section the player sat on`() {
        assertEquals(MainSection.EPG, GuidePlayback.openFromPlayer(MainSection.EPG).returnSection)
    }

    @Test fun `the TV Guide button is only offered on a tuned live channel`() {
        assertTrue(GuidePlayback.canOpenGuide(isTunedLive = true, hasChannel = true))
        assertFalse("catch-up", GuidePlayback.canOpenGuide(isTunedLive = false, hasChannel = true))
        assertFalse("movie or episode", GuidePlayback.canOpenGuide(isTunedLive = false, hasChannel = false))
        assertFalse("no channel tuned", GuidePlayback.canOpenGuide(isTunedLive = true, hasChannel = false))
    }

    @Test fun `the TV Guide button does not depend on the playback engine`() {
        // Phase 4 hid it on mpv: its inputs are the tuned channel only, the same for ExoPlayer and mpv.
        for (engineIsMpv in listOf(false, true)) {
            assertTrue("mpv=$engineIsMpv", GuidePlayback.canOpenGuide(isTunedLive = true, hasChannel = true))
        }
    }

    // ---- Player ownership and decoder reuse ---------------------------------------------------------------

    @Test fun `the preview hosts the same engine the player used`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertEquals(Surface.EXO, GuidePlayback.previewSurface(s, playerShown = false, liveOnExo = true))
        assertEquals(Surface.MPV, GuidePlayback.previewSurface(s, playerShown = false, liveOnExo = false))
    }

    @Test fun `an engine never has two surfaces - no preview while the player is shown`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertEquals(Surface.PLACEHOLDER, GuidePlayback.previewSurface(s, playerShown = true, liveOnExo = true))
        assertEquals(Surface.PLACEHOLDER, GuidePlayback.previewSurface(s, playerShown = true, liveOnExo = false))
    }

    @Test fun `mpv keeps playing while it is the Guide's picture`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertFalse(GuidePlayback.mayStopMpvOffLive(MainSection.EPG, playerShown = false, session = s))
        // The usual rule still applies once the Guide no longer plays live.
        assertTrue(GuidePlayback.mayStopMpvOffLive(MainSection.EPG, playerShown = false, session = GuidePlayback.NONE))
        assertFalse(GuidePlayback.mayStopMpvOffLive(MainSection.LIVE_TV, playerShown = false, session = GuidePlayback.NONE))
    }

    // ---- Browsing, Watch, and a Guide opened without playback ---------------------------------------------

    @Test fun `a Guide opened from the sidebar starts nothing`() {
        val s = GuidePlayback.NONE
        assertEquals(Surface.PLACEHOLDER, GuidePlayback.previewSurface(s, playerShown = false, liveOnExo = true))
        assertEquals(FocusOwner.NONE, GuidePlayback.audioFocusOwner(playerShown = false, session = s, liveOnExo = true))
        assertEquals(Back.DEFAULT, GuidePlayback.back(s))
    }

    @Test fun `browsing the guide changes nothing about what plays`() {
        // The session is the only input the preview has: cursor moves never reach it. A whole browse is
        // just repeated reads of the same session.
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        val reads = (1..50).map { GuidePlayback.previewSurface(s, playerShown = false, liveOnExo = true) }.toSet()
        assertEquals(setOf(Surface.EXO), reads)
        assertEquals(setOf<Teardown?>(null), (1..50).map { GuidePlayback.onSectionChanged(s, MainSection.EPG, false) }.toSet())
    }

    @Test fun `Back from a channel watched from the Guide keeps it playing there, without a loop`() {
        val s = GuidePlayback.onPlayerExit(MainSection.EPG, isTunedLive = true, isLiveStream = true, returnSection = null)
        assertTrue(s.live)
        assertFalse(s.backToPlayer)
        assertEquals(Back.DEFAULT, GuidePlayback.back(s))
    }

    @Test fun `other exits stop playback as before`() {
        assertEquals(GuidePlayback.NONE, GuidePlayback.onPlayerExit(MainSection.LIVE_TV, true, true, null))
        assertEquals("catch-up from the Guide", GuidePlayback.NONE, GuidePlayback.onPlayerExit(MainSection.EPG, false, true, null))
        assertEquals("a movie", GuidePlayback.NONE, GuidePlayback.onPlayerExit(MainSection.EPG, false, false, null))
        assertEquals("rewound live (not live)", GuidePlayback.NONE, GuidePlayback.onPlayerExit(MainSection.EPG, true, false, null))
        assertEquals("Sports' own return", GuidePlayback.NONE, GuidePlayback.onPlayerExit(MainSection.EPG, true, true, MainSection.SPORTS))
    }

    // ---- Audio focus --------------------------------------------------------------------------------------

    @Test fun `the Guide's live preview keeps audio focus with its engine`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertEquals(FocusOwner.EXO, GuidePlayback.audioFocusOwner(playerShown = false, session = s, liveOnExo = true))
        assertEquals(FocusOwner.MPV, GuidePlayback.audioFocusOwner(playerShown = false, session = s, liveOnExo = false))
    }

    @Test fun `audio focus follows the player as before`() {
        assertEquals(FocusOwner.EXO, GuidePlayback.audioFocusOwner(playerShown = true, session = GuidePlayback.NONE, liveOnExo = true))
        assertEquals(FocusOwner.MPV, GuidePlayback.audioFocusOwner(playerShown = true, session = GuidePlayback.NONE, liveOnExo = false))
        assertEquals(FocusOwner.NONE, GuidePlayback.audioFocusOwner(playerShown = false, session = GuidePlayback.NONE, liveOnExo = true))
    }

    // ---- Leaving the Guide --------------------------------------------------------------------------------

    @Test fun `leaving the Guide for Live TV hands the stream to its preview pane`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertEquals(Teardown.KEEP_FOR_LIVE_PANE, GuidePlayback.onSectionChanged(s, MainSection.LIVE_TV, playerShown = false))
    }

    @Test fun `leaving the Guide anywhere else stops the stream`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        for (section in listOf(MainSection.HOME, MainSection.SETTINGS, MainSection.MOVIES, MainSection.MULTISCREEN)) {
            assertEquals(section.name, Teardown.STOP, GuidePlayback.onSectionChanged(s, section, playerShown = false))
        }
    }

    @Test fun `returning to the player is not a teardown`() {
        val s = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertNull(GuidePlayback.onSectionChanged(s, MainSection.LIVE_TV, playerShown = true))
        assertNull(GuidePlayback.onSectionChanged(GuidePlayback.NONE, MainSection.HOME, playerShown = false))
    }

}
