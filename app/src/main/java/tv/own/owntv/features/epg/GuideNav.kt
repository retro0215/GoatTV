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
     * Where the guide opens: the channel being watched (else the first row), at the current time.
     * Returns null for an empty guide.
     */
    fun openingCursor(channelIds: List<Long>, watchingChannelId: Long?, now: Long): Cursor? {
        if (channelIds.isEmpty()) return null
        val row = watchingChannelId?.let { id -> channelIds.indexOf(id) }?.takeIf { it >= 0 } ?: 0
        return Cursor(row, now)
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
