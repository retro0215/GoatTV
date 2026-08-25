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
 * A [DefaultRenderersFactory] that can be pinned to plain stereo PCM.
 */
@UnstableApi
class OwnTVRenderersFactory(
    private val context: Context,
    private val forceStereo: Boolean,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val rawCaps = AudioCapabilities.getCapabilities(context)
        
        val caps = if (forceStereo) AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                   else filterAudioCapabilities(rawCaps)

        return runCatching<AudioSink?> {
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder()
                .setAudioCapabilities(caps)
                .setAudioTrackProvider(DiagnosticAudioTrackProvider())
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }.getOrElse {
            android.util.Log.w("AudioOutputPolicy", "sink build failed, falling back", it)
            super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
        }
    }

    private fun filterAudioCapabilities(caps: AudioCapabilities): AudioCapabilities {
        val supported = mutableListOf<Int>()
        supported.add(C.ENCODING_PCM_16BIT)
        supported.add(C.ENCODING_PCM_FLOAT)

        // Section 6: Explicitly block AAC passthrough. 
        val candidates = intArrayOf(
            C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS, C.ENCODING_DTS_HD
        )
        for (encoding in candidates) {
            if (caps.supportsEncoding(encoding)) supported.add(encoding)
        }

        @Suppress("DEPRECATION")
        return AudioCapabilities(supported.toIntArray(), caps.maxChannelCount)
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
