package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** VOD Phase 5B: carry the customer's audio pick across mpv ⇄ ExoPlayer, never into another item. */
@androidx.media3.common.util.UnstableApi
class VodAudioCarryTest {

    // The same MKV as each engine lists it. mpv: ids 1.., 3-letter languages, codec names, titles.
    // ExoPlayer: ordinals 0.., 2-letter languages, MIME subtypes, labels from the same MKV Name.
    private val mpv = listOf(
        AudioCandidate(0, engineId = 1, "eng", "eac3", 6, "English", selected = true),
        AudioCandidate(1, engineId = 2, "eng", "aac", 2, "Director's Commentary", selected = false),
        AudioCandidate(2, engineId = 3, "fre", "ac3", 6, "Français", selected = false),
    )
    private val exo = listOf(
        AudioCandidate(0, engineId = 0, "en", "eac3", 6, "English", selected = true),
        AudioCandidate(1, engineId = 1, "en", "mp4a-latm", 2, "Director's Commentary", selected = false),
        AudioCandidate(2, engineId = 2, "fr", "ac3", 6, "Français", selected = false),
    )

    private fun pick(engine: String, list: List<AudioCandidate>, index: Int) =
        AudioTrackMatcher.choiceOf(engine, list[index], list)

    // --- Handoffs, different ids ----------------------------------------------------------------------

    @Test fun `mpv to ExoPlayer finds the same track although the ids differ`() {
        val r = AudioTrackMatcher.match(pick("mpv", mpv, 2), exo)
        assertEquals(CarryOutcome.MATCHED, r.outcome)
        assertEquals("metadata", r.basis)
        assertEquals(2, r.target!!.engineId) // ExoPlayer ordinal, not mpv's aid 3
    }

    @Test fun `ExoPlayer to mpv maps back to mpv's aid`() {
        val r = AudioTrackMatcher.match(pick("exo", exo, 1), mpv)
        assertEquals(CarryOutcome.MATCHED, r.outcome)
        assertEquals(2, r.target!!.engineId)
    }

    @Test fun `commentary and main of the same language are told apart`() {
        assertEquals(1, AudioTrackMatcher.match(pick("mpv", mpv, 1), exo).target!!.index) // commentary
        assertEquals(0, AudioTrackMatcher.match(pick("mpv", mpv, 0), exo).target!!.index) // main
    }

    @Test fun `language and codec names are normalized across engines`() {
        assertEquals(AudioTrackMatcher.normalizeLanguage("en"), AudioTrackMatcher.normalizeLanguage("eng"))
        assertNull(AudioTrackMatcher.normalizeLanguage("und"))
        // MKV/mpv bibliographic codes meet Media3's two-letter codes.
        assertEquals(AudioTrackMatcher.normalizeLanguage("fr"), AudioTrackMatcher.normalizeLanguage("fre"))
        assertEquals(AudioTrackMatcher.normalizeLanguage("de"), AudioTrackMatcher.normalizeLanguage("ger"))
        assertEquals("aac", AudioTrackMatcher.codecFamily("mp4a-latm"))
        assertEquals("eac3", AudioTrackMatcher.codecFamily("eac3-joc"))
        assertEquals("ac3", AudioTrackMatcher.codecFamily("ac3"))
        assertEquals("dts", AudioTrackMatcher.codecFamily("vnd.dts.hd"))
        assertEquals("truehd", AudioTrackMatcher.codecFamily("true-hd"))
    }

    // --- Same-language / missing / ambiguous ----------------------------------------------------------

    @Test fun `identical untitled tracks match by position only when both lists have the same length`() {
        val a = listOf(AudioCandidate(0, 1, "eng", "aac", 2, null, true), AudioCandidate(1, 2, "eng", "aac", 2, null, false))
        val b = listOf(AudioCandidate(0, 0, "en", "mp4a-latm", 2, null, true), AudioCandidate(1, 1, "en", "mp4a-latm", 2, null, false))
        val r = AudioTrackMatcher.match(pick("mpv", a, 1), b)
        assertEquals(CarryOutcome.MATCHED, r.outcome)
        assertEquals("ordinal", r.basis)
        assertEquals(1, r.target!!.index)
        // A list of a different length means the position proves nothing: refuse.
        val b3 = b + AudioCandidate(2, 2, "en", "mp4a-latm", 2, null, false)
        assertEquals(CarryOutcome.AMBIGUOUS, AudioTrackMatcher.match(pick("mpv", a, 1), b3).outcome)
        assertNull(AudioTrackMatcher.match(pick("mpv", a, 1), b3).target)
    }

    @Test fun `a track that is not there is missing, and nothing is selected`() {
        val choice = pick("mpv", mpv, 2) // French
        val r = AudioTrackMatcher.match(choice, exo.filter { it.language != "fr" })
        assertEquals(CarryOutcome.MISSING, r.outcome)
        assertNull(r.target)
        assertEquals(CarryOutcome.MISSING, AudioTrackMatcher.match(choice, emptyList()).outcome)
    }

    @Test fun `missing metadata on one side is not held against a match`() {
        // ExoPlayer read no title/channels (an MP4 without names), mpv did.
        val bare = exo.map { it.copy(title = null, channels = null) }
        val r = AudioTrackMatcher.match(pick("mpv", mpv, 2), bare)
        assertEquals(CarryOutcome.MATCHED, r.outcome)
        assertEquals(2, r.target!!.index)
    }

    @Test fun `a pick with no usable metadata never matches a different-length list`() {
        val blank = listOf(AudioCandidate(0, 1, null, null, null, null, true), AudioCandidate(1, 2, null, null, null, null, false))
        val other = listOf(
            AudioCandidate(0, 0, null, null, null, null, true),
            AudioCandidate(1, 1, null, null, null, null, false),
            AudioCandidate(2, 2, null, null, null, null, false),
        )
        assertEquals(CarryOutcome.AMBIGUOUS, AudioTrackMatcher.match(pick("mpv", blank, 1), other).outcome)
    }

    @Test fun `codec naming disagreement falls back to language and title`() {
        val odd = exo.map { if (it.index == 1) it.copy(codec = "x-unknown", channels = 6) else it }
        val r = AudioTrackMatcher.match(pick("mpv", mpv, 1), odd)
        assertEquals(CarryOutcome.MATCHED, r.outcome)
        assertEquals(1, r.target!!.index)
    }

    // --- Session: isolation, priority, flags ---------------------------------------------------------

    @Test fun `a pick is kept for the item and carried to the other engine`() {
        val s = VodAudioSelectionSession(enabled = true)
        s.newItem(isLive = false)
        s.userSelected("mpv", mpv[1], mpv)
        val c = s.currentChoice()!!
        assertEquals("mpv", c.engine)
        assertEquals(1, AudioTrackMatcher.match(c, exo).target!!.index)
    }

    @Test fun `the latest user pick wins over an earlier one`() {
        val s = VodAudioSelectionSession(enabled = true)
        s.newItem(isLive = false)
        s.userSelected("mpv", mpv[1], mpv)
        s.userSelected("exo", exo[2], exo)
        assertEquals("fr", s.currentChoice()!!.language)
        assertEquals("exo", s.currentChoice()!!.engine)
    }

    @Test fun `next episode and another version start with no carried pick and a fresh mpv aid`() {
        val s = VodAudioSelectionSession(enabled = true)
        s.newItem(isLive = false)
        assertTrue("episode 1 resets aid", s.takeAidReset())
        assertFalse("only once per item", s.takeAidReset())
        s.userSelected("mpv", mpv[1], mpv)
        s.newItem(isLive = false) // next episode, or the 4K / Dolby version of the film
        assertNull(s.currentChoice())
        assertTrue(s.takeAidReset())
    }

    @Test fun `retries and handoffs of the same item keep the pick`() {
        val s = VodAudioSelectionSession(enabled = true)
        s.newItem(isLive = false)
        s.takeAidReset()
        s.userSelected("mpv", mpv[1], mpv)
        // No newItem() for a retry / in-place reload / engine handoff: the pick stands, aid is not reset.
        assertFalse(s.takeAidReset())
        assertEquals(1, s.currentChoice()!!.index)
    }

    @Test fun `live tv is untouched`() {
        val s = VodAudioSelectionSession(enabled = true)
        s.newItem(isLive = true)
        assertFalse(s.takeAidReset())
        assertNull(s.currentChoice())
    }

    @Test fun `with the brand flag off nothing changes`() {
        val s = VodAudioSelectionSession(enabled = false)
        s.newItem(isLive = false)
        assertFalse(s.takeAidReset())
        s.userSelected("mpv", mpv[1], mpv)
        assertNull(s.currentChoice())
        assertFalse(s.resetsExoOverrides)
    }

    // --- Diagnostics ----------------------------------------------------------------------------------

    private fun ring(): Pair<VodAudioDiagnostics, MutableList<String>> {
        val lines = mutableListOf<String>()
        return VodAudioDiagnostics { lines += it } to lines
    }

    @Test fun `a carried match is logged with its basis and as a user_carried selection`() {
        val (d, lines) = ring()
        d.startItem("5:MOVIE:1")
        d.onTracks("exo", listOf(vodAudioTrackInfoFor(0, true), vodAudioTrackInfoFor(1, false)), "vod#2")
        d.onCarry("exo", "mpv", CarryResult(CarryOutcome.MATCHED, exo[1], "metadata"), applied = true)
        assertTrue(lines.any { it == "VOD_AUDIO_CARRY engine=exo from=mpv content=5:MOVIE:1 outcome=matched basis=metadata index=1 applied=true" })
        assertTrue(lines.last(), lines.last().contains("index=1 reason=user_carried"))
    }

    @Test fun `ambiguous and missing say they fell back to the engine default`() {
        val (d, lines) = ring()
        d.startItem("5:MOVIE:2")
        d.onCarry("exo", "mpv", CarryResult(CarryOutcome.AMBIGUOUS, null, null), applied = false)
        d.onCarry("mpv", "exo", CarryResult(CarryOutcome.MISSING, null, null), applied = false)
        assertEquals("VOD_AUDIO_CARRY engine=exo from=mpv content=5:MOVIE:2 outcome=ambiguous fallback=engine_default", lines[0])
        assertEquals("VOD_AUDIO_CARRY engine=mpv from=exo content=5:MOVIE:2 outcome=missing fallback=engine_default", lines[1])
        assertEquals(2, lines.size) // no selection line: nothing was changed
    }

    @Test fun `carry lines never contain the provider title`() {
        val (d, lines) = ring()
        d.startItem("5:MOVIE:3")
        d.onCarry("exo", "mpv", CarryResult(CarryOutcome.MATCHED, exo[1], "metadata"), applied = false)
        lines.forEach { assertFalse(it, it.contains("Commentary")); assertFalse(it, it.contains("http")) }
    }

    // --- Brand isolation ------------------------------------------------------------------------------

    @Test fun `only the goat flavor enables the carry`() {
        assertTrue(tv.own.owntv.BuildConfig.VOD_AUDIO_SELECTION_CARRY)
        val text = (File("build.gradle.kts").takeIf { it.exists() } ?: File("app/build.gradle.kts")).readText()
        val on = Regex("""buildConfigField\("boolean", "VOD_AUDIO_SELECTION_CARRY", "true"\)""").findAll(text).toList()
        assertEquals(1, on.size)
        val goat = text.indexOf("create(\"goat\")", text.indexOf("productFlavors {"))
        val next = listOf("create(\"fivestar\")", "create(\"supreme\")", "create(\"allaccess\")")
            .map { text.indexOf(it, goat + 1) }.filter { it > 0 }.minOrNull() ?: text.length
        assertTrue(on.single().range.first in goat until next)
        assertTrue(text.contains("""buildConfigField("boolean", "VOD_AUDIO_SELECTION_CARRY", "false")"""))
    }

    private fun vodAudioTrackInfoFor(i: Int, sel: Boolean) =
        mpvAudioTrackInfo(i, null, "eng", "aac", 2, isDefault = i == 0, visualImpaired = false, selected = sel)
}
