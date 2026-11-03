package tv.own.owntv.features.epg

/**
 * Phase 4B: how the Guide opens quickly.
 *
 * Before, every open ran four queries over the whole programme table (the channel/guide join, two
 * table-wide counts and a page-by-page read of the 24-hour window, ~650k programmes on the onn) and kept
 * the grid behind a spinner until all of them had finished. Now:
 *  - an open that changes nothing reuses the list already on screen ([reuse]);
 *  - the channel list is shown as soon as it is known; rows read their own programmes with one indexed
 *    query each while the window preload runs behind them ([GuideTiming] marks each step);
 *  - the window only counts as cached once that preload has finished, so rows are never hidden as
 *    "empty" while it is still running.
 */
internal object GuideLoad {

    /** Everything a load depends on, besides the stored guide data. Equal keys produce the same list. */
    data class Key(
        val profileId: Long,
        val sourceIds: List<Long>,
        val query: String,
        val category: String?,
        val sortLive: String,
        val sortGuide: String,
        val favoriteIds: Set<Long>,
        val customizations: Any?,
        val windowStart: Long,
    )

    /**
     * Whether an open can keep the list from the last load: same inputs, the stored guide unchanged since
     * (the [dataSignal] — the per-feed programme counts Room already observes), a list on screen, and no
     * guide import running (its data is still changing; the one reload after it finishes stays).
     */
    fun reuse(last: Key?, next: Key, lastSignal: Long?, signal: Long?, hasList: Boolean, importRunning: Boolean): Boolean =
        hasList && !importRunning && last != null && last == next && lastSignal != null && lastSignal == signal
}

/** Guide open timing, logged as `GuideTiming` (one line per step, milliseconds since the open request). */
internal class GuideTiming(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    @Volatile private var openedAt = 0L
    @Volatile private var opened = false
    @Volatile private var firstFrameLogged = false
    val steps = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun open() {
        openedAt = clock()
        opened = true
        firstFrameLogged = false
        steps.clear()
        mark("guide open requested")
    }

    fun mark(step: String) {
        val ms = if (!opened) -1 else clock() - openedAt
        steps += step
        runCatching { android.util.Log.i(TAG, "$step +${ms}ms") }
    }

    /** The first frame with rows on screen, once per open. */
    fun firstUsableFrame() {
        if (firstFrameLogged || !opened) return
        firstFrameLogged = true
        mark("first usable guide frame")
    }

    private companion object { const val TAG = "GuideTiming" }
}
