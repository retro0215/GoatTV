package tv.own.owntv.features.sports.live

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Subtle team accent derived from the team's own logo (glows behind crests). Purely decorative:
 * every card and the details panel look complete with a null accent, so a failed or slow extraction
 * never affects readability. Logos are decoded at 32px (software bitmap) once per URL and cached.
 */
internal object SportsTeamAccent {

    /**
     * Picks the logo's dominant saturated hue from ARGB pixels, ignoring transparent, near-white,
     * near-black and gray pixels; null when the mark is essentially monochrome. Pure (unit-tested).
     */
    fun dominantAccent(pixels: IntArray): Int? {
        val bins = 18
        val weight = DoubleArray(bins)
        val sumR = DoubleArray(bins); val sumG = DoubleArray(bins); val sumB = DoubleArray(bins)
        var opaque = 0
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a < 160) continue
            opaque++
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val v = max / 255.0
            val s = if (max == 0) 0.0 else (max - min).toDouble() / max
            if (s < 0.28 || v < 0.18) continue // gray / black / white outlines carry no team color
            val h = hue(r, g, b, max, min)
            val bin = ((h / 360.0) * bins).toInt().coerceIn(0, bins - 1)
            val w = s * (0.35 + v) // favour vivid, reasonably bright pixels
            weight[bin] += w; sumR[bin] += r * w; sumG[bin] += g * w; sumB[bin] += b * w
        }
        if (opaque == 0) return null
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        // Require the hue to cover a meaningful share of the mark (≈6% of opaque pixels at full weight).
        if (weight[best] < opaque * 0.06) return null
        val r = (sumR[best] / weight[best]).toInt().coerceIn(0, 255)
        val g = (sumG[best] / weight[best]).toInt().coerceIn(0, 255)
        val b = (sumB[best] / weight[best]).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hue(r: Int, g: Int, b: Int, max: Int, min: Int): Double {
        val d = (max - min).toDouble()
        if (d == 0.0) return 0.0
        val h = when (max) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60.0
        return if (h < 0) h + 360 else h
    }

}

/** Per-URL accent cache (Android-only; kept apart from the pure math so it stays JVM-testable). */
private object SportsTeamAccentCache {
    private val cache = LruCache<String, Int>(256)
    const val NONE = 0 // cached "no usable accent" (dominantAccent always returns an opaque color)

    fun cached(url: String): Int? = cache.get(url)

    fun put(url: String, argb: Int?) { cache.put(url, argb ?: NONE) }
}

/** The team's accent color for [logoUrl], or null (no logo / monochrome / not loaded yet). */
@Composable
internal fun rememberTeamAccent(logoUrl: String?): Color? {
    if (logoUrl.isNullOrBlank()) return null
    val context = LocalContext.current
    val initial = SportsTeamAccentCache.cached(logoUrl)
    var argb by remember(logoUrl) { mutableStateOf(initial) }
    if (initial == null) {
        LaunchedEffect(logoUrl) {
            val extracted = try {
                val request = ImageRequest.Builder(context)
                    .data(logoUrl)
                    .size(32)
                    .allowHardware(false) // pixels must be readable
                    .build()
                val result = SingletonImageLoader.get(context).execute(request)
                (result as? SuccessResult)?.image?.toBitmap()?.let { bitmap ->
                    withContext(Dispatchers.Default) {
                        val px = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        SportsTeamAccent.dominantAccent(px)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            SportsTeamAccentCache.put(logoUrl, extracted)
            argb = extracted ?: 0
        }
    }
    val value = argb ?: return null
    return if (value == SportsTeamAccentCache.NONE) null else Color(value)
}
