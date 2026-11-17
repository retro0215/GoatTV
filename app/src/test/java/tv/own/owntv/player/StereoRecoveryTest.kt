package tv.own.owntv.player

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "No sound? Switch to stereo" (Phase 4B). The session object is the whole policy the live engine applies:
 * when the row is offered, what the next player's sink is, and when the override ends.
 */
class StereoRecoveryTest {

    private fun available(
        s: StereoRecoverySession,
        mode: SurroundMode = SurroundMode.AUTO,
        feature: Boolean = true,
        tuned: Boolean = true,
        hasAudio: Boolean = true,
        silenced: Boolean = false,
    ) = s.isAvailable(feature, mode, tuned, hasAudio, silenced)

    // --- Modes ---------------------------------------------------------------------------------------

    @Test fun `recovery from Auto pins the next player to stereo`() {
        val s = StereoRecoverySession()
        assertFalse(s.forceStereo(SurroundMode.AUTO))
        assertTrue(available(s, SurroundMode.AUTO))
        assertTrue(s.begin())
        assertTrue(s.forceStereo(SurroundMode.AUTO))
    }

    @Test fun `recovery from Surround pins the next player to stereo`() {
        val s = StereoRecoverySession()
        assertFalse(s.forceStereo(SurroundMode.SURROUND))
        assertTrue(available(s, SurroundMode.SURROUND))
        assertTrue(s.begin())
        assertTrue(s.forceStereo(SurroundMode.SURROUND))
    }

    @Test fun `stereo only never offers it - the output is already stereo`() {
        val s = StereoRecoverySession()
        assertTrue(s.forceStereo(SurroundMode.STEREO))
        assertFalse(available(s, SurroundMode.STEREO))
    }

    // --- When it is offered --------------------------------------------------------------------------

    @Test fun `hidden for an intentionally muted preview or a Multiscreen suspension`() {
        assertFalse(available(StereoRecoverySession(), silenced = true))
    }

    @Test fun `hidden with nothing tuned or no audio track`() {
        assertFalse(available(StereoRecoverySession(), tuned = false))
        assertFalse(available(StereoRecoverySession(), hasAudio = false))
    }

    @Test fun `hidden when the brand flag is off`() {
        assertFalse(available(StereoRecoverySession(), feature = false))
    }

    @Test fun `never triggers by itself - only begin() starts it`() {
        val s = StereoRecoverySession()
        repeat(5) { available(s) } // the HUD asking is not a request
        assertFalse(s.active)
        assertFalse(s.forceStereo(SurroundMode.AUTO))
    }

    // --- Duplicates ----------------------------------------------------------------------------------

    @Test fun `a second request while one is rebuilding is refused`() {
        val s = StereoRecoverySession()
        assertTrue(s.begin())
        assertTrue(s.inFlight)
        assertFalse(available(s))
        assertFalse(s.begin())
    }

    @Test fun `once applied it is not offered again in the same session`() {
        val s = StereoRecoverySession()
        s.begin(); s.finish(success = true)
        assertTrue(s.active)
        assertFalse(s.inFlight)
        assertFalse(available(s))
        assertFalse(s.begin())
    }

    @Test fun `a failed rebuild gives the override back and allows another try`() {
        val s = StereoRecoverySession()
        s.begin(); s.finish(success = false)
        assertFalse(s.active)
        assertFalse(s.forceStereo(SurroundMode.AUTO))
        assertTrue(available(s))
    }

    // --- Session lifetime ----------------------------------------------------------------------------

    @Test fun `channel changes keep the override - only clear() ends it`() {
        val s = StereoRecoverySession()
        s.begin(); s.finish(true)
        // A zap re-reads the sink choice for every new player; nothing in the session resets on its own.
        repeat(3) { assertTrue("zap $it", s.forceStereo(SurroundMode.AUTO)) }
    }

    @Test fun `leaving the playback session clears it`() {
        val s = StereoRecoverySession()
        s.begin(); s.finish(true)
        assertTrue(s.clear())
        assertFalse(s.active)
        assertFalse(s.forceStereo(SurroundMode.AUTO))
        assertFalse("nothing to clear twice", s.clear())
    }

    @Test fun `changing the Surround setting clears it, even mid-rebuild`() {
        val s = StereoRecoverySession()
        s.begin()
        assertTrue(s.clear())
        assertFalse(s.inFlight)
        assertFalse(s.forceStereo(SurroundMode.SURROUND))
    }

    // --- Preferences and other players ---------------------------------------------------------------

    @Test fun `the saved Surround setting and the app-wide policy are untouched`() {
        val s = StereoRecoverySession()
        s.begin(); s.finish(true)
        // Every other player (Multiscreen tiles, VOD, Home hero) decides from the policy, not the session.
        assertTrue(AudioOutputPolicy.allowsMultichannel(SurroundMode.AUTO))
        assertTrue(AudioOutputPolicy.allowsMultichannel(SurroundMode.SURROUND))
        assertEquals(SurroundMode.AUTO, SurroundMode.of("AUTO", null))
    }

    @Test fun `two engines never share the override`() {
        val live = StereoRecoverySession()
        val other = StereoRecoverySession()
        live.begin(); live.finish(true)
        assertFalse(other.forceStereo(SurroundMode.AUTO))
    }

    @Test fun `mpv and every other engine do not offer it`() {
        // The interface default is what MpvPlaybackEngine and the Multiscreen tile engine inherit.
        val mpv = MpvPlaybackEngine(mockk(relaxed = true))
        assertFalse(mpv.stereoRecoveryAvailable())
        mpv.recoverAudioAsStereo() // a no-op, never a rebuild
    }

    // --- Brand isolation -----------------------------------------------------------------------------

    @Test fun `enabled on the GoatTV build under test`() {
        assertTrue(tv.own.owntv.BuildConfig.AUDIO_STEREO_RECOVERY)
    }

    @Test fun `only the goat flavor turns the flag on`() {
        val gradle = File("build.gradle.kts").takeIf { it.exists() } ?: File("app/build.gradle.kts")
        val text = gradle.readText()
        val on = Regex("""buildConfigField\("boolean", "AUDIO_STEREO_RECOVERY", "true"\)""").findAll(text).toList()
        assertEquals("exactly one flavor enables it", 1, on.size)
        // create("goat") also appears under signingConfigs — look inside productFlavors only.
        val goat = text.indexOf("create(\"goat\")", text.indexOf("productFlavors {"))
        val next = listOf("create(\"fivestar\")", "create(\"supreme\")", "create(\"allaccess\")")
            .map { text.indexOf(it, goat + 1) }.filter { it > 0 }.minOrNull() ?: text.length
        assertTrue("the enabling line sits inside the goat flavor", on.single().range.first in goat until next)
        assertTrue("default is off", text.contains("""buildConfigField("boolean", "AUDIO_STEREO_RECOVERY", "false")"""))
    }
}
