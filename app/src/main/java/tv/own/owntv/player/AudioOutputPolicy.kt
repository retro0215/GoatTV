package tv.own.owntv.player

import android.content.Context
import android.media.AudioTrack
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import tv.own.owntv.player.LiveDiagnosticsLog

/**
 * How the user wants multichannel audio handled. Persisted as a string (see
 * `SettingsRepository.surroundMode`); the legacy `surround_sound` boolean maps onto it.
 */
enum class SurroundMode {
    AUTO, STEREO, SURROUND;

    companion object {
        fun of(stored: String?, legacyBoolean: Boolean?): SurroundMode {
            stored?.let { s -> entries.firstOrNull { it.name == s }?.let { return it } }
            return when (legacyBoolean) {
                null -> AUTO
                true -> SURROUND
                false -> STEREO
            }
        }
    }
}

object AudioOutputPolicy {
    private const val DEFAULT_NO_AUDIO_GRACE_MS = 6_000L
    private const val SHIELD_NO_AUDIO_GRACE_MS = 4_000L

    const val UNDERRUN_WINDOW_MS = 10_000L

    /** Underruns within [UNDERRUN_WINDOW_MS] worth one diagnostic line (the 4.1.7 threshold). Report-only. */
    const val UNDERRUN_REPORT_COUNT = 4

    /**
     * True when this mode permits anything other than plain stereo PCM.
     */
    fun allowsMultichannel(mode: SurroundMode): Boolean = mode != SurroundMode.STEREO

    fun getNoAudioGraceMs(): Long {
        return if (android.os.Build.MODEL.contains("SHIELD", ignoreCase = true)) {
            SHIELD_NO_AUDIO_GRACE_MS
        } else {
            DEFAULT_NO_AUDIO_GRACE_MS
        }
    }

    /** No-op in stabilized build; auto-latching removed. */
    fun clearLatch() {}
}

/**
 * Which audio sink an engine gets. Stereo only is a fixed answer; Auto and Surround must follow the
 * HDMI/ARC output as it is *now*, so they get Media3's context-aware sink.
 */
enum class AudioSinkKind {
    /** Plain 16-bit stereo PCM, never anything encoded. Static on purpose: it doesn't depend on HDMI. */
    STEREO_PCM,
    /** Media3's live capabilities (HDMI plug, Global surround setting, routed device), narrowed to [PassthroughPolicy]. */
    DYNAMIC_FILTERED;

    companion object {
        fun of(forceStereo: Boolean): AudioSinkKind = if (forceStereo) STEREO_PCM else DYNAMIC_FILTERED
    }
}

/**
 * The compressed formats Auto/Surround may bitstream over HDMI. Anything else is decoded in-app.
 *
 * This keeps the encoding allowlist that the 4.2.15 sink applied to its startup capability snapshot.
 * AAC is never on it (Media3 refuses AAC passthrough too, but this doesn't depend on that). AC-4,
 * TrueHD, DTS:X P2 and MPEG-H are also left off, so a TV that starts reporting them doesn't put new
 * formats on the bitstream path.
 */
object PassthroughPolicy {
    val ALLOWED_ENCODINGS: Set<Int> = setOf(
        C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC,
        C.ENCODING_DTS, C.ENCODING_DTS_HD,
    )

    /**
     * False when [format] must never reach the sink undecoded. PCM (`audio/raw`) is not passthrough,
     * so it is always left to the sink.
     */
    @UnstableApi
    fun mayReachSinkDirectly(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW) return true
        return MimeTypes.getEncoding(mime, format.codecs) in ALLOWED_ENCODINGS
    }
}

/**
 * Gives the sink's own support answer only for formats [PassthroughPolicy] allows to go undecoded.
 * For any other compressed format it says "unsupported", and `MediaCodecAudioRenderer` decodes that
 * format to PCM instead of bitstreaming it.
 *
 * Nothing is cached here. Every query reaches the wrapped sink, so an HDMI capability change still
 * reaches the renderer through the forwarded listener (`onAudioCapabilitiesChanged`).
 */
@UnstableApi
class PassthroughFilteringAudioSink(
    sink: AudioSink,
    /** Diagnostics only: called when Media3 reports a capability change (HDMI plug, routed device, …). */
    private val onCapabilitiesChanged: () -> Unit = {},
) : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {

    /** Every callback is forwarded unchanged; only the capability change is also observed. Spelled out
     *  (not `by`) so a Java default method can never silently stop reaching the renderer. */
    override fun setListener(listener: AudioSink.Listener) {
        super.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = listener.onPositionDiscontinuity()
            override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) = listener.onPositionAdvancing(playoutStartSystemTimeMs)
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
                listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
            override fun onOffloadBufferEmptying() = listener.onOffloadBufferEmptying()
            override fun onOffloadBufferFull() = listener.onOffloadBufferFull()
            override fun onAudioSinkError(audioSinkError: Exception) = listener.onAudioSinkError(audioSinkError)
            override fun onAudioCapabilitiesChanged() {
                runCatching(onCapabilitiesChanged)
                listener.onAudioCapabilitiesChanged()
            }
            override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) = listener.onAudioTrackInitialized(audioTrackConfig)
            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) = listener.onAudioTrackReleased(audioTrackConfig)
            override fun onSilenceSkipped() = listener.onSilenceSkipped()
            override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
        })
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (PassthroughPolicy.mayReachSinkDirectly(format)) super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): androidx.media3.exoplayer.audio.AudioOffloadSupport =
        if (PassthroughPolicy.mayReachSinkDirectly(format)) super.getFormatOffloadSupport(format)
        else androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED
}

/** How an AudioTrack output encoding should be reported. Diagnostics only — never drives playback. */
@UnstableApi
object AudioOutputReport {
    /** True = an encoded bitstream (passthrough), false = PCM decoded in-app, null = no output yet / unknown. */
    fun isEncodedPassthrough(encoding: Int): Boolean? = when {
        encoding == C.ENCODING_INVALID || encoding == Format.NO_VALUE -> null
        androidx.media3.common.util.Util.isEncodingLinearPcm(encoding) -> false
        else -> true
    }

    fun pathLabel(passthrough: Boolean?): String = when (passthrough) {
        true -> "passthrough"
        false -> "decoded"
        null -> "unknown"
    }

    /**
     * An output device for the exported ring: its id and type only. Never the product name — that is the
     * TV's, soundbar's or receiver's name, and for Bluetooth often a name the customer chose ("Sam's
     * AirPods"). The type says what support needs: HDMI vs ARC/eARC vs Bluetooth vs the box's speaker.
     */
    fun deviceLabel(id: Int?, type: Int?, idKey: String = "id"): String =
        "$idKey=${id ?: "-"} type=${type ?: "-"}(${deviceTypeName(type)})"

    /** `AudioDeviceInfo.TYPE_*` values (stable platform constants) for the device kinds a TV box routes to. */
    fun deviceTypeName(type: Int?): String = when (type) {
        null -> "none"
        2 -> "builtin_speaker"
        3 -> "wired_headset"
        4 -> "wired_headphones"
        5 -> "line_analog"
        6 -> "line_digital" // S/PDIF / optical
        7 -> "bluetooth_sco"
        8 -> "bluetooth_a2dp"
        9 -> "hdmi"
        10 -> "hdmi_arc"
        11 -> "usb_device"
        12 -> "usb_accessory"
        13 -> "dock"
        18 -> "telephony"
        22 -> "usb_headset"
        23 -> "hearing_aid"
        24 -> "builtin_speaker_safe"
        26 -> "ble_headset"
        27 -> "ble_speaker"
        29 -> "hdmi_earc"
        30 -> "ble_broadcast"
        else -> "other"
    }

    fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_16BIT -> "PCM_16BIT"
        C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        C.ENCODING_AC3 -> "AC3"
        C.ENCODING_E_AC3 -> "E_AC3"
        C.ENCODING_E_AC3_JOC -> "E_AC3_JOC"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS_HD"
        else -> "ENCODING_$encoding"
    }

    /** One line naming the selected audio track (no URL, id or label), or null when there are no audio tracks. */
    fun describeSelectedAudio(tracks: androidx.media3.common.Tracks): String? {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return null
        val total = groups.sumOf { it.length }
        val supported = groups.sumOf { g -> (0 until g.length).count { g.isTrackSupported(it) } }
        val selected = groups.firstNotNullOfOrNull { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) }
        } ?: return "none tracks=$total supported=$supported"
        return "mime=${selected.sampleMimeType} codecs=${selected.codecs} ch=${selected.channelCount} " +
            "rate=${selected.sampleRate} lang=${selected.language} tracks=$total supported=$supported"
    }
}

/**
 * The audio diagnostics of ONE player. Each [OwnTVRenderersFactory] owns one, so lines from a Multiscreen
 * tile can no longer be mistaken for the Live player's, and the route check reads this player's own
 * AudioTrack instead of whichever one was created last anywhere in the app.
 */
class AudioOutputDiagnostics(val playerTag: String, val sinkKind: AudioSinkKind) {
    @Volatile private var trackRef: java.lang.ref.WeakReference<AudioTrack>? = null

    /** The most recent AudioTrack this player created. Weak, so diagnostics never keep a released track alive. */
    val lastTrack: AudioTrack? get() = trackRef?.get()

    fun onTrackCreated(track: AudioTrack) { trackRef = java.lang.ref.WeakReference(track) }

    /** Into the exported ring (redacted there by [tv.own.owntv.core.diagnostics.SensitiveText]). */
    fun event(message: String) = LiveDiagnosticsLog.event("EXO_AUDIO_DIAG [$playerTag] $message")

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicInteger()
        fun nextTag(engine: String): String = "$engine#${counter.incrementAndGet()}"
    }
}

/**
 * A [DefaultRenderersFactory] that can be pinned to plain stereo PCM.
 */
@UnstableApi
open class OwnTVRenderersFactory( // open only so the unit test can reach buildAudioSink
    private val context: Context,
    private val forceStereo: Boolean,
    /** Diagnostics label for the engine building this player ("live", "vod", "ms", …). */
    engine: String = "exo",
) : DefaultRenderersFactory(context) {

    /** This player's diagnostics identity — see [AudioOutputDiagnostics]. */
    val audioDiagnostics = AudioOutputDiagnostics(AudioOutputDiagnostics.nextTag(engine), AudioSinkKind.of(forceStereo))

    @Suppress("DEPRECATION") // setAudioTrackProvider (diagnostics) and the context-less Builder (stereo)
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val kind = AudioSinkKind.of(forceStereo)
        audioDiagnostics.event("AUDIO_SINK kind=$kind")
        return runCatching<AudioSink?> {
            when (kind) {
                // The context-less builder is the only one that honours fixed capabilities. Fixed is
                // right here: stereo PCM is what the user asked for whatever the HDMI output reports.
                AudioSinkKind.STEREO_PCM -> DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setAudioTrackProvider(DiagnosticAudioTrackProvider(audioDiagnostics))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build()
                // With a Context, Media3 reads capabilities lazily on the playback thread and keeps
                // them current. It listens for HDMI plug, the Global surround setting and device
                // changes, follows the routed device, and falls back to PCM after a passthrough write
                // error. The 4.2.15 sink read them once at build time instead, and the Live engine
                // keeps one player across tunes, so a snapshot from before an AVR/soundbar woke up
                // (or from another route) stayed in force.
                AudioSinkKind.DYNAMIC_FILTERED -> PassthroughFilteringAudioSink(
                    DefaultAudioSink.Builder(context)
                        .setAudioTrackProvider(DiagnosticAudioTrackProvider(audioDiagnostics))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .build(),
                    onCapabilitiesChanged = ::logCapabilitiesChanged,
                )
            }
        }.getOrElse {
            android.util.Log.w("AudioOutputPolicy", "sink build failed, falling back", it)
            audioDiagnostics.event("AUDIO_SINK build failed (${it.javaClass.simpleName}); using Media3 default")
            val fallback = super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
            if (kind == AudioSinkKind.DYNAMIC_FILTERED && fallback != null) {
                PassthroughFilteringAudioSink(fallback, onCapabilitiesChanged = ::logCapabilitiesChanged)
            } else fallback
        }
    }

    /** Media3 saw the output change (HDMI plug, the Global surround setting, a routed-device change). */
    private fun logCapabilitiesChanged() {
        audioDiagnostics.event("AUDIO_CAPABILITIES_CHANGED (renderer will re-check formats)")
    }

    private class DiagnosticAudioTrackProvider(
        private val diagnostics: AudioOutputDiagnostics,
    ) : DefaultAudioSink.AudioTrackProvider {
        @Suppress("DEPRECATION")
        override fun getAudioTrack(
            config: AudioSink.AudioTrackConfig,
            attributes: androidx.media3.common.AudioAttributes,
            audioSessionId: Int,
            context: Context?
        ): AudioTrack {
            val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, audioSessionId, context)
            
            runCatching {
                val dev = track.routedDevice
                val msg = "AUDIO_TRACK_INIT encoding=${config.encoding} rate=${config.sampleRate} " +
                    "ch=${config.channelConfig} session=$audioSessionId state=${track.state} " +
                    "playState=${track.playState} ${AudioOutputReport.deviceLabel(dev?.id, dev?.type, "routedId")} " +
                    "usage=${attributes.usage} content=${attributes.contentType}"
                android.util.Log.i("EXO_AUDIO_DIAG", msg)
                diagnostics.event(msg)
            }
            diagnostics.onTrackCreated(track)
            return track
        }
    }
}

/**
 * Watches an ExoPlayer's audio output and reports failures to the log.
 */
@UnstableApi
class AudioWatchdog(private val appContext: Context) : AnalyticsListener {

    @Volatile private var armed = false
    @Volatile private var advancing = false
    /** Accumulated *playing* milliseconds since the audio format was accepted. */
    @Volatile private var playingSinceArmMs = 0L
    @Volatile private var lastTickMs = 0L

    private val underrunTimes = ArrayDeque<Long>()

    @Volatile var audioFormat: Format? = null
        private set
    /**
     * Legacy guess (decoder name / input mime). Kept exactly as it was because it still gates the
     * passthrough re-prime seek in `selectAudio` — changing it would change playback. For *reporting*
     * use [outputPassthrough], which comes from the AudioTrack the sink actually opened.
     */
    @Volatile var passthrough = false
        private set
    @Volatile private var decoderInitialized = false

    /** The encoding of the AudioTrack this player's sink last opened, or [C.ENCODING_INVALID] before one. */
    @Volatile var outputEncoding: Int = C.ENCODING_INVALID
        private set

    /** Diagnostics only: true = encoded bitstream, false = PCM decoded in-app, null = no output yet. */
    val outputPassthrough: Boolean? get() = AudioOutputReport.isEncodedPassthrough(outputEncoding)

    /** The player this watchdog is attached to, for tagged lines and the per-player route check. */
    @Volatile private var diagnostics: AudioOutputDiagnostics? = null
    @Volatile private var lastSelectedAudio: String? = null

    /** Attach to the player built from [diag]'s factory. Call on every (re)build. */
    fun bind(diag: AudioOutputDiagnostics?) { diagnostics = diag }

    /** Exported-ring line, tagged with this watchdog's player when bound. */
    private fun ring(message: String) {
        diagnostics?.event(message) ?: LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $message")
    }

    fun reset() {
        armed = false; advancing = false
        playingSinceArmMs = 0L; lastTickMs = 0L
        synchronized(underrunTimes) { underrunTimes.clear() }
        audioFormat = null; passthrough = false; decoderInitialized = false
        outputEncoding = C.ENCODING_INVALID; lastSelectedAudio = null
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
        val line = AudioOutputReport.describeSelectedAudio(tracks) ?: return
        if (line == lastSelectedAudio) return // only when the selection actually changes — bounded ring
        lastSelectedAudio = line
        ring("AUDIO_TRACK_SELECTED $line")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        val msg = "onAudioInputFormatChanged: mime=${format.sampleMimeType} codecs=${format.codecs} " +
            "ch=${format.channelCount} rate=${format.sampleRate}Hz lang=${format.language}"
        android.util.Log.i("EXO_AUDIO_DIAG", msg)
        ring(msg)
        audioFormat = format
        armed = true
        advancing = false
        decoderInitialized = false
        playingSinceArmMs = 0L
        lastTickMs = 0L
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        outputEncoding = audioTrackConfig.encoding
        val encodingName = AudioOutputReport.encodingName(audioTrackConfig.encoding)
        val msg = "AUDIO_TRACK_CONFIG encoding=$encodingName rate=${audioTrackConfig.sampleRate}Hz " +
            "ch=${audioTrackConfig.channelConfig} bufferSize=${audioTrackConfig.bufferSize}" +
            " path=${AudioOutputReport.pathLabel(outputPassthrough)} sink=${diagnostics?.sinkKind}"
        android.util.Log.i("EXO_AUDIO_DIAG", msg)
        ring(msg)

        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.forEach { dev ->
                val devMsg = "AVAILABLE_DEVICE ${AudioOutputReport.deviceLabel(dev.id, dev.type)}"
                android.util.Log.i("EXO_AUDIO_DIAG", devMsg)
                ring(devMsg)
            }
        }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoderInitialized = true
        passthrough = decoderName.startsWith("audio.passthrough", ignoreCase = true)

        android.util.Log.i("EXO_AUDIO_DIAG", "onAudioDecoderInitialized: $decoderName passthrough=$passthrough")
        ring("onAudioDecoderInitialized: $decoderName passthrough=$passthrough")
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) {
        if (!advancing) {
            val msg = "onAudioPositionAdvancing: audio head advancing path=${AudioOutputReport.pathLabel(outputPassthrough)} " +
                "encoding=${AudioOutputReport.encodingName(outputEncoding)}"
            android.util.Log.i("EXO_AUDIO_DIAG", msg)
            ring(msg)

            runCatching {
                // This player's own AudioTrack — never another engine's (Multiscreen tiles build their own).
                val track = diagnostics?.lastTrack
                if (track != null) {
                    val dev = track.routedDevice
                    val routeMsg = "PCM_ROUTE_DIAG session=${track.audioSessionId} " +
                        AudioOutputReport.deviceLabel(dev?.id, dev?.type, "routedId")
                    android.util.Log.i("EXO_AUDIO_DIAG", routeMsg)
                    ring(routeMsg)
                }
            }
        }
        advancing = true
        
        if (!decoderInitialized && !passthrough) {
             val mime = audioFormat?.sampleMimeType ?: ""
             if (mime == MimeTypes.AUDIO_AC3 || mime == MimeTypes.AUDIO_E_AC3 || mime == MimeTypes.AUDIO_DTS) {
                 passthrough = true
                 android.util.Log.i("EXO_AUDIO_DIAG", "onAudioPositionAdvancing: determined passthrough from mime=$mime")
             }
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        android.util.Log.w("AudioOutputPolicy", "audio underrun: buffer=${bufferSize}B/${bufferSizeMs}ms")
        val inWindow = synchronized(underrunTimes) {
            underrunTimes.addLast(now)
            while (underrunTimes.isNotEmpty() && now - underrunTimes.first() > AudioOutputPolicy.UNDERRUN_WINDOW_MS) {
                underrunTimes.removeFirst()
            }
            underrunTimes.size
        }
        // One ring line when a burst reaches the old safety-net threshold, not one per underrun.
        if (inWindow == AudioOutputPolicy.UNDERRUN_REPORT_COUNT) {
            ring("AUDIO_UNDERRUN $inWindow in ${AudioOutputPolicy.UNDERRUN_WINDOW_MS / 1000}s (buffer ${bufferSizeMs}ms)")
        }
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        android.util.Log.e("EXO_AUDIO_DIAG", "onAudioSinkError: ${audioSinkError.message}")
        // Class + message only (no stack); the ring redacts the message again before storing it.
        ring(
            "AUDIO_SINK_ERROR type=${audioSinkError.javaClass.simpleName} " +
                "msg=${audioSinkError.message?.take(MAX_ERROR_CHARS)} " +
                "encoding=${AudioOutputReport.encodingName(outputEncoding)}",
        )
    }

    fun poll(isPlaying: Boolean) {
        if (!armed || advancing) { lastTickMs = 0L; return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!isPlaying) { lastTickMs = 0L; return }
        if (lastTickMs != 0L) playingSinceArmMs += (now - lastTickMs).coerceAtMost(2_000L)
        lastTickMs = now
        
        val graceMs = AudioOutputPolicy.getNoAudioGraceMs()
        if (playingSinceArmMs >= graceMs) {
            val what = audioFormat?.let { MimeTypes.normalizeMimeType(it.sampleMimeType ?: "") } ?: "audio"
            android.util.Log.w("EXO_AUDIO_DIAG", "no sound detected after ${graceMs / 1000}s ($what)")
            // Reported only — the watchdog still takes no action. "No position" is what this measures;
            // it cannot tell whether the TV actually produced sound from a passthrough stream.
            ring(
                "AUDIO_WATCHDOG no audio position after ${graceMs / 1000}s playing ($what) " +
                    "path=${AudioOutputReport.pathLabel(outputPassthrough)} " +
                    "encoding=${AudioOutputReport.encodingName(outputEncoding)}",
            )
            armed = false
        }
    }

    private companion object {
        const val MAX_ERROR_CHARS = 200
    }

    fun describe(): String? {
        val f = audioFormat ?: return null
        val codec = f.sampleMimeType?.substringAfterLast('/')?.uppercase() ?: "?"
        val channels = if (f.channelCount != Format.NO_VALUE) "${f.channelCount}ch" else null
        val rate = if (f.sampleRate != Format.NO_VALUE) "${f.sampleRate / 1000}kHz" else null
        val path = if (passthrough) "passthrough" else "decoded"
        return listOfNotNull(codec, channels, rate, path).joinToString(" \u00b7 ")
    }
}

/** Media3 channel-count cap that matches [mode]. */
fun maxAudioChannelsFor(mode: SurroundMode): Int =
    if (AudioOutputPolicy.allowsMultichannel(mode)) Int.MAX_VALUE else 2
