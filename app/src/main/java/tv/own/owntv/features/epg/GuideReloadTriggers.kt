package tv.own.owntv.features.epg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * When the Guide should reload because its stored data changed: the programme count, but only while
 * no EPG sync is running ([syncIdle]), de-duplicated and settled for [settleMs]. The first value is
 * dropped because the Guide screen triggers its own initial load.
 *
 * Holding reloads until the sync is idle is what keeps a long EPG import from re-reading the whole
 * guide window after every batch it writes — each of those reloads held a full copy of the window and,
 * stacked up while the user browsed, ran the app out of heap. A sync now causes one reload, at the end.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun guideReloadTriggers(counts: Flow<Int>, syncIdle: Flow<Boolean>, settleMs: Long): Flow<Int> =
    counts.combine(syncIdle) { count, idle -> count to idle }
        .filter { (_, idle) -> idle }
        .map { (count, _) -> count }
        .distinctUntilChanged()
        .debounce(settleMs)
        .drop(1)
