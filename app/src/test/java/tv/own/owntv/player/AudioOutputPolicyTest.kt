package tv.own.owntv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surround setting → audio sink contract. Before 4.6.1 the Auto/Surround sink read the HDMI
 * capabilities once, when the sink was built, and never updated them. These tests pin the live
 * (context-aware) sink, the stereo-only pin and the passthrough allowlist, including AAC.
 */
@UnstableApi
class AudioOutputPolicyTest {

    private fun audio(mime: String, channels: Int = 6, codecs: String? = null): Format =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs)
            .setChannelCount(channels).setSampleRate(48_000).build()

    private val ac3 = audio(MimeTypes.AUDIO_AC3)
    private val eac3 = audio(MimeTypes.AUDIO_E_AC3)
    private val eac3Joc = audio(MimeTypes.AUDIO_E_AC3_JOC)
    private val dts = audio(MimeTypes.AUDIO_DTS)
    private val dtsHd = audio(MimeTypes.AUDIO_DTS_HD)
    private val aacLc = audio(MimeTypes.AUDIO_AAC, channels = 2, codecs = "mp4a.40.2")
    private val heAac = audio(MimeTypes.AUDIO_AAC, channels = 2, codecs = "mp4a.40.5")
    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(48_000).build()

    // --- Mode → sink -------------------------------------------------------------------------------

    @Test fun `stereo only pins the static stereo PCM sink`() {
        assertFalse(AudioOutputPolicy.allowsMultichannel(SurroundMode.STEREO))
        assertEquals(AudioSinkKind.STEREO_PCM, AudioSinkKind.of(forceStereo = !AudioOutputPolicy.allowsMultichannel(SurroundMode.STEREO)))
        assertEquals(2, maxAudioChannelsFor(SurroundMode.STEREO))
    }

    @Test fun `auto uses the dynamic filtered sink`() {
        assertTrue(AudioOutputPolicy.allowsMultichannel(SurroundMode.AUTO))
        assertEquals(AudioSinkKind.DYNAMIC_FILTERED, AudioSinkKind.of(forceStereo = !AudioOutputPolicy.allowsMultichannel(SurroundMode.AUTO)))
    }

    @Test fun `surround uses the dynamic filtered sink`() {
        assertTrue(AudioOutputPolicy.allowsMultichannel(SurroundMode.SURROUND))
        assertEquals(AudioSinkKind.DYNAMIC_FILTERED, AudioSinkKind.of(forceStereo = !AudioOutputPolicy.allowsMultichannel(SurroundMode.SURROUND)))
    }

    @Test fun `stored mode and legacy boolean still map as before`() {
        assertEquals(SurroundMode.AUTO, SurroundMode.of(null, null))
        assertEquals(SurroundMode.SURROUND, SurroundMode.of(null, true))
        assertEquals(SurroundMode.STEREO, SurroundMode.of(null, false))
        assertEquals(SurroundMode.STEREO, SurroundMode.of("STEREO", true))
    }

    // --- Passthrough allowlist / AAC -----------------------------------------------------------------

    @Test fun `dolby and dts stay eligible for passthrough`() {
        listOf(ac3, eac3, eac3Joc, dts, dtsHd).forEach {
            assertTrue(it.sampleMimeType, PassthroughPolicy.mayReachSinkDirectly(it))
        }
    }

    @Test fun `aac is never eligible for passthrough`() {
        assertFalse(PassthroughPolicy.mayReachSinkDirectly(aacLc))
        assertFalse(PassthroughPolicy.mayReachSinkDirectly(heAac))
        assertFalse(PassthroughPolicy.mayReachSinkDirectly(audio(MimeTypes.AUDIO_AAC)))
    }

    @Test fun `formats outside the old allowlist are decoded in-app`() {
        listOf(MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_UHD_P2, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_OPUS)
            .forEach { assertFalse(it, PassthroughPolicy.mayReachSinkDirectly(audio(it))) }
        assertFalse(PassthroughPolicy.mayReachSinkDirectly(Format.Builder().build()))
    }

    @Test fun `pcm is always left to the sink`() {
        assertTrue(PassthroughPolicy.mayReachSinkDirectly(pcm))
    }

    @Test fun `filtering sink refuses aac even when the device claims it`() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { inner.supportsFormat(any()) } returns true
        val sink = PassthroughFilteringAudioSink(inner)

        assertFalse(sink.supportsFormat(aacLc))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(aacLc))
        assertSame(AudioOffloadSupport.DEFAULT_UNSUPPORTED, sink.getFormatOffloadSupport(aacLc))
        verify(exactly = 0) { inner.getFormatSupport(aacLc) }
    }

    @Test fun `filtering sink defers dolby and pcm to the live sink`() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.getFormatSupport(ac3) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { inner.getFormatSupport(pcm) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        val sink = PassthroughFilteringAudioSink(inner)

        assertTrue(sink.supportsFormat(ac3))
        assertTrue(sink.supportsFormat(pcm))
    }

    // --- HDMI capability change ----------------------------------------------------------------------

    @Test fun `capability change is not cached - the next query sees the new answer`() {
        val inner = mockk<AudioSink>(relaxed = true)
        // AVR asleep at build time: no AC-3. It wakes (HDMI re-plug), then the user switches to TV speakers.
        every { inner.getFormatSupport(ac3) } returnsMany listOf(
            AudioSink.SINK_FORMAT_UNSUPPORTED,
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            AudioSink.SINK_FORMAT_UNSUPPORTED,
        )
        val sink = PassthroughFilteringAudioSink(inner)

        assertFalse(sink.supportsFormat(ac3))
        assertTrue(sink.supportsFormat(ac3))
        assertFalse(sink.supportsFormat(ac3))
    }

    @Test fun `capability change notification reaches the renderer's listener`() {
        val inner = mockk<AudioSink>(relaxed = true)
        val forwarded = slot<AudioSink.Listener>()
        every { inner.setListener(capture(forwarded)) } returns Unit
        val rendererListener = mockk<AudioSink.Listener>(relaxed = true)

        PassthroughFilteringAudioSink(inner).setListener(rendererListener)
        forwarded.captured.onAudioCapabilitiesChanged()

        verify(exactly = 1) { rendererListener.onAudioCapabilitiesChanged() }
    }

    // --- Player initialization -----------------------------------------------------------------------

    /** Exposes the protected factory hook the ExoPlayer builder calls. */
    private class Probe(context: Context, forceStereo: Boolean) : OwnTVRenderersFactory(context, forceStereo) {
        fun sink(context: Context): AudioSink? = buildAudioSink(context, false, false)
    }

    private val context: Context = mockk(relaxed = true) {
        every { applicationContext } returns this
    }

    @Test fun `auto and surround build a filtered context-aware sink`() {
        val sink = Probe(context, forceStereo = false).sink(context)
        assertTrue("got ${sink?.javaClass}", sink is PassthroughFilteringAudioSink)
    }

    @Test fun `stereo only builds a plain default sink`() {
        val sink = Probe(context, forceStereo = true).sink(context)
        assertTrue("got ${sink?.javaClass}", sink is DefaultAudioSink)
    }
}
