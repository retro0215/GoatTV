package tv.own.owntv.features.epg

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.sync.work.ProgramReminders

/**
 * Navigation rules for the full TV Guide, kept out of [EpgScreen] so they can be unit-tested.
 *
 * The guide has ONE focus target (the grid) and a cursor: a channel row plus a time. Up/Down change the
 * row and keep the time, so the highlight stays in the same column; Left/Right move the time to the
 * previous/next programme of the current row. The highlighted programme is always the one airing at
 * the cursor time (else the nearest earlier one). Nothing here touches the database.
 */
internal object GuideNav {
    const val SLOT_MS = GuideGridDefaults.SlotMin * 60_000L

    data class Cursor(val row: Int, val timeMs: Long)

    /** What OK on a programme focuses first in its details dialog. OK never tunes by itself. */
    enum class DetailFocus { WATCH, REMINDER, CATCHUP }

    /**
     * Where the guide opens: the first of [preferredChannelIds] that is listed in this view, at the
     * current time — callers pass the channel playing now, then the last one watched, in that order. A
     * channel outside the active playlist or category is not in [channelIds], so it is skipped, and the
     * first row is the last resort. Returns null for an empty guide. Opening never tunes anything.
     */
    fun openingCursor(channelIds: List<Long>, preferredChannelIds: List<Long?>, now: Long): Cursor? {
        if (channelIds.isEmpty()) return null
        val row = preferredChannelIds.asSequence()
            .filterNotNull()
            .map { id -> channelIds.indexOf(id) }
            .firstOrNull { it >= 0 } ?: 0
        return Cursor(row, now)
    }

    /** The highlight time row [row] draws: the cursor's time on the cursor's row while the grid has focus. */
    fun highlightFor(cursor: Cursor?, gridFocused: Boolean, row: Int): Long? =
        cursor?.takeIf { gridFocused && it.row == row }?.timeMs

    enum class CategoryChange { FILTER_LAST_LIST, FULL_LOAD }

    /**
     * How a category change is applied. Re-filtering the last full list is enough unless there is none
     * yet, a full load is already running (it applies the newest category itself), or the category is a
     * custom one (its members bypass the hidden-category filter the last list already applied).
     */
    fun categoryChange(hasLastList: Boolean, fullLoadRunning: Boolean, customCategory: Boolean): CategoryChange =
        if (!hasLastList || fullLoadRunning || customCategory) CategoryChange.FULL_LOAD else CategoryChange.FILTER_LAST_LIST

    // ---- Header access -------------------------------------------------------------------------

    /** The header control Back (from the grid) lands on. */
    enum class HeaderTarget { JUMP_TO_NOW, CATEGORY, MORE }

    /**
     * Back in the grid goes to the header — the first header control shown (Jump to Now only exists
     * while Now is inside the loaded window, Category only when the playlist has categories). A second
     * Back, from the header, leaves the guide for the menu as before; Down returns to the grid on the
     * same programme. Up from the first row still reaches the header too.
     */
    fun headerTarget(jumpToNowShown: Boolean, categoryShown: Boolean): HeaderTarget = when {
        jumpToNowShown -> HeaderTarget.JUMP_TO_NOW
        categoryShown -> HeaderTarget.CATEGORY
        else -> HeaderTarget.MORE
    }

    /**
     * Focus entering the guide from outside goes to [default] (the grid) unless a specific control was
     * asked for: closing More / Category requests its button, and that request must not be redirected.
     */
    fun <T : Any> enterTarget(pending: T?, default: T): T = pending ?: default

    /** CH+/CH−/Page keys: the row one page ([pageSize] rows) away, kept inside the list. */
    fun pageRow(row: Int, pages: Int, pageSize: Int, rowCount: Int): Int? {
        if (rowCount <= 0) return null
        val target = (row + pages * pageSize.coerceAtLeast(1)).coerceIn(0, rowCount - 1)
        return target.takeIf { it != row }
    }

    /**
     * Pixels to scroll the list so its first fully visible row becomes [targetFirst] (rows are uniform).
     * Scrolling by pixels, unlike a jump to an index, lets the list prefetch the next rows.
     */
    fun rowScrollDeltaPx(targetFirst: Int, firstVisibleIndex: Int, firstVisibleOffsetPx: Int, rowStridePx: Int): Int =
        (targetFirst - firstVisibleIndex) * rowStridePx - firstVisibleOffsetPx

    /**
     * The day the header shows while browsing: the highlighted programme's start, so a programme that
     * runs past midnight keeps its own date instead of flipping to the next day mid-programme. With no
     * programme under the cursor, the cursor's own time.
     */
    fun headerDay(programme: EpgProgrammeEntity?, cursorTime: Long): Long = programme?.startMs ?: cursorTime

    // ---- Row data, selection, paging ------------------------------------------------------------

    /**
     * One row's display data, prepared off the main thread: the programmes as cached, and which of them
     * the channel can replay. Channels without an archive skip the scan entirely.
     */
    fun prepareRow(channel: ChannelEntity, programmes: List<EpgProgrammeEntity>, now: Long, revision: Int): GuideRowData {
        val catchupIds = if (!channel.catchup) emptySet() else programmes.filter { canCatchup(channel, it, now) }.mapTo(HashSet()) { it.id }
        return GuideRowData(channel.id, programmes, catchupIds, revision)
    }

    /**
     * The day the header shows for [cursor]: the start of the programme under it on the row's channel
     * in [rows] (the CURRENT list — the Phase 3C header kept the list from its first composition, before
     * the guide had loaded, so it always fell back to the cursor time and showed the next day after
     * midnight). Falls back to the cursor time when the row or programme is unknown.
     */
    fun selectionDay(rows: List<ChannelEntity>, cursor: Cursor, programmesOf: (ChannelEntity) -> List<EpgProgrammeEntity>): Long {
        val channel = rows.getOrNull(cursor.row) ?: return cursor.timeMs
        return headerDay(programmeAt(programmesOf(channel), cursor.timeMs), cursor.timeMs)
    }

    /**
     * The order rows are prepared in: the visible rows top to bottom first, then [margin] rows below and
     * above, alternating outward (the direction of travel is the likelier next view). Indices only.
     */
    fun prepOrder(firstVisible: Int, visible: Int, margin: Int, rowCount: Int): List<Int> {
        if (rowCount <= 0) return emptyList()
        val first = firstVisible.coerceIn(0, rowCount - 1)
        val last = (first + visible - 1).coerceAtMost(rowCount - 1)
        val out = ArrayList<Int>(visible + margin * 2)
        for (i in first..last) out += i
        for (k in 1..margin) {
            (last + k).takeIf { it < rowCount }?.let { out += it }
            (first - k).takeIf { it >= 0 }?.let { out += it }
        }
        return out
    }

    /** Where a page move lands: the focused row and the list's first visible row after it. */
    data class PageMove(val row: Int, val firstVisible: Int)

    /**
     * CH+/CH−: a full screen of rows ([visibleRows] fully visible), and the list scrolls by the same
     * amount so the focused row keeps its place on screen — a page flip, not a crawl to the edge. At
     * either end of the list the row still moves to the first/last channel. Null when it can't move.
     */
    fun pageMove(row: Int, pages: Int, visibleRows: Int, firstVisible: Int, rowCount: Int): PageMove? {
        if (rowCount <= 0) return null
        val page = visibleRows.coerceAtLeast(1)
        val target = (row + pages * page).coerceIn(0, rowCount - 1)
        if (target == row) return null
        val maxFirst = (rowCount - page).coerceAtLeast(0)
        val first = (firstVisible + (target - row)).coerceIn(0, maxFirst)
        return PageMove(target, first.coerceAtMost(target).coerceAtLeast(target - page + 1).coerceAtLeast(0))
    }

    /**
     * A focus target to honour the next time focus enters the guide from outside. [arm] before asking
     * for the target; [disarm] once that request has run, whether or not the entry rule consumed it —
     * a request left armed (Phase 3C) later hijacked the grid's return from the channel-options dialog.
     */
    class PendingFocus<T : Any> {
        private var target: T? = null
        fun arm(t: T) { target = t }
        fun take(): T? = target.also { target = null }
        fun disarm(t: T) { if (target == t) target = null }
        val armed: T? get() = target
    }

    /** The guide's overlays, and where focus goes when one closes. */
    enum class Overlay { PROGRAMME, CHANNEL_OPTIONS, EPG_MATCH, EPG_OFFSET, MORE, CATEGORY }

    enum class FocusTarget { GRID, MORE_BUTTON, CATEGORY_BUTTON }

    /**
     * Focus after [overlay] closes: back to the control that opened it. Programme and channel dialogs
     * come from the grid; More and Category from their header buttons (Phase 3 dropped focus to the
     * sidebar after More closed). Picking a category keeps focus on its button, as the list reloads.
     */
    fun focusAfterClose(overlay: Overlay): FocusTarget = when (overlay) {
        Overlay.PROGRAMME, Overlay.CHANNEL_OPTIONS, Overlay.EPG_MATCH, Overlay.EPG_OFFSET -> FocusTarget.GRID
        Overlay.MORE -> FocusTarget.MORE_BUTTON
        Overlay.CATEGORY -> FocusTarget.CATEGORY_BUTTON
    }

    /** The programme the cursor highlights on a row: airing at [timeMs], else the latest one before it. */
    fun programmeAt(programmes: List<EpgProgrammeEntity>, timeMs: Long): EpgProgrammeEntity? =
        programmes.firstOrNull { timeMs >= it.startMs && timeMs < it.stopMs }
            ?: programmes.lastOrNull { it.startMs <= timeMs }

    /**
     * Left/Right: the cursor time on the previous/next programme of this row, or null at the edge of the
     * loaded window (Left then hands focus back to the menu). A row without guide data moves by one slot.
     */
    fun stepTime(
        programmes: List<EpgProgrammeEntity>,
        timeMs: Long,
        delta: Int,
        windowStart: Long,
        windowEnd: Long,
    ): Long? {
        if (programmes.isEmpty()) {
            val next = timeMs + delta * SLOT_MS
            return next.takeIf { it in windowStart until windowEnd }
        }
        if (delta > 0) return programmes.firstOrNull { it.startMs > timeMs && it.startMs < windowEnd }?.startMs
        // Before the row's first programme: plain slots back to the window start.
        val current = programmeAt(programmes, timeMs) ?: return (timeMs - SLOT_MS).takeIf { it >= windowStart }
        // The programme on screen began at or before the window start: nothing further left is loaded.
        if (current.startMs <= windowStart) return null
        val previous = programmes.lastOrNull { it.startMs < current.startMs }
            ?: return (current.startMs - SLOT_MS).takeIf { it >= windowStart } // a gap before it
        // A previous programme that straddles the window start is entered at its first visible minute.
        return maxOf(previous.startMs, windowStart)
    }

    /** Up/Down: the adjacent row, or null at the top/bottom (Up from the first row reaches the header). */
    fun stepRow(row: Int, delta: Int, rowCount: Int): Int? =
        (row + delta).takeIf { it in 0 until rowCount }

    /**
     * The first visible row to scroll to so [row] is on screen, or null when it already is. Moves the
     * list by the least amount, so a held Down scrolls one row at a time instead of jumping.
     */
    fun rowScrollTarget(row: Int, firstVisible: Int, visibleCount: Int): Int? {
        if (visibleCount <= 0) return row
        return when {
            row < firstVisible -> row
            row >= firstVisible + visibleCount -> row - visibleCount + 1
            else -> null
        }
    }

    /**
     * Timeline scroll (in minutes from the window start) that brings [timeMs] back into view, placed one
     * slot from the left edge, or null when it is already visible (short of the last slot). The cursor itself is the anchor, so a long
     * programme that started earlier stays clipped on the left rather than scrolling back hours.
     */
    fun timelineScrollTarget(timeMs: Long, windowStart: Long, scrollMinutes: Int, viewportMinutes: Int): Int? {
        val at = ((timeMs - windowStart) / 60_000L).toInt()
        val margin = GuideGridDefaults.SlotMin
        return when {
            at < scrollMinutes -> (at - margin).coerceAtLeast(0)
            viewportMinutes > 0 && at > scrollMinutes + viewportMinutes - margin -> (at - margin).coerceAtLeast(0)
            else -> null
        }
    }

    /** The scroll the guide opens at: the half-hour slot holding [now] at the left edge. */
    fun openingScrollMinutes(now: Long, windowStart: Long): Int {
        if (now <= windowStart) return 0
        val minutes = ((now - windowStart) / 60_000L).toInt()
        return minutes - minutes % GuideGridDefaults.SlotMin
    }

    fun detailFocus(programme: EpgProgrammeEntity, now: Long, canCatchup: Boolean): DetailFocus = when {
        programme.startMs > now -> DetailFocus.REMINDER
        programme.stopMs <= now && canCatchup -> DetailFocus.CATCHUP
        now in programme.startMs until programme.stopMs -> DetailFocus.WATCH
        canCatchup -> DetailFocus.CATCHUP
        else -> DetailFocus.WATCH
    }

    /** A catch-up channel can replay a programme that has started and is still inside its archive. */
    fun canCatchup(channel: ChannelEntity, programme: EpgProgrammeEntity, now: Long): Boolean {
        if (!channel.catchup || programme.startMs > now) return false
        val windowMs = channel.catchupDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        return now - programme.startMs <= windowMs
    }

    /**
     * Rows to show. Channels whose loaded guide window is empty (a blank row, nothing to browse) are
     * hidden unless [showAll]. [hasProgrammes] answers null while a row's guide isn't loaded yet; those
     * stay visible, so a guide still filling never hides channels it simply hasn't read.
     */
    fun visibleChannels(
        channels: List<ChannelEntity>,
        showAll: Boolean,
        hasProgrammes: (ChannelEntity) -> Boolean?,
    ): List<ChannelEntity> = if (showAll) channels else channels.filter { hasProgrammes(it) != false }

    /**
     * Category filter: a custom category keeps its member channels; a provider category keeps its
     * channels except ones the viewer moved out of it ([movedFrom] = the category key it was moved
     * from); no filter keeps everything. Order is preserved.
     */
    fun filterByCategory(
        channels: List<ChannelEntity>,
        customMemberIds: Set<Long>?,
        categoryId: Long?,
        categoryKey: String?,
        movedFrom: (ChannelEntity) -> String?,
    ): List<ChannelEntity> = when {
        customMemberIds != null -> channels.filter { it.id in customMemberIds }
        categoryId != null -> channels.filter { it.categoryId == categoryId && movedFrom(it) != categoryKey }
        else -> channels
    }

    /** Start times on [channelId] with an active-profile reminder: the bells on that row. */
    fun reminderStarts(slots: Set<ProgramReminders.Key>, channelId: Long): Set<Long> =
        slots.filterTo(HashSet()) { it.channelId == channelId }.mapTo(HashSet()) { it.programStartMs }

    /** The programme after [current] on the row ("Next" in the details panel). */
    fun nextAfter(programmes: List<EpgProgrammeEntity>, current: EpgProgrammeEntity?): EpgProgrammeEntity? =
        current?.let { c -> programmes.firstOrNull { it.startMs >= c.stopMs } }
}
