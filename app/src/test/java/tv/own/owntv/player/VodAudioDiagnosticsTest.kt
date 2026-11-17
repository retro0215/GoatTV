package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VOD Phase 5A: report-only Movies & Series audio-track diagnostics. */
class VodAudioDiagnosticsTest {

    private val classify = AudioTrackClassifier::classify

    // --- Title classification ------------------------------------------------------------------------

    @Test fun `commentary titles`() {
        listOf(
            "Commentary", "Director's Commentary", "Commentary with cast and crew",
            "Commentaire du réalisateur", "Kommentar", "Regiekommentar", "Commento del regista",
            "Comentario del director", "Comentário", "Komentarz reżysera", "Audiocommentaar",
        ).forEach { assertEquals(it, AudioTitleClass.COMMENTARY, classify(it, null, null)) }
    }

    @Test fun `audio description titles`() {
        listOf(
            "Audio Description", "English (AD)", "ENG AD 2.0", "Descriptive Video Service", "Described Video",
            "Audiodeskription", "Audiodescripción", "Audiodescrição", "Visually Impaired", "Audiodescrizione",
        ).forEach { assertEquals(it, AudioTitleClass.AUDIO_DESCRIPTION, classify(it, null, null)) }
    }

    @Test fun `main, other and unknown`() {
        assertEquals(AudioTitleClass.MAIN, classify("Original", null, null))
        assertEquals(AudioTitleClass.MAIN, classify("Main Audio", null, null))
        assertEquals(AudioTitleClass.MAIN, classify("Originalton", null, null))
        assertEquals(AudioTitleClass.OTHER, classify("English 5.1", null, null))
        assertEquals(AudioTitleClass.OTHER, classify("DTS-HD MA 7.1", null, null))
        assertEquals("no 'main' inside a word", AudioTitleClass.OTHER, classify("Remain in Light", null, null))
        assertEquals("no 'ad' inside a word", AudioTitleClass.OTHER, classify("Stereo Downmix (Broadcast)", null, null))
        assertEquals(AudioTitleClass.UNKNOWN, classify(null, null, null))
        assertEquals(AudioTitleClass.UNKNOWN, classify("   ", null, null))
    }

    @Test fun `structured flags win over the title`() {
        assertEquals(AudioTitleClass.AUDIO_DESCRIPTION, classify("English", true, null))
        assertEquals(AudioTitleClass.COMMENTARY, classify(null, null, C.ROLE_FLAG_COMMENTARY))
        assertEquals(AudioTitleClass.AUDIO_DESCRIPTION, classify(null, null, C.ROLE_FLAG_DESCRIBES_VIDEO))
        assertEquals(AudioTitleClass.MAIN, classify(null, null, C.ROLE_FLAG_MAIN))
        assertEquals(AudioTitleClass.UNKNOWN, classify(null, false, 0))
    }

    @Test fun `role flag constants are Media3's`() {
        assertEquals(C.ROLE_FLAG_COMMENTARY, AudioTrackClassifier.ROLE_FLAG_COMMENTARY)
        assertEquals(C.ROLE_FLAG_DESCRIBES_VIDEO, AudioTrackClassifier.ROLE_FLAG_DESCRIBES_VIDEO)
    }

    // --- Engine metadata mapping ---------------------------------------------------------------------

    @Test fun `exoplayer format maps language, codec, channels, default and role flags`() {
        val f = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6)
            .setLanguage("en").setLabel("Director's Commentary").setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(0).build()
        val t = vodAudioTrackInfo(2, f, playable = true, selected = true)
        assertEquals(2, t.index)
        assertEquals("en", t.language)
        assertEquals("eac3", t.codec)
        assertEquals(6, t.channels)
        assertEquals(true, t.isDefault)
        assertNull("ExoPlayer has no visual-impaired flag", t.visualImpaired)
        assertEquals(0, t.roleFlags)
        assertEquals(AudioTitleClass.COMMENTARY, t.titleClass)
        assertEquals(true, t.playable)
    }

    @Test fun `exoplayer format with missing metadata stays unknown`() {
        val t = vodAudioTrackInfo(0, Format.Builder().build(), playable = null, selected = false)
        assertNull(t.language); assertNull(t.codec); assertNull(t.channels)
        assertEquals(false, t.isDefault)
        assertEquals(AudioTitleClass.UNKNOWN, t.titleClass)
        assertEquals("[0 lang=- codec=- ch=- default=n vi=- role=0 class=unknown playable=-]", t.describe())
    }

    @Test fun `mpv track maps its flags and has no role flags or playability`() {
        val t = mpvAudioTrackInfo(1, "Audio Description", "eng", "ac3", 2, isDefault = false, visualImpaired = true, selected = false)
        assertEquals(AudioTitleClass.AUDIO_DESCRIPTION, t.titleClass)
        assertNull(t.roleFlags); assertNull(t.playable)
        assertEquals(true, t.visualImpaired)
        val blank = mpvAudioTrackInfo(0, null, "", "", 0, null, null, selected = true)
        assertNull(blank.language); assertNull(blank.codec); assertNull(blank.channels)
        assertEquals("[0 lang=- codec=- ch=- default=- vi=- role=- class=unknown playable=- sel]", blank.describe())
    }

    // --- Logging lifecycle ---------------------------------------------------------------------------

    private class Ring { val lines = ArrayList<String>() }
    private fun diag(): Pair<VodAudioDiagnostics, Ring> = Ring().let { r -> VodAudioDiagnostics { r.lines += it } to r }

    private fun mpv(i: Int, sel: Boolean, title: String? = null, lang: String = "eng") =
        mpvAudioTrackInfo(i, title, lang, "ac3", 6, isDefault = i == 0, visualImpaired = false, selected = sel)

    @Test fun `first pick is engine_default and the tracks line names engine and content`() {
        val (d, r) = diag()
        d.startItem("12:MOVIE:4411")
        d.onTracks("mpv", listOf(mpv(0, false, "Commentary"), mpv(1, true)))
        assertTrue(r.lines[0], r.lines[0].startsWith("VOD_AUDIO_TRACKS engine=mpv content=12:MOVIE:4411 count=2 "))
        assertTrue(r.lines[0], r.lines[0].contains("class=commentary"))
        assertEquals("VOD_AUDIO_SELECTED engine=mpv content=12:MOVIE:4411 index=1 reason=engine_default lang=eng codec=ac3 ch=6 class=unknown", r.lines[1])
    }

    @Test fun `manual selection is logged as user, and the engine echo is suppressed`() {
        val (d, r) = diag()
        d.startItem("12:EPISODE:9")
        d.onTracks("exo", listOf(vodAudioTrackInfo(0, fmt("en"), true, true), vodAudioTrackInfo(1, fmt("fr"), true, false)), "vod#3")
        d.onUserSelected("exo", 1)
        // ExoPlayer reports the new selection back on its next onTracksChanged.
        d.onTracks("exo", listOf(vodAudioTrackInfo(0, fmt("en"), true, false), vodAudioTrackInfo(1, fmt("fr"), true, true)), "vod#3")
        val selected = r.lines.filter { it.startsWith("VOD_AUDIO_SELECTED") }
        assertEquals(2, selected.size)
        assertTrue(selected[1], selected[1].contains("index=1 reason=user lang=fr"))
    }

    @Test fun `mpv to exoplayer on the same item is an engine_handoff, and back again too`() {
        val (d, r) = diag()
        d.startItem("3:MOVIE:77")
        d.onTracks("mpv", listOf(mpv(0, true), mpv(1, false)))
        d.onTracks("exo", listOf(vodAudioTrackInfo(0, fmt("en"), true, false), vodAudioTrackInfo(1, fmt("en"), true, true)), "vod#1")
        d.onTracks("mpv", listOf(mpv(0, true), mpv(1, false)))
        val reasons = r.lines.filter { it.startsWith("VOD_AUDIO_SELECTED") }.map { it.substringAfter("reason=").substringBefore(' ') }
        assertEquals(listOf("engine_default", "engine_handoff", "engine_handoff"), reasons)
    }

    @Test fun `an unexplained change on the same engine is unknown, never guessed`() {
        val (d, r) = diag()
        d.startItem("3:MOVIE:78")
        d.onTracks("mpv", listOf(mpv(0, true), mpv(1, false)))
        d.onTracks("mpv", listOf(mpv(0, false), mpv(1, true)))
        assertTrue(r.lines.last(), r.lines.last().contains("index=1 reason=unknown"))
    }

    @Test fun `duplicate reports are suppressed`() {
        val (d, r) = diag()
        d.startItem("3:MOVIE:79")
        val list = listOf(mpv(0, true), mpv(1, false))
        repeat(5) { d.onTracks("mpv", list) } // FILE_LOADED re-fires on in-place reloads and retries
        assertEquals(2, r.lines.size)
    }

    @Test fun `a new item (next episode or another version) starts fresh`() {
        val (d, r) = diag()
        d.startItem("3:EPISODE:1")
        d.onTracks("mpv", listOf(mpv(0, true)))
        d.startItem("3:EPISODE:2")
        d.onTracks("mpv", listOf(mpv(0, true)))
        assertEquals(4, r.lines.size)
        assertTrue(r.lines[3], r.lines[3].contains("content=3:EPISODE:2 index=0 reason=engine_default"))
    }

    @Test fun `live tv on mpv reports nothing`() {
        val (d, r) = diag()
        d.stopItem()
        d.onTracks("mpv", listOf(mpv(0, true)))
        d.onUserSelected("mpv", 0)
        assertTrue(r.lines.isEmpty())
    }

    @Test fun `long track lists are capped on one line`() {
        val (d, r) = diag()
        d.startItem("3:MOVIE:80")
        d.onTracks("mpv", (0 until 30).map { mpv(it, it == 0) })
        assertTrue(r.lines[0], r.lines[0].endsWith("+14 more"))
    }

    // --- Privacy -------------------------------------------------------------------------------------

    @Test fun `raw titles, urls and credentials never reach the line`() {
        val (d, r) = diag()
        d.startItem("3:MOVIE:81")
        val title = "Commentary by user:hunter2 http://panel.example/movie/u/p/1.mkv"
        d.onTracks("mpv", listOf(mpvAudioTrackInfo(0, title, "eng", "aac", 2, true, false, true)))
        val f = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLabel(title)
            .setId("http://user:secret@panel.example/live/1.ts").build()
        d.onTracks("exo", listOf(vodAudioTrackInfo(0, f, true, true)), "vod#9")
        r.lines.forEach { line ->
            assertFalse(line, line.contains("hunter2"))
            assertFalse(line, line.contains("http"))
            assertFalse(line, line.contains("secret"))
            assertFalse(line, line.contains("Commentary by"))
        }
        assertTrue(r.lines.any { it.contains("class=commentary") })
    }

    @Test fun `a url-shaped content key is withheld`() {
        assertEquals("withheld", VodAudioDiagnostics.safeKey("http://panel.example/movie/u/p/1.mkv"))
        assertEquals("none", VodAudioDiagnostics.safeKey(null))
        assertEquals("12:MOVIE:4411", VodAudioDiagnostics.safeKey("12:MOVIE:4411"))
    }

    @Test fun `default sink goes through the redacting diagnostics ring`() {
        val d = VodAudioDiagnostics()
        val tag = "77:MOVIE:redact-${System.nanoTime()}"
        d.startItem(tag)
        // A field an engine fills in (here the codec) carrying a credential URL is masked by the ring.
        d.onTracks("mpv", listOf(mpvAudioTrackInfo(0, "x", "eng", "http://bob:hunter2@panel.example/movie/bob/hunter2/1.mkv", 2, true, false, true)))
        val line = LiveDiagnosticsLog.snapshot().lines().single { it.contains(tag) && it.contains("VOD_AUDIO_TRACKS") }
        assertFalse(line, line.contains("hunter2"))
        assertTrue(line, line.contains(tv.own.owntv.core.diagnostics.SensitiveText.MASK))
    }

    private fun fmt(lang: String) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(lang).setChannelCount(2).build()
}
