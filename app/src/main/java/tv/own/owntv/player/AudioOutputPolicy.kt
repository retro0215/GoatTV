package tv.own.owntv.player

import android.content.Context
import android.media.AudioDeviceInfo
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    @Volatile private var latched = false
    @Volatile var latchReason: String? = null
        private set

    val stereoLatched: Boolean get() = latched

    private const val DEFAULT_NO_AUDIO_GRACE_MS = 6_000L
    private const val SHIELD_NO_AUDIO_GRACE_MS = 4_000L

    const val UNDERRUN_LIMIT = 4
    const val UNDERRUN_WINDOW_MS = 10_000L

    fun allowsMultichannel(mode: SurroundMode): Boolean {
        val allowed = mode != SurroundMode.STEREO && !latched
        android.util.Log.i("EXO_AUDIO_DIAG", "allowsMultichannel: mode=$mode latched=$latched -> $allowed")
        return allowed
    }

    fun getNoAudioGraceMs(): Long {
        return if (android.os.Build.MODEL.contains("SHIELD", ignoreCase = true)) {
            SHIELD_NO_AUDIO_GRACE_MS
        } else {
            DEFAULT_NO_AUDIO_GRACE_MS
        }
    }

    fun latchStereo(reason: String) {
        if (latched) return
        latched = true
        latchReason = reason
        android.util.Log.w("AudioOutputPolicy", "forcing stereo for this session: $reason")
    }

    fun clearLatch() {
        latched = false
        latchReason = null
    }
}

/**
 * A [DefaultRenderersFactory] that can be pinned to plain stereo PCM.
 */
@UnstableApi
class OwnTVRenderersFactory(
    context: Context,
    private val forceStereo: Boolean,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val rawCaps = AudioCapabilities.getCapabilities(context)
        
        // Section 5: Log raw capabilities to detect if return-to-AUTO receives correct HDMI data
        val ac3 = rawCaps.supportsEncoding(C.ENCODING_AC3)
        val eac3 = rawCaps.supportsEncoding(C.ENCODING_E_AC3)
        android.util.Log.i("EXO_AUDIO_DIAG", "buildAudioSink: forceStereo=$forceStereo " +
            "maxCh=${rawCaps.maxChannelCount} AC3=$ac3 EAC3=$eac3")

        val caps = if (forceStereo) AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                   else filterAudioCapabilities(rawCaps)

        val processors = arrayOf<androidx.media3.common.audio.AudioProcessor>(
            PcmDiagnosticProcessor("FRONT"),
            PcmDiagnosticProcessor("SINK")
        )

        return runCatching<AudioSink?> {
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder()
                .setAudioCapabilities(caps)
                .setAudioProcessors(processors)
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
        @Volatile var currentTrack: AudioTrack? = null
    }
}

@UnstableApi
class AudioWatchdog(private val appContext: Context) : AnalyticsListener {

    @Volatile private var pendingReason: String? = null
    @Volatile private var fired = false

    @Volatile private var armed = false
    @Volatile private var advancing = false
    @Volatile private var playingSinceArmMs = 0L
    @Volatile private var lastTickMs = 0L

    private val underrunTimes = ArrayDeque<Long>()

    @Volatile var audioFormat: Format? = null
        private set
    @Volatile var passthrough = false
        private set
    @Volatile private var decoderInitialized = false

    fun reset() {
        pendingReason = null; fired = false
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

    override fun onAudioAttributesChanged(
        eventTime: AnalyticsListener.EventTime,
        audioAttributes: androidx.media3.common.AudioAttributes
    ) {
        val msg = "AUDIO_ATTRIBUTES usage=${audioAttributes.usage} content=${audioAttributes.contentType} flags=${audioAttributes.flags}"
        android.util.Log.i("EXO_AUDIO_DIAG", msg)
        LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $msg")
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
        val hit = synchronized(underrunTimes) {
            underrunTimes.addLast(now)
            while (underrunTimes.isNotEmpty() && now - underrunTimes.first() > AudioOutputPolicy.UNDERRUN_WINDOW_MS) {
                underrunTimes.removeFirst()
            }
            underrunTimes.size >= AudioOutputPolicy.UNDERRUN_LIMIT
        }
        if (hit) raise("audio output underran ${AudioOutputPolicy.UNDERRUN_LIMIT}ÃƒÆ’Ã¢â‚¬â€ in ${AudioOutputPolicy.UNDERRUN_WINDOW_MS / 1000}s")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        android.util.Log.e("EXO_AUDIO_DIAG", "onAudioSinkError: ${audioSinkError.message}")
        if (audioSinkError is AudioSink.UnexpectedDiscontinuityException) return
        raise("audio sink error: ${audioSinkError.javaClass.simpleName}")
    }

    private fun raise(reason: String) {
        if (fired) return
        fired = true
        pendingReason = reason
        android.util.Log.i("EXO_AUDIO_DIAG", "raise: $reason")
    }

    fun poll(isPlaying: Boolean): String? {
        pendingReason?.let { pendingReason = null; return it }
        if (!armed || advancing) { lastTickMs = 0L; return null }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!isPlaying) { lastTickMs = 0L; return null }
        if (lastTickMs != 0L) playingSinceArmMs += (now - lastTickMs).coerceAtMost(2_000L)
        lastTickMs = now
        
        val graceMs = AudioOutputPolicy.getNoAudioGraceMs()
        if (playingSinceArmMs < graceMs) return null
        if (fired) return null
        fired = true
        return "no sound from the audio output after ${graceMs / 1000}s"
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

@UnstableApi
private class PcmDiagnosticProcessor(private val label: String) : androidx.media3.common.audio.AudioProcessor {
    private var inputFormat = androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
    private var isActive = false
    private var outputBuffer = androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER

    private var sampleCount = 0L
    private var peak = 0f
    private var sumSquares = 0.0

    override fun configure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        isActive = true
        resetStats()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val startPos = inputBuffer.position()
        if (inputFormat.encoding == C.ENCODING_PCM_16BIT) {
            val shortBuffer = inputBuffer.asShortBuffer()
            while (shortBuffer.hasRemaining()) {
                val sample = shortBuffer.get().toFloat() / 32768f
                val abs = Math.abs(sample)
                if (abs > peak) peak = abs
                sumSquares += (sample * sample).toDouble()
                sampleCount++
            }
        } else if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
             val floatBuffer = inputBuffer.asFloatBuffer()
             while (floatBuffer.hasRemaining()) {
                 val sample = floatBuffer.get()
                 val abs = Math.abs(sample)
                 if (abs > peak) peak = abs
                 sumSquares += (sample * sample).toDouble()
                 sampleCount++
             }
        }
        inputBuffer.position(startPos)

        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()

        if (sampleCount >= (inputFormat.sampleRate.toLong() * inputFormat.channelCount * 2)) {
            val rms = Math.sqrt(sumSquares / sampleCount.toDouble())
            val msg = "PCM_${label}_DIAG encoding=${inputFormat.encoding} peak=$peak rms=$rms allZero=${peak == 0f}"
            android.util.Log.i("EXO_AUDIO_DIAG", msg)
            LiveDiagnosticsLog.event("EXO_AUDIO_DIAG $msg")
            resetStats()
        }
    }

    private fun resetStats() {
        sampleCount = 0
        peak = 0f
        sumSquares = 0.0
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
        return buffer
    }

    override fun queueEndOfStream() {}
    override fun isEnded(): Boolean = false
    @Deprecated("Deprecated in Java")
    override fun flush() { outputBuffer = androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER; resetStats() }
    override fun reset() { isActive = false; outputBuffer = androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER; resetStats() }
}
