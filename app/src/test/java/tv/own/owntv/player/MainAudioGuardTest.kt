package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** VOD Phase 5C: never start a film on its commentary / audio-description track when a normal one exists. */
@androidx.media3.common.util.UnstableApi
class MainAudioGuardTest {

    /** mpv-shaped track (title, lang, codec, channels, default, visual-impaired). */
    private fun m(
        i: Int, title: String?, lang: String? = "eng", sel: Boolean = false, def: Boolean? = null,
        vi: Boolean? = false, codec: String = "ac3", ch: Int = 6,
    ) = mpvAudioTrackInfo(i, title, lang, codec, ch, def, vi, sel)

    /** ExoPlayer-shaped track from a Media3 Format. */
    private fun x(
        i: Int, label: String?, lang: String? = "en", sel: Boolean = false, def: Boolean = false,
        role: Int = 0, playable: Boolean = true, ch: Int = 6,
    ) = vodAudioTrackInfo(
        i,
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setLanguage(lang).setLabel(label).setChannelCount(ch)
            .setSelectionFlags(if (def) C.SELECTION_FLAG_DEFAULT else 0).setRoleFlags(role).build(),
        playable = playable, selected = sel,
    )

    private fun decide(vararg t: VodAudioTrackInfo, pref: String? = null) = MainAudioGuard.decide(t.toList(), pref)

    // --- When it switches ----------------------------------------------------------------------------

    @Test fun `commentary listed first moves to the main track`() {
        val d = decide(m(0, "Director's Commentary", sel = true), m(1, null))
        assertEquals(GuardDecision(0, 1, GuardReason.COMMENTARY_TO_MAIN), d)
    }

    @Test fun `commentary marked default still moves to the main track`() {
        val d = decide(m(0, "English", def = false), m(1, "Commentary", sel = true, def = true))
        assertEquals(0, d.to)
        assertEquals(GuardReason.COMMENTARY_TO_MAIN, d.reason)
    }

    @Test fun `a main track without a language tag is acceptable`() {
        val d = decide(m(0, "Commentary", sel = true), m(1, null, lang = "und"))
        assertEquals(1, d.to)
    }

    @Test fun `audio description moves to the main track`() {
        assertEquals(
            GuardDecision(1, 0, GuardReason.AUDIO_DESCRIPTION_TO_MAIN),
            decide(m(0, "English"), m(1, "English", sel = true, vi = true)),
        )
        assertEquals(GuardReason.AUDIO_DESCRIPTION_TO_MAIN, decide(m(0, "English (AD)", sel = true), m(1, "English")).reason)
    }

    @Test fun `same-language main and commentary`() {
        assertEquals(0, decide(m(0, "English 5.1"), m(1, "Commentary with the director", sel = true)).to)
    }

    @Test fun `ExoPlayer role flags are understood the same way`() {
        val d = decide(x(0, null, sel = true, role = C.ROLE_FLAG_COMMENTARY), x(1, null))
        assertEquals(GuardDecision(0, 1, GuardReason.COMMENTARY_TO_MAIN), d)
    }

    // --- Language ------------------------------------------------------------------------------------

    @Test fun `english commentary does not jump to a french dub with no preference`() {
        val d = decide(m(0, "Commentary", lang = "eng", sel = true), m(1, "Français", lang = "fre"))
        assertNull(d.to)
        assertEquals(GuardReason.NO_SUITABLE_ALTERNATIVE, d.reason)
    }

    @Test fun `the preferred language is honoured, across mpv and Media3 codes`() {
        val d = decide(m(0, "Commentary", lang = "eng", sel = true), m(1, "Français", lang = "fre"), pref = "fr")
        assertEquals(1, d.to)
        // An exact-language main beats an untagged one.
        val e = decide(m(0, null, lang = null), m(1, "Commentary", sel = true), m(2, "English"))
        assertEquals(2, e.to)
    }

    // --- Insufficient metadata: leave it alone -------------------------------------------------------

    @Test fun `untitled tracks are never touched`() {
        val d = decide(m(0, null, sel = true), m(1, null))
        assertNull(d.to)
        assertEquals(GuardReason.SELECTED_NOT_SECONDARY, d.reason)
    }

    @Test fun `missing metadata everywhere is never touched`() {
        val d = decide(m(0, null, lang = null, sel = true, def = null, vi = null), m(1, null, lang = null, def = null, vi = null))
        assertNull(d.to)
    }

    @Test fun `a normal-looking pick is never touched`() {
        assertEquals(GuardReason.SELECTED_NOT_SECONDARY, decide(m(0, "English 5.1", sel = true), m(1, "English 2.0")).reason)
    }

    @Test fun `nothing selected yet waits`() {
        assertEquals(GuardReason.NO_SELECTION, decide(m(0, "Commentary"), m(1, null)).reason)
    }

    @Test fun `only secondary alternatives means no switch`() {
        val d = decide(m(0, "Commentary", sel = true), m(1, "Audio Description"), m(2, "Cast commentary"))
        assertEquals(GuardReason.NO_SUITABLE_ALTERNATIVE, d.reason)
    }

    // --- Several mains: default, then main title, then file order; never channels ---------------------

    @Test fun `multiple main candidates prefer the default flag`() {
        assertEquals(2, decide(m(0, "Commentary", sel = true), m(1, null, def = false), m(2, null, def = true)).to)
    }

    @Test fun `then a title that says original or main`() {
        assertEquals(2, decide(m(0, "Commentary", sel = true), m(1, "English"), m(2, "Original")).to)
    }

    @Test fun `then file order, and never channel count`() {
        val d = decide(m(0, "Commentary", sel = true), m(1, null, ch = 2, codec = "aac"), m(2, null, ch = 8, codec = "truehd"))
        assertEquals(1, d.to)
    }

    @Test fun `an unplayable main is skipped, and only-unplayable means no switch`() {
        assertEquals(2, decide(x(0, "Commentary", sel = true), x(1, null, playable = false), x(2, null)).to)
        assertEquals(
            GuardReason.NO_SUITABLE_ALTERNATIVE,
            decide(x(0, "Commentary", sel = true), x(1, null, playable = false)).reason,
        )
    }

    // --- Priority, lifecycle, engines ----------------------------------------------------------------

    private fun session() = VodAudioSelectionSession(enabled = true, guardEnabled = true).apply { newItem(isLive = false) }
    private val cands = listOf(AudioCandidate(0, 1, "eng", "aac", 2, "Commentary", true), AudioCandidate(1, 2, "eng", "ac3", 6, null, false))

    @Test fun `a manual pick always wins - the guard stands down`() {
        val s = session()
        s.userSelected("mpv", cands[0], cands) // the customer chose the commentary on purpose
        assertFalse(s.mayGuard("mpv"))
        assertFalse(s.mayGuard("exo"))
    }

    @Test fun `a carried pick wins over the guard on the next engine`() {
        val s = session()
        s.userSelected("mpv", cands[0], cands)
        assertTrue(s.currentChoice() != null) // what startExo carries
        assertFalse(s.mayGuard("exo"))
    }

    @Test fun `once per engine per item - no repeated switching`() {
        val s = session()
        assertTrue(s.mayGuard("mpv"))
        s.markGuarded("mpv")
        assertFalse(s.mayGuard("mpv"))
        assertTrue("the other engine decides once too", s.mayGuard("exo"))
    }

    @Test fun `episode autoplay and alternate versions start a fresh guard`() {
        val s = session()
        s.markGuarded("mpv"); s.markGuarded("exo")
        s.newItem(isLive = false) // next episode, or the 4K / Dolby version
        assertTrue(s.mayGuard("mpv"))
        assertTrue(s.mayGuard("exo"))
    }

    @Test fun `no effect on live tv`() {
        val s = VodAudioSelectionSession(enabled = true, guardEnabled = true)
        s.newItem(isLive = true)
        assertFalse(s.mayGuard("mpv"))
    }

    @Test fun `with the brand flag off the guard never runs`() {
        val s = VodAudioSelectionSession(enabled = true, guardEnabled = false)
        s.newItem(isLive = false)
        assertFalse(s.mayGuard("mpv"))
        assertFalse(VodAudioSelectionSession(enabled = false).apply { newItem(false) }.mayGuard("exo"))
    }

    // --- Diagnostics ---------------------------------------------------------------------------------

    @Test fun `guard lines say what happened and why, without titles`() {
        val lines = mutableListOf<String>()
        val d = VodAudioDiagnostics { lines += it }
        d.startItem("8:MOVIE:5")
        d.onTracks("mpv", listOf(m(0, "Director's Commentary", sel = true), m(1, null)))
        d.onGuard("mpv", GuardDecision(0, 1, GuardReason.COMMENTARY_TO_MAIN))
        d.onGuard("exo", GuardDecision(0, null, GuardReason.NO_SUITABLE_ALTERNATIVE))
        d.onGuard("exo", GuardDecision(1, null, GuardReason.SELECTED_NOT_SECONDARY))
        assertTrue(lines.contains("VOD_AUDIO_GUARD engine=mpv content=8:MOVIE:5 action=switched from=0 to=1 reason=commentary_to_main"))
        assertTrue(lines.any { it.startsWith("VOD_AUDIO_SELECTED engine=mpv") && it.contains("index=1 reason=main_audio_guard") })
        assertTrue(lines.contains("VOD_AUDIO_GUARD engine=exo content=8:MOVIE:5 action=unchanged from=0 reason=no_suitable_alternative"))
        assertTrue(lines.contains("VOD_AUDIO_GUARD engine=exo content=8:MOVIE:5 action=unchanged from=1 reason=selected_not_secondary"))
        lines.forEach { assertFalse(it, it.contains("Director")) }
    }

    @Test fun `the engine echoing the guard's switch back is not logged again`() {
        val lines = mutableListOf<String>()
        val d = VodAudioDiagnostics { lines += it }
        d.startItem("8:MOVIE:6")
        d.onTracks("exo", listOf(x(0, "Commentary", sel = true), x(1, null)), "vod#1")
        d.onGuard("exo", GuardDecision(0, 1, GuardReason.COMMENTARY_TO_MAIN))
        val before = lines.size
        d.onTracks("exo", listOf(x(0, "Commentary"), x(1, null, sel = true)), "vod#1")
        assertEquals("only the new tracks line", before + 1, lines.size)
    }

    // --- Brand isolation ----------------------------------------------------------------------------

    @Test fun `only the goat flavor enables the guard`() {
        assertEquals("on for GoatTV only", tv.own.owntv.BuildConfig.FLAVOR_brand == "goat", tv.own.owntv.BuildConfig.VOD_MAIN_AUDIO_GUARD)
        val text = (File("build.gradle.kts").takeIf { it.exists() } ?: File("app/build.gradle.kts")).readText()
        val on = Regex("""buildConfigField\("boolean", "VOD_MAIN_AUDIO_GUARD", "true"\)""").findAll(text).toList()
        assertEquals(1, on.size)
        val goat = text.indexOf("create(\"goat\")", text.indexOf("productFlavors {"))
        val next = listOf("create(\"fivestar\")", "create(\"supreme\")", "create(\"allaccess\")")
            .map { text.indexOf(it, goat + 1) }.filter { it > 0 }.minOrNull() ?: text.length
        assertTrue(on.single().range.first in goat until next)
        assertTrue(text.contains("""buildConfigField("boolean", "VOD_MAIN_AUDIO_GUARD", "false")"""))
    }
}
