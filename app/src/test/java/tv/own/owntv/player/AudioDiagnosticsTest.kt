package tv.own.owntv.player

import android.content.Context
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4A audio diagnostics: these are report-only, so they must describe the output this player actually
 * opened, stay with the right player, and never put credentials into the exported ring.
 */
@UnstableApi
class AudioDiagnosticsTest {

    private val context: Context = mockk(relaxed = true)
    private val eventTime: AnalyticsListener.EventTime = mockk(relaxed = true)

    private fun trackConfig(encoding: Int) =
        AudioSink.AudioTrackConfig(encoding, 48_000, 0, false, false, 0)

    private fun audio(mime: String, ch: Int = 6, lang: String? = "eng", codecs: String? = null) =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setChannelCount(ch)
            .setSampleRate(48_000).setLanguage(lang).setId("http://user:secret@host/live/u/p/1.ts")
            .setLabel("Provider label").build()

    private fun audioGroup(vararg formats: Format, selected: Int = 0) = Tracks.Group(
        TrackGroup(*formats), false,
        IntArray(formats.size) { C.FORMAT_HANDLED },
        BooleanArray(formats.size) { it == selected },
    )

    /** A watchdog bound to a fresh player, so its ring lines can be found by a tag no other test uses
     *  (the ring is shared and bounded, so counting by position would break once it is full). */
    private fun bound(engine: String = "t"): Pair<AudioWatchdog, String> {
        val diag = OwnTVRenderersFactory(context, forceStereo = false, engine = engine).audioDiagnostics
        return AudioWatchdog(context).apply { bind(diag) } to "[${diag.playerTag}]"
    }
    private fun ringLines(tag: String): List<String> = LiveDiagnosticsLog.snapshot().lines().filter { it.contains(tag) }

    // --- PCM vs encoded passthrough ----------------------------------------------------------------

    @Test fun `pcm output reports decoded`() {
        assertEquals(false, AudioOutputReport.isEncodedPassthrough(C.ENCODING_PCM_16BIT))
        assertEquals(false, AudioOutputReport.isEncodedPassthrough(C.ENCODING_PCM_FLOAT))
        assertEquals("decoded", AudioOutputReport.pathLabel(false))
    }

    @Test fun `dolby and dts outputs report passthrough, including atmos and dts-hd`() {
        listOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_DTS_HD)
            .forEach { assertEquals("encoding $it", true, AudioOutputReport.isEncodedPassthrough(it)) }
        assertEquals("E_AC3_JOC", AudioOutputReport.encodingName(C.ENCODING_E_AC3_JOC))
        assertEquals("DTS_HD", AudioOutputReport.encodingName(C.ENCODING_DTS_HD))
    }

    @Test fun `missing or unknown output reports unknown, never decoded`() {
        assertNull(AudioOutputReport.isEncodedPassthrough(C.ENCODING_INVALID))
        assertNull(AudioOutputReport.isEncodedPassthrough(Format.NO_VALUE))
        assertEquals("unknown", AudioOutputReport.pathLabel(null))
        assertEquals("ENCODING_12345", AudioOutputReport.encodingName(12345))
    }

    @Test fun `watchdog takes the reported path from the opened AudioTrack`() {
        val w = AudioWatchdog(context)
        assertNull(w.outputPassthrough) // nothing opened yet

        // An E-AC-3 JOC (Atmos) bitstream: the legacy decoder-name / mime guess missed this one.
        w.onAudioInputFormatChanged(eventTime, audio(MimeTypes.AUDIO_E_AC3_JOC), null)
        w.onAudioTrackInitialized(eventTime, trackConfig(C.ENCODING_E_AC3_JOC))
        assertEquals(true, w.outputPassthrough)

        // Same player re-opens as PCM (e.g. Media3 fell back after an HDMI change).
        w.onAudioTrackInitialized(eventTime, trackConfig(C.ENCODING_PCM_16BIT))
        assertEquals(false, w.outputPassthrough)

        w.reset()
        assertNull(w.outputPassthrough)
        assertEquals(C.ENCODING_INVALID, w.outputEncoding)
    }

    @Test fun `legacy passthrough flag that gates playback is left untouched by the new reporting`() {
        val w = AudioWatchdog(context)
        w.onAudioDecoderInitialized(eventTime, "c2.android.aac.decoder", 0L, 0L)
        w.onAudioTrackInitialized(eventTime, trackConfig(C.ENCODING_AC3))
        assertTrue(w.outputPassthrough == true)
        assertFalse("selectAudio's re-prime still keys off the old flag", w.passthrough)
    }

    // --- Selected audio track ----------------------------------------------------------------------

    @Test fun `selected track is described by codec, channels and language only`() {
        // One group per language, as Media3 builds them; the second (Spanish AAC) is not selected.
        val tracks = Tracks(listOf(
            audioGroup(audio(MimeTypes.AUDIO_AC3, codecs = "ac-3")),
            audioGroup(audio(MimeTypes.AUDIO_AAC, ch = 2, lang = "spa"), selected = -1),
        ))
        val line = AudioOutputReport.describeSelectedAudio(tracks)!!
        assertTrue(line, line.startsWith("mime=audio/ac3 codecs=ac-3 ch=6 rate=48000 lang=en ")) // Media3 normalises eng -> en
        assertTrue(line, line.endsWith("tracks=2 supported=2"))
        assertFalse("no format id", line.contains("http"))
        assertFalse("no provider label", line.contains("Provider"))
    }

    @Test fun `no audio tracks and nothing selected are told apart`() {
        assertNull(AudioOutputReport.describeSelectedAudio(Tracks.EMPTY))
        val unselected = Tracks(listOf(audioGroup(audio(MimeTypes.AUDIO_AC3), selected = -1)))
        assertEquals("none tracks=1 supported=1", AudioOutputReport.describeSelectedAudio(unselected))
    }

    @Test fun `selected track is logged once per change, not on every tracks event`() {
        val (w, tag) = bound()
        val tracks = Tracks(listOf(audioGroup(audio(MimeTypes.AUDIO_E_AC3))))
        w.onTracksChanged(eventTime, tracks)
        w.onTracksChanged(eventTime, tracks)
        assertEquals(1, ringLines(tag).count { it.contains("AUDIO_TRACK_SELECTED") })
    }

    // --- Multiple player instances -----------------------------------------------------------------

    @Test fun `each player keeps its own tag and its own AudioTrack`() {
        val live = OwnTVRenderersFactory(context, forceStereo = false, engine = "live").audioDiagnostics
        val tile = OwnTVRenderersFactory(context, forceStereo = true, engine = "ms").audioDiagnostics
        assertNotEquals(live.playerTag, tile.playerTag)
        assertTrue(live.playerTag.startsWith("live#"))
        assertTrue(tile.playerTag.startsWith("ms#"))

        val liveTrack = mockk<AudioTrack>(relaxed = true)
        val tileTrack = mockk<AudioTrack>(relaxed = true)
        live.onTrackCreated(liveTrack)
        tile.onTrackCreated(tileTrack) // a Multiscreen tile opening later must not replace Live's track
        assertSame(liveTrack, live.lastTrack)
        assertSame(tileTrack, tile.lastTrack)
    }

    @Test fun `watchdog lines carry the bound player's tag`() {
        val (live, liveTag) = bound("live")
        val (tile, tileTag) = bound("ms")
        live.onAudioTrackInitialized(eventTime, trackConfig(C.ENCODING_AC3))
        tile.onAudioTrackInitialized(eventTime, trackConfig(C.ENCODING_PCM_16BIT))
        val liveLine = ringLines(liveTag).single { it.contains("AUDIO_TRACK_CONFIG") }
        val tileLine = ringLines(tileTag).single { it.contains("AUDIO_TRACK_CONFIG") }
        assertTrue(liveLine, liveLine.contains("encoding=AC3"))
        assertTrue(liveLine, liveLine.contains("path=passthrough sink=DYNAMIC_FILTERED"))
        assertTrue(tileLine, tileLine.contains("path=decoded"))
    }

    // --- Redaction ---------------------------------------------------------------------------------

    @Test fun `audio sink error reaches the ring with credentials redacted`() {
        val (w, tag) = bound()
        w.onAudioSinkError(eventTime, RuntimeException("AudioTrack init failed for http://bob:hunter2@panel.example/live/bob/hunter2/77.ts"))
        val line = ringLines(tag).single { it.contains("AUDIO_SINK_ERROR") }
        assertTrue(line, line.contains("type=RuntimeException"))
        assertFalse(line, line.contains("hunter2"))
        assertTrue(line, line.contains(tv.own.owntv.core.diagnostics.SensitiveText.MASK))
    }

    @Test fun `long sink error messages are capped`() {
        val (w, tag) = bound()
        w.onAudioSinkError(eventTime, IllegalStateException("x".repeat(5_000)))
        val line = ringLines(tag).single { it.contains("AUDIO_SINK_ERROR") }
        assertTrue("length ${line.length}", line.length < 400)
    }

    @Test fun `underruns produce one line per burst, not one per underrun`() {
        val (w, tag) = bound()
        repeat(10) { w.onAudioUnderrun(eventTime, 4096, 20L, 30L) }
        assertEquals(1, ringLines(tag).count { it.contains("AUDIO_UNDERRUN") })
    }

    // --- Capability changes ------------------------------------------------------------------------

    @Test fun `capability change is logged and still reaches the renderer`() {
        val inner = mockk<AudioSink>(relaxed = true)
        val forwarded = slot<AudioSink.Listener>()
        every { inner.setListener(capture(forwarded)) } returns Unit
        var logged = 0
        val renderer = mockk<AudioSink.Listener>(relaxed = true)

        PassthroughFilteringAudioSink(inner, onCapabilitiesChanged = { logged++ }).setListener(renderer)
        forwarded.captured.onAudioCapabilitiesChanged()

        assertEquals(1, logged)
        verify(exactly = 1) { renderer.onAudioCapabilitiesChanged() }
    }

    @Test fun `a throwing diagnostics hook never blocks the capability change`() {
        val inner = mockk<AudioSink>(relaxed = true)
        val forwarded = slot<AudioSink.Listener>()
        every { inner.setListener(capture(forwarded)) } returns Unit
        val renderer = mockk<AudioSink.Listener>(relaxed = true)

        PassthroughFilteringAudioSink(inner, onCapabilitiesChanged = { error("boom") }).setListener(renderer)
        forwarded.captured.onAudioCapabilitiesChanged()

        verify(exactly = 1) { renderer.onAudioCapabilitiesChanged() }
    }

    @Test fun `every other sink callback is forwarded unchanged`() {
        val inner = mockk<AudioSink>(relaxed = true)
        val forwarded = slot<AudioSink.Listener>()
        every { inner.setListener(capture(forwarded)) } returns Unit
        val renderer = mockk<AudioSink.Listener>(relaxed = true)
        PassthroughFilteringAudioSink(inner).setListener(renderer)
        val l = forwarded.captured
        val cfg = trackConfig(C.ENCODING_AC3)
        val err = RuntimeException("x")

        l.onPositionDiscontinuity(); l.onPositionAdvancing(5L); l.onUnderrun(1, 2L, 3L)
        l.onSkipSilenceEnabledChanged(true); l.onOffloadBufferEmptying(); l.onOffloadBufferFull()
        l.onAudioSinkError(err); l.onAudioTrackInitialized(cfg); l.onAudioTrackReleased(cfg)
        l.onSilenceSkipped(); l.onAudioSessionIdChanged(9)

        verify(exactly = 1) {
            renderer.onPositionDiscontinuity(); renderer.onPositionAdvancing(5L); renderer.onUnderrun(1, 2L, 3L)
            renderer.onSkipSilenceEnabledChanged(true); renderer.onOffloadBufferEmptying(); renderer.onOffloadBufferFull()
            renderer.onAudioSinkError(err); renderer.onAudioTrackInitialized(cfg); renderer.onAudioTrackReleased(cfg)
            renderer.onSilenceSkipped(); renderer.onAudioSessionIdChanged(9)
        }
    }

    // --- No change to output selection ------------------------------------------------------------

    @Test fun `diagnostics record the sink kind the mode already selected`() {
        assertEquals(AudioSinkKind.STEREO_PCM, OwnTVRenderersFactory(context, forceStereo = true).audioDiagnostics.sinkKind)
        assertEquals(AudioSinkKind.DYNAMIC_FILTERED, OwnTVRenderersFactory(context, forceStereo = false).audioDiagnostics.sinkKind)
    }

    @Test fun `the capability hook does not change what the filtering sink accepts`() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        val plain = PassthroughFilteringAudioSink(inner)
        val hooked = PassthroughFilteringAudioSink(inner, onCapabilitiesChanged = {})
        listOf(audio(MimeTypes.AUDIO_AC3), audio(MimeTypes.AUDIO_AAC, ch = 2), audio(MimeTypes.AUDIO_TRUEHD))
            .forEach { assertEquals(it.sampleMimeType, plain.supportsFormat(it), hooked.supportsFormat(it)) }
        assertFalse(hooked.supportsFormat(audio(MimeTypes.AUDIO_AAC, ch = 2)))
    }
}
