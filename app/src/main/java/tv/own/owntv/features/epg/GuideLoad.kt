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
    fun <S : Any> reuse(last: Key?, next: Key, lastSignal: S?, signal: S?, hasList: Boolean, importRunning: Boolean): Boolean =
        hasList && !importRunning && last != null && last == next && lastSignal != null && lastSignal == signal

    /**
     * Phase 4C: only the half-hour window moved since the last load. The channel list does not depend on the
     * window (the channel query has no time filter), so it is kept and only the programmes are re-read. This
     * was the slowest reopen on the onn: 124 s, 62 of them re-running the channel query for the same list.
     */
    fun <S : Any> reuseList(last: Key?, next: Key, lastSignal: S?, signal: S?, hasList: Boolean, importRunning: Boolean): Boolean =
        last != null && last.windowStart != next.windowStart &&
            reuse(last.copy(windowStart = next.windowStart), next, lastSignal, signal, hasList, importRunning)

    /**
     * Phase 4C: "has the data behind the guide changed?", from three cheap reads: the per-feed programme
     * counts Room already observes ([epgCounts], standalone EPG feeds only), the newest programme row id
     * (any feed, including a playlist's own guide; a rowid lookup) and the playlists' channel count.
     */
    data class Signal(val epgCounts: Long?, val maxProgrammeId: Long, val channelCount: Int)
}

/**
 * Phase 4C: the playlist ids the guide rows read their programmes with.
 *
 * Phase 4B showed the channel list before these were set (they were set after the stored-programme count),
 * so on the first open of a session every visible row queried `sourceId IN ()`, got nothing, and stayed
 * empty until the 24-hour preload finished (~40 s on the onn). [prepare] runs before anything is shown; a
 * row read made while the ids are still unknown is not cached ([canQuery]).
 */
internal class GuideRowSources {
    @Volatile var ids: List<Long> = emptyList()
        private set

    /** Sets the ids for the coming list; true when they changed (the row caches hold other sources' data). */
    fun prepare(next: List<Long>): Boolean {
        val changed = ids != next
        ids = next
        return changed
    }

    fun canQuery(): Boolean = ids.isNotEmpty()
}

/**
 * Phase 4C: the guide keys that have programmes ([tv.own.owntv.core.database.dao.EpgDao.guideKeys]), kept
 * between loads for the same sources while the stored guide is unchanged. Never kept during an import.
 */
internal class GuideKeyCache {
    private var stamp: Pair<List<Long>, GuideLoad.Signal>? = null
    private var keys: Set<String>? = null

    fun get(sourceIds: List<Long>, signal: GuideLoad.Signal, importRunning: Boolean): Set<String>? =
        keys?.takeIf { !importRunning && stamp == (sourceIds to signal) }

    fun put(sourceIds: List<Long>, signal: GuideLoad.Signal, importRunning: Boolean, value: Set<String>) {
        if (importRunning) { clear(); return }
        stamp = sourceIds to signal
        keys = value
    }

    fun clear() { stamp = null; keys = null }
}

/**
 * Phase 4C: the guide's channel list without re-running the programme-table match per CursorWindow.
 * Same result as `ChannelDao.channelsWithGuide`: the same candidates in the same order, kept when their
 * normalized key (computed by SQLite, as before) has programmes, cut at the same limit.
 */
internal object GuideChannelQuery {
    /** Under SQLite's 999 bound-variable limit, and one CursorWindow of full channel rows. */
    const val CHUNK = 900

    fun select(candidates: List<tv.own.owntv.core.database.dao.GuideChannelCandidate>, keys: Set<String>, limit: Int): List<Long> =
        candidates.asSequence().filter { c -> c.guideKey != null && c.guideKey in keys }.map { it.id }.take(limit).toList()

    fun chunks(ids: List<Long>): List<List<Long>> = ids.chunked(CHUNK)

    /** [rows] (loaded in any order) back in [order]; ids no longer in the table are dropped. */
    fun <T> inOrder(order: List<Long>, rows: List<T>, id: (T) -> Long): List<T> {
        val byId = rows.associateBy(id)
        return order.mapNotNull { byId[it] }
    }
}

/** Guide open timing, logged as `GuideTiming` (one line per step, milliseconds since the open request). */
internal class GuideTiming(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    @Volatile private var openedAt = 0L
    @Volatile private var opened = false
    @Volatile private var firstFrameLogged = false
    @Volatile private var firstProgrammesLogged = false
    val steps = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun open() {
        openedAt = clock()
        opened = true
        firstFrameLogged = false
        firstProgrammesLogged = false
        steps.clear()
        mark("guide open requested")
    }

    fun mark(step: String) {
        val ms = if (!opened) -1 else clock() - openedAt
        steps += step
        runCatching { android.util.Log.i(TAG, "$step +${ms}ms") }
    }

    /** The first visible row with programme cells ready to draw, once per open. */
    fun firstProgrammes() {
        if (firstProgrammesLogged || !opened) return
        firstProgrammesLogged = true
        mark("first programme cells ready")
    }

    /** The first frame with rows on screen, once per open. */
    fun firstUsableFrame() {
        if (firstFrameLogged || !opened) return
        firstFrameLogged = true
        mark("first usable guide frame")
    }

    private companion object { const val TAG = "GuideTiming" }
}
