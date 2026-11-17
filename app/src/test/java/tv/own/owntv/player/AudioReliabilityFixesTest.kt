package tv.own.owntv.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Final audio reliability fixes: remembered zero, Live TV gain, stuck duck, device-name privacy. */
@UnstableApi
class AudioReliabilityFixesTest {

    // --- Remembered volume zero ----------------------------------------------------------------------

    @Test fun `a remembered zero is never restored on a new tune`() {
        assertNull(VolumeLevels.restorable(0))
        assertNull(VolumeLevels.restorable(-5))
        assertNull(VolumeLevels.restorable(null))
    }

    @Test fun `valid remembered levels, including boost, are kept`() {
        assertEquals(40, VolumeLevels.restorable(40))
        assertEquals(100, VolumeLevels.restorable(100))
        assertEquals(135, VolumeLevels.restorable(135))
        assertEquals("capped at the boost ceiling", VolumeBoost.MAX_VOLUME, VolumeLevels.restorable(400))
    }

    // --- Live TV gain --------------------------------------------------------------------------------

    @Test fun `the player gain follows the selected level instead of a flat 100 percent`() {
        assertEquals(0.5f, VolumeLevels.playerGain(50, muted = false), 0f)
        assertEquals(0.25f, VolumeLevels.playerGain(25, muted = false), 0f) // an audio-focus duck
        assertEquals(1f, VolumeLevels.playerGain(100, muted = false), 0f)
    }

    @Test fun `boost above 100 stays on the LoudnessEnhancer, not the player gain`() {
        assertEquals(1f, VolumeLevels.playerGain(150, muted = false), 0f)
    }

    @Test fun `mute still silences whatever the level`() {
        assertEquals(0f, VolumeLevels.playerGain(80, muted = true), 0f)
        assertEquals(0f, VolumeLevels.playerGain(150, muted = true), 0f)
    }

    @Test fun `default volume sets the starting level, but a default of 0 does not silence live tv`() {
        assertEquals(70, VolumeLevels.liveTuneLevel(70))
        assertEquals(150, VolumeLevels.liveTuneLevel(150))
        assertEquals("Live TV always played a 0 default at full volume", 100, VolumeLevels.liveTuneLevel(0))
    }

    // --- Audio-focus ducking -------------------------------------------------------------------------

    @Test fun `transient loss ducks once, gain restores`() {
        val d = DuckState(25)
        assertEquals(25, d.duck(100))
        assertNull("a second loss while ducked does not duck again", d.duck(25))
        assertEquals(100, d.take()!!.levelToRestore(current = 25))
        assertFalse(d.isDucked)
    }

    @Test fun `pause during a duck keeps it pending until focus is granted again`() {
        val d = DuckState(25)
        d.duck(80)
        // Paused → focus abandoned: nothing taken, so the volume is not raised while another app may own focus.
        assertTrue(d.isDucked)
        // Resume → focus GRANTED → PlaybackSession undoes it (no AUDIOFOCUS_GAIN arrives for an abandoned duck).
        assertEquals(80, d.take()!!.levelToRestore(current = 20))
    }

    @Test fun `a denied focus request leaves the duck in force`() {
        val d = DuckState(25)
        d.duck(100)
        // PlaybackSession only calls take() on a grant; a denial changes nothing.
        assertTrue(d.isDucked)
    }

    @Test fun `a volume change while ducked is the customer's newer choice`() {
        val d = DuckState(25)
        d.duck(100)
        assertNull(d.take()!!.levelToRestore(current = 60))
    }

    @Test fun `nothing to restore when not ducked`() {
        assertNull(DuckState(25).take())
    }

    @Test fun `a duck never mutes an audible level`() {
        assertEquals(1, DuckState(25).duck(3))  // 3 * 25 % rounds to 0 — would deselect Live TV's audio track
        assertEquals(0, DuckState(25).duck(0))  // already silent stays silent
    }

    // --- Diagnostic privacy --------------------------------------------------------------------------

    private val eventTime: AnalyticsListener.EventTime = mockk(relaxed = true)

    private fun device(id: Int, type: Int, name: String): AudioDeviceInfo = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.type } returns type
        every { productName } returns name
    }

    @Test fun `device labels carry id and type, never the product name`() {
        assertEquals("id=7 type=8(bluetooth_a2dp)", AudioOutputReport.deviceLabel(7, 8))
        assertEquals("routedId=3 type=10(hdmi_arc)", AudioOutputReport.deviceLabel(3, 10, "routedId"))
        assertEquals("routedId=- type=-(none)", AudioOutputReport.deviceLabel(null, null, "routedId"))
        assertEquals("line_digital", AudioOutputReport.deviceTypeName(6))
        assertEquals("hdmi_earc", AudioOutputReport.deviceTypeName(29))
        assertEquals("other", AudioOutputReport.deviceTypeName(999))
    }

    @Test fun `available output devices reach the ring without their names`() {
        val am = mockk<AudioManager>(relaxed = true)
        every { am.getDevices(any()) } returns arrayOf(
            device(11, 8, "Sam's AirPods Pro"),
            device(12, 9, "Living Room Sony KD-65"),
        )
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.AUDIO_SERVICE) } returns am
        val diag = OwnTVRenderersFactory(ctx, forceStereo = false, engine = "priv").audioDiagnostics
        val w = AudioWatchdog(ctx).apply { bind(diag) }

        w.onAudioTrackInitialized(eventTime, AudioSink.AudioTrackConfig(C.ENCODING_AC3, 48_000, 0, false, false, 0))

        val lines = LiveDiagnosticsLog.snapshot().lines().filter { it.contains("[${diag.playerTag}]") }
        val devices = lines.filter { it.contains("AVAILABLE_DEVICE") }
        assertEquals(2, devices.size)
        assertTrue(devices[0], devices[0].endsWith("AVAILABLE_DEVICE id=11 type=8(bluetooth_a2dp)"))
        assertTrue(devices[1], devices[1].endsWith("AVAILABLE_DEVICE id=12 type=9(hdmi)"))
        lines.forEach { line ->
            assertFalse(line, line.contains("Sam"))
            assertFalse(line, line.contains("AirPods"))
            assertFalse(line, line.contains("Sony"))
            assertFalse(line, line.contains("product="))
        }
    }

    @Test fun `the routed-device line has no name either`() {
        val ctx = mockk<Context>(relaxed = true)
        val diag = OwnTVRenderersFactory(ctx, forceStereo = false, engine = "route").audioDiagnostics
        val track = mockk<AudioTrack>(relaxed = true)
        val dev = device(21, 10, "Bedroom Soundbar")
        every { track.routedDevice } returns dev
        every { track.audioSessionId } returns 5
        diag.onTrackCreated(track)
        val w = AudioWatchdog(ctx).apply { bind(diag) }

        w.onAudioPositionAdvancing(eventTime, 0L)

        val route = LiveDiagnosticsLog.snapshot().lines().single { it.contains("[${diag.playerTag}]") && it.contains("PCM_ROUTE_DIAG") }
        // `session=` is masked by the ring's own SensitiveText rule (it treats it like a session token).
        assertTrue(route, route.endsWith("routedId=21 type=10(hdmi_arc)"))
        assertFalse(route, route.contains("Soundbar"))
    }
}
