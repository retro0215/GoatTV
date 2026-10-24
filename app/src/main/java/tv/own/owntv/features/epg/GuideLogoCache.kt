package tv.own.owntv.features.epg

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException

/**
 * Channel logos for the guide grid, decoded once at row size through the app's shared Coil loader (its
 * disk and memory caches still apply) and drawn as bitmaps. Each logo URL has its own state (see
 * [KeyedStates]), so a logo landing redraws only the rows showing it. Bounded: [maxLogos] bitmaps of
 * about 72×54 px each (≈ 15 KB), the least recently decoded dropped first.
 */
internal class GuideLogoCache(private val context: Context, private val sizePx: Int, maxLogos: Int = 160) {
    private val bitmaps = KeyedStates<String, ImageBitmap>(maxLogos)
    private val failed = BoundedLru<String, Unit>(512)
    private val inFlight = HashSet<String>()

    /** The state for [url] — read its value while drawing. */
    fun state(url: String): State<ImageBitmap?> = bitmaps.state(url)

    operator fun get(url: String?): ImageBitmap? = url?.let { bitmaps[it] }

    /** Decodes [url] unless it is cached, loading, or already failed. Call from the main thread. */
    suspend fun load(url: String?) {
        if (url.isNullOrBlank() || bitmaps[url] != null || failed[url] != null || !inFlight.add(url)) return
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(sizePx)
                .allowHardware(false) // drawn with drawImage on a software-recorded canvas
                .build()
            val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
            val bitmap = image?.toBitmap()?.asImageBitmap()
            if (bitmap != null) bitmaps.put(url, bitmap) else failed[url] = Unit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed[url] = Unit
        } finally {
            inFlight.remove(url)
        }
    }

    val size: Int get() = bitmaps.loadedCount
}
