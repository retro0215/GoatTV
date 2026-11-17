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
class PassthroughFilteringAudioSink(sink: AudioSink) : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (PassthroughPolicy.mayReachSinkDirectly(format)) super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): androidx.media3.exoplayer.audio.AudioOffloadSupport =
        if (PassthroughPolicy.mayReachSinkDirectly(format)) super.getFormatOffloadSupport(format)
        else androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED
}

/**
 * A [DefaultRenderersFactory] that can be pinned to plain stereo PCM.
 */
@UnstableApi
open class OwnTVRenderersFactory( // open only so the unit test can reach buildAudioSink
    private val context: Context,
    private val forceStereo: Boolean,
) : DefaultRenderersFactory(context) {

    @Suppress("DEPRECATION") // setAudioTrackProvider (diagnostics) and the context-less Builder (stereo)
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val kind = AudioSinkKind.of(forceStereo)
        return runCatching<AudioSink?> {
            when (kind) {
                // The context-less builder is the only one that honours fixed capabilities. Fixed is
                // right here: stereo PCM is what the user asked for whatever the HDMI output reports.
                AudioSinkKind.STEREO_PCM -> DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setAudioTrackProvider(DiagnosticAudioTrackProvider())
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
                        .setAudioTrackProvider(DiagnosticAudioTrackProvider())
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .build(),
                )
            }
        }.getOrElse {
            android.util.Log.w("AudioOutputPolicy", "sink build failed, falling back", it)
            val fallback = super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
            if (kind == AudioSinkKind.DYNAMIC_FILTERED && fallback != null) PassthroughFilteringAudioSink(fallback) else fallback
        }
    }

    private class DiagnosticAudioTrackProvider : DefaultAudioSink.AudioTrackProvider {
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
                    "playState=${track.playState} routedId=${dev?.id} type=${dev?.type} " +
                    "product='${dev?.productName}' usage=${attributes.usage} content=${attributes.contentType}"
                android.util.Log.i("EXO_AUDIO_DIAG", msg)
                LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $msg")
                
                currentTrack = track
            }
            return track
        }
    }
    
    companion object {
        /** Reference to the most recently created AudioTrack for routing diagnostics. */
        @Volatile var currentTrack: AudioTrack? = null
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
    @Volatile var passthrough = false
        private set
    @Volatile private var decoderInitialized = false

    fun reset() {
        armed = false; advancing = false
        playingSinceArmMs = 0L; lastTickMs = 0L
        synchronized(underrunTimes) { underrunTimes.clear() }
        audioFormat = null; passthrough = false; decoderInitialized = false
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        val msg = "onAudioInputFormatChanged: mime=${format.sampleMimeType} codecs=${format.codecs} " +
            "ch=${format.channelCount} rate=${format.sampleRate}Hz lang=${format.language}"
        android.util.Log.i("EXO_AUDIO_DIAG", msg)
        LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $msg")
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
        val encodingName = when (audioTrackConfig.encoding) {
            C.ENCODING_PCM_16BIT -> "PCM_16BIT"
            C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            C.ENCODING_AC3 -> "AC3"
            C.ENCODING_E_AC3 -> "E_AC3"
            C.ENCODING_E_AC3_JOC -> "E_AC3_JOC"
            C.ENCODING_DTS -> "DTS"
            C.ENCODING_DTS_HD -> "DTS_HD"
            else -> "ENCODING_${audioTrackConfig.encoding}"
        }
        val msg = "AUDIO_TRACK_CONFIG encoding=$encodingName rate=${audioTrackConfig.sampleRate}Hz " +
            "ch=${audioTrackConfig.channelConfig} bufferSize=${audioTrackConfig.bufferSize}"
        android.util.Log.i("EXO_AUDIO_DIAG", msg)
        LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $msg")
        
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.forEach { dev ->
                val devMsg = "AVAILABLE_DEVICE id=${dev.id} type=${dev.type} product='${dev.productName}'"
                android.util.Log.i("EXO_AUDIO_DIAG", devMsg)
                LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $devMsg")
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
        LiveDiagnosticsLog.event("EXO_AUDIO_DIAG onAudioDecoderInitialized: $decoderName passthrough=$passthrough")
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) {
        if (!advancing) {
            val path = if (passthrough) "passthrough (bitstream)" else "decoded (PCM)"
            val msg = "onAudioPositionAdvancing: audio head advancing path=$path"
            android.util.Log.i("EXO_AUDIO_DIAG", msg)
            LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $msg")
            
            runCatching {
                val track = OwnTVRenderersFactory.currentTrack
                if (track != null) {
                    val dev = track.routedDevice
                    val routeMsg = "PCM_ROUTE_DIAG session=${track.audioSessionId} " +
                        "routedId=${dev?.id} type=${dev?.type} product='${dev?.productName}'"
                    android.util.Log.i("EXO_AUDIO_DIAG", routeMsg)
                    LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $routeMsg")
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
        synchronized(underrunTimes) {
            underrunTimes.addLast(now)
            while (underrunTimes.isNotEmpty() && now - underrunTimes.first() > AudioOutputPolicy.UNDERRUN_WINDOW_MS) {
                underrunTimes.removeFirst()
            }
        }
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        android.util.Log.e("EXO_AUDIO_DIAG", "onAudioSinkError: ${audioSinkError.message}")
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
            armed = false
        }
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
