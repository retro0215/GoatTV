package tv.own.owntv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * What this TV can actually show, as far as Android will say — used to pick a sensible default version
 * of a film, never to hide one. Every answer is `true`, `false` or `null` (Android could not tell), and
 * callers treat `null` as "not established".
 *
 * - [uhdOutput]: the display offers a mode at least 3840 wide (a 4K output is possible).
 * - [hevcUhdHardware]: a hardware HEVC decoder handles 3840x2160 (most 4K files are HEVC).
 * - [hdrDisplay]: the display reports HDR10, HLG or HDR10+.
 * - [dolbyVisionDisplay] / [dolbyVisionDecoder]: the display reports Dolby Vision, and a decoder for it
 *   exists. Both are needed before a "Dolby Vision" label is trusted to mean anything on this TV.
 */
data class DeviceVideoCaps(
    val uhdOutput: Boolean?,
    val hevcUhdHardware: Boolean?,
    val hdrDisplay: Boolean?,
    val dolbyVisionDisplay: Boolean?,
    val dolbyVisionDecoder: Boolean?,
) {
    companion object {
        val UNKNOWN = DeviceVideoCaps(null, null, null, null, null)
    }
}

/** Reads [DeviceVideoCaps] once per process (the display and codecs do not change while the app runs). */
class DeviceVideoCapabilities(private val context: Context) {

    val caps: DeviceVideoCaps by lazy { runCatching { read() }.getOrDefault(DeviceVideoCaps.UNKNOWN) }

    private fun read(): DeviceVideoCaps {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val uhdOutput = display?.supportedModes?.let { modes -> modes.any { it.physicalWidth >= UHD_WIDTH } }
        @Suppress("DEPRECATION") // Display.getHdrCapabilities: the API-24 call; still answers on newer releases.
        val hdrTypes = display?.let { runCatching { it.hdrCapabilities?.supportedHdrTypes }.getOrNull() }
        val hdrDisplay = hdrTypes?.any { it == HDR_TYPE_HDR10 || it == HDR_TYPE_HLG || it == HDR_TYPE_HDR10_PLUS }
        val dvDisplay = hdrTypes?.contains(HDR_TYPE_DOLBY_VISION)
        val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder } }.getOrNull()
        val hevcUhd = decoders?.any { info ->
            info.supportedTypes.any { it.equals(MIME_HEVC, ignoreCase = true) } && isHardware(info) &&
                runCatching { info.getCapabilitiesForType(MIME_HEVC).videoCapabilities?.isSizeSupported(UHD_WIDTH, UHD_HEIGHT) == true }.getOrDefault(false)
        }
        val dvDecoder = decoders?.any { info -> info.supportedTypes.any { it.equals(MIME_DOLBY_VISION, ignoreCase = true) } }
        return DeviceVideoCaps(uhdOutput, hevcUhd, hdrDisplay, dvDisplay, dvDecoder)
    }

    /** API 29+ says so directly; before that, the platform's own software decoders are named alike. */
    private fun isHardware(info: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }

    private companion object {
        const val UHD_WIDTH = 3840
        const val UHD_HEIGHT = 2160
        const val MIME_HEVC = "video/hevc"
        const val MIME_DOLBY_VISION = "video/dolby-vision"
        // Display.HdrCapabilities.HDR_TYPE_* values, restated so they read the same on every API level.
        const val HDR_TYPE_DOLBY_VISION = 1
        const val HDR_TYPE_HDR10 = 2
        const val HDR_TYPE_HLG = 3
        const val HDR_TYPE_HDR10_PLUS = 4
    }
}
