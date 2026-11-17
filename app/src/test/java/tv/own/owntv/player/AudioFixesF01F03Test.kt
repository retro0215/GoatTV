package tv.own.owntv.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import tv.own.owntv.player.AudioRePrime.Action

/** 4.6.2 audio fixes: F-01 (passthrough re-prime on the real output) and F-03 (guard after an Exo restart). */
@UnstableApi
class AudioFixesF01F03Test {

    // ===== F-01 ===================================================================================

    private fun decide(accurate: Boolean = true, selected: Boolean = false, legacy: Boolean = false, output: Boolean?) =
        AudioRePrime.decide(accurate, alreadySelected = selected, legacyPassthrough = legacy, outputPassthrough = output)

    @Test fun `automatic switch while bitstreaming re-primes even though the legacy guess is still false`() {
        // The guard/carry switch at the first track report: the old flag is false, the open AudioTrack is AC-3.
        assertEquals(Action.SELECT_AND_REPRIME, decide(legacy = false, output = true))
    }

    @Test fun `atmos and dts-hd, which the legacy guess never caught, re-prime on the real output`() {
        assertEquals(Action.SELECT_AND_REPRIME, decide(legacy = false, output = AudioOutputReport.isEncodedPassthrough(androidx.media3.common.C.ENCODING_E_AC3_JOC)))
        assertEquals(Action.SELECT_AND_REPRIME, decide(legacy = false, output = AudioOutputReport.isEncodedPassthrough(androidx.media3.common.C.ENCODING_DTS_HD)))
    }

    @Test fun `automatic switch during PCM playback is a plain switch - no seek`() {
        assertEquals(Action.SELECT, decide(output = false))
        assertEquals(Action.SELECT, decide(output = AudioOutputReport.isEncodedPassthrough(androidx.media3.common.C.ENCODING_PCM_16BIT)))
    }

    @Test fun `a switch before any output is open needs no re-prime`() {
        assertEquals(Action.SELECT, decide(output = null))
    }

    @Test fun `manual selection during passthrough re-primes the same way`() {
        assertEquals(Action.SELECT_AND_REPRIME, decide(legacy = true, output = true))
    }

    @Test fun `picking the track that is already playing does nothing`() {
        assertEquals(Action.NONE, decide(selected = true, legacy = true, output = true))
        assertEquals(Action.NONE, decide(selected = true, output = false))
    }

    @Test fun `with the GoatTV VOD audio flags off the old rule is unchanged`() {
        assertEquals(Action.SELECT_AND_REPRIME, decide(accurate = false, selected = true, legacy = true, output = true))
        assertEquals("the real output is not consulted", Action.SELECT, decide(accurate = false, legacy = false, output = true))
        assertEquals(Action.SELECT, decide(accurate = false, legacy = false, output = false))
    }

    @Test fun `no re-prime loop - the guard and carry each switch at most once per lifecycle`() {
        // A re-prime is a seek: it does not change tracks, and the next track report finds the guard marked.
        val s = VodAudioSelectionSession(enabled = true, guardEnabled = true).apply { newItem(isLive = false) }
        assertTrue(s.mayGuard("exo"))
        s.markGuarded("exo") // the switch (and its one re-prime) happened
        repeat(5) { assertFalse("track report $it after the seek", s.mayGuard("exo")) }
    }

    @Test fun `the re-prime keeps the playback position - it seeks to where playback already is`() {
        // Device-side behaviour (a real ExoPlayer), pinned by the source: the re-prime is seekTo(currentPosition).
        val src = File("src/main/java/tv/own/owntv/player/ExoSubtitleEngine.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/tv/own/owntv/player/ExoSubtitleEngine.kt")
        val text = src.readText()
        assertTrue(text.contains("if (action == AudioRePrime.Action.SELECT_AND_REPRIME)"))
        assertTrue(text.contains("runCatching { p.seekTo(p.currentPosition) }"))
    }

    // ===== F-03 ===================================================================================

    private fun session() = VodAudioSelectionSession(enabled = true, guardEnabled = true).apply { newItem(isLive = false) }
    private val tracks = listOf(
        AudioCandidate(0, 0, "en", "aac", 2, "Commentary", selected = true),
        AudioCandidate(1, 1, "en", "eac3", 6, null, selected = false),
    )

    @Test fun `initial playback - the guard decides once`() {
        val s = session()
        assertTrue(s.mayGuard("exo"))
        s.markGuarded("exo")
        assertFalse(s.mayGuard("exo"))
    }

    @Test fun `an ExoPlayer restart of the same movie lets the guard decide again`() {
        val s = session()
        s.markGuarded("exo")
        s.engineStarted("exo") // software-decoder rescue / fallback restart: startExo
        assertTrue(s.mayGuard("exo"))
    }

    @Test fun `an ExoPlayer restart of the same episode does the same`() {
        val s = session() // an episode is an item like a movie; only newItem() separates episodes
        s.markGuarded("exo"); s.engineStarted("exo")
        assertTrue(s.mayGuard("exo"))
    }

    @Test fun `commentary is corrected again after the restart`() {
        val s = session()
        val infos = listOf(
            mpvAudioTrackInfo(0, "Commentary", "eng", "aac", 2, false, false, selected = true),
            mpvAudioTrackInfo(1, null, "eng", "eac3", 6, false, false, selected = false),
        )
        assertEquals(1, MainAudioGuard.decide(infos, null).to)
        s.markGuarded("exo")
        s.engineStarted("exo") // the restarted player picked the commentary again
        assertTrue(s.mayGuard("exo"))
        assertEquals(1, MainAudioGuard.decide(infos, null).to)
    }

    @Test fun `a manual commentary pick stays respected after a restart`() {
        val s = session()
        s.userSelected("exo", tracks[0], tracks) // the customer chose the commentary on purpose
        s.engineStarted("exo")
        assertFalse(s.mayGuard("exo"))
        assertEquals("the carry re-applies their pick instead", 0, s.currentChoice()!!.index)
    }

    @Test fun `a manual main-audio pick stays respected after a restart`() {
        val s = session()
        s.userSelected("exo", tracks[1], tracks)
        s.engineStarted("exo")
        assertFalse(s.mayGuard("exo"))
        assertEquals(1, s.currentChoice()!!.index)
    }

    @Test fun `the guard does not run repeatedly within one lifecycle`() {
        val s = session()
        s.engineStarted("exo") // the first start of the lifecycle
        s.markGuarded("exo")
        repeat(3) { assertFalse(s.mayGuard("exo")) } // ordinary track updates
    }

    @Test fun `switching to another movie or the next episode starts fresh`() {
        val s = session()
        s.markGuarded("exo"); s.markGuarded("mpv")
        s.newItem(isLive = false)
        assertTrue(s.mayGuard("exo"))
        assertTrue(s.mayGuard("mpv"))
    }

    @Test fun `mpv is unaffected by an ExoPlayer start`() {
        val s = session()
        s.markGuarded("mpv")
        s.engineStarted("exo")
        assertFalse(s.mayGuard("mpv"))
    }

    @Test fun `with the guard flag off nothing runs, restart or not`() {
        val s = VodAudioSelectionSession(enabled = true, guardEnabled = false).apply { newItem(isLive = false) }
        s.engineStarted("exo")
        assertFalse(s.mayGuard("exo"))
        val none = VodAudioSelectionSession(enabled = false, guardEnabled = false).apply { newItem(isLive = false) }
        none.engineStarted("exo")
        assertFalse(none.mayGuard("exo"))
    }

    @Test fun `the restart reset is wired at every ExoPlayer start, for VOD only`() {
        val src = File("src/main/java/tv/own/owntv/player/OwnTVPlayer.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/tv/own/owntv/player/OwnTVPlayer.kt")
        val text = src.readText()
        val start = text.indexOf("private fun startExo(")
        val body = text.substring(start, start + 600)
        assertTrue(body.contains("""if (!isLiveContent) vodAudioSession.engineStarted("exo")"""))
    }

    // ===== Brand isolation =========================================================================

    @Test fun `GoatTV has the flags these fixes ride on, the other brands default off`() {
        // Brand-aware so the suite also passes on AllAccess / 5Star / Supreme, where both flags are off.
        val goat = tv.own.owntv.BuildConfig.FLAVOR_brand == "goat"
        assertEquals(goat, tv.own.owntv.BuildConfig.VOD_AUDIO_SELECTION_CARRY)
        assertEquals(goat, tv.own.owntv.BuildConfig.VOD_MAIN_AUDIO_GUARD)
        val gradle = (File("build.gradle.kts").takeIf { it.exists() } ?: File("app/build.gradle.kts")).readText()
        assertTrue(gradle.contains("""buildConfigField("boolean", "VOD_AUDIO_SELECTION_CARRY", "false")"""))
        assertTrue(gradle.contains("""buildConfigField("boolean", "VOD_MAIN_AUDIO_GUARD", "false")"""))
    }
}
