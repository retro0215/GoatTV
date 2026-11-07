package tv.own.owntv.features.epg

import android.content.ComponentCallbacks2
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 4.6.0: the Guide's loaded window (every programme from the catch-up lookback, up to 7 days, to the grid's
 * end, for every guide channel: hundreds of thousands of rows on a big lineup) is kept by the activity-scoped
 * EpgViewModel after the Guide closes, so it reopens instantly. Under real memory pressure, and only while
 * the Guide is off screen, it is released; the next open reads the window again.
 *
 * Fed by OwnTVApp.onTrimMemory. Not RUNNING_LOW / RUNNING_MODERATE (frequent on 2 GB TV boxes during normal
 * playback, and dropping the cache there would cost every reopen) and not UI_HIDDEN (merely leaving the app).
 */
internal object GuideMemoryPressure {
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    fun onTrimMemory(level: Int) {
        if (releases(level)) _events.tryEmit(level)
    }

    /** RUNNING_CRITICAL, or the process waiting in the background LRU list (BACKGROUND and above). */
    @Suppress("DEPRECATION") // the RUNNING_* levels are still what pre-14 TV devices deliver
    fun releases(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
}
