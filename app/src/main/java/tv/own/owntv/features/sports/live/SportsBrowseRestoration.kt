package tv.own.owntv.features.sports.live

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * Where the user was on the Sports screen. The shell swaps Sports out of composition for fullscreen
 * and Multiscreen (a plain `when` on the section), so everything `remember`ed there is lost; this
 * survives in the activity-scoped [SportsEventsViewModel] and lets Back restore the same place.
 */
class SportsBrowseState {
    /** Vertical scroll of the Sports column, px. */
    var scrollY: Int = 0

    /** Horizontal position per row (row key → first visible item). */
    val rows: MutableMap<String, SportsRowPosition> = HashMap()

    /** Last focused card (event or channel), by stable identity. */
    var focus: SportsFocusTarget? = null

    fun clear() {
        scrollY = 0
        rows.clear()
        focus = null
    }
}

/** First visible item of a row: its stable key (event id / channel id), with index + offset as fallback. */
data class SportsRowPosition(val firstKey: Any?, val firstIndex: Int, val offset: Int)

/** A focused card: its row, its stable key, and its index at the time (fallback if it disappears). */
data class SportsFocusTarget(val rowKey: String, val itemKey: Any, val index: Int)

/** Pure resolution rules (unit-tested): stable identity first, index only as a fallback. */
object SportsBrowseRestoration {

    /** Row start after returning: the same first item by key; else the old index, clamped. */
    fun rowStart(saved: SportsRowPosition?, keys: List<Any>): Pair<Int, Int> {
        if (saved == null || keys.isEmpty()) return 0 to 0
        val byKey = saved.firstKey?.let { keys.indexOf(it) } ?: -1
        return if (byKey >= 0) byKey to saved.offset else saved.firstIndex.coerceIn(0, keys.lastIndex) to 0
    }

    /**
     * The item to focus in [keys] for [target]: the same item by key; if it was removed while away,
     * the nearest item at its old index (clamped); null when the row is now empty.
     */
    fun focusIndex(target: SportsFocusTarget, keys: List<Any>): Int? {
        if (keys.isEmpty()) return null
        val byKey = keys.indexOf(target.itemKey)
        return if (byKey >= 0) byKey else target.index.coerceIn(0, keys.lastIndex)
    }
}

/**
 * Composition side of [SportsBrowseState] for one Sports screen instance. [restoring] is decided once,
 * when Sports is composed: true only when returning from fullscreen / Multiscreen (the shell's
 * `restoreFocus`); entering from the menu starts at the top as before.
 */
@Stable
class SportsBrowseRestorer(
    val state: SportsBrowseState,
    val restoring: Boolean,
    private val onDone: () -> Unit,
) {
    /** True until the saved focus target has been handled (focused, or given up on). */
    var pending: Boolean = restoring
        private set

    fun onItemFocused(rowKey: String, itemKey: Any, index: Int) {
        state.focus = SportsFocusTarget(rowKey, itemKey, index)
    }

    /** The target for [rowKey] while a restore is pending, else null. */
    fun targetFor(rowKey: String): SportsFocusTarget? = state.focus?.takeIf { pending && it.rowKey == rowKey }

    fun finish() {
        if (!pending) return
        pending = false
        onDone()
    }
}

/**
 * A row's [LazyListState], restored to its saved first item (by key) when returning, and saved again
 * when Sports leaves composition. Created once per row; no work on recomposition.
 */
@Composable
fun rememberSportsRowState(restorer: SportsBrowseRestorer?, rowKey: String, keys: List<Any>): LazyListState {
    val listState = remember(rowKey) {
        val saved = if (restorer?.restoring == true) restorer.state.rows[rowKey] else null
        val (index, offset) = SportsBrowseRestoration.rowStart(saved, keys)
        LazyListState(index, offset)
    }
    if (restorer != null) {
        DisposableEffect(rowKey, listState) {
            onDispose {
                restorer.state.rows[rowKey] = SportsRowPosition(
                    firstKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key,
                    firstIndex = listState.firstVisibleItemIndex,
                    offset = listState.firstVisibleItemScrollOffset,
                )
            }
        }
    }
    return listState
}

/**
 * Focuses this row's saved target once (Sports just came back from fullscreen / Multiscreen): scrolls
 * it into view only if needed, then requests focus on its card. Runs once per screen instance.
 */
@Composable
fun SportsRowFocusRestore(
    restorer: SportsBrowseRestorer?,
    rowKey: String,
    keys: List<Any>,
    listState: LazyListState,
    requesters: Map<Any, FocusRequester>,
) {
    val target = restorer?.targetFor(rowKey) ?: return
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val index = SportsBrowseRestoration.focusIndex(target, keys)
        if (index == null) { restorer.finish(); return@LaunchedEffect }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) runCatching { listState.scrollToItem(index) }
        androidx.compose.runtime.withFrameNanos { } // the card is composed and attached
        runCatching { requesters[keys[index]]?.requestFocus() }
        restorer.finish()
    }
}
