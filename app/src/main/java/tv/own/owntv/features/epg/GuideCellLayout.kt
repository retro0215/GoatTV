package tv.own.owntv.features.epg

/**
 * Where a guide cell's title, reminder bell and catch-up badge go, given how much of the cell is on
 * screen. Text is laid out from the cell's VISIBLE left edge, not its real start: a programme that
 * began before the visible timeline (most programmes on air at Now) otherwise drew its title off the
 * left edge and showed an empty cell, or half a word.
 */
internal object GuideCellLayout {
    /**
     * @property bellX left of the reminder bell, or null when the cell has no reminder or no room.
     * @property textX left of the title / time lines.
     * @property textWidth room for the title (0 = too narrow to draw text).
     * @property badgeX left of the catch-up badge, or null.
     */
    data class Content(val bellX: Float?, val textX: Float, val textWidth: Float, val badgeX: Float?)

    /**
     * @param cellX cell start relative to the visible timeline (negative = began off-screen left).
     * @param cellW cell width; @param viewW visible timeline width.
     * @param bellW width of the bell glyph when the programme has a reminder, else 0.
     * @param badgeW width of the catch-up badge when it applies, else 0.
     * @param minText narrowest title worth drawing (an ellipsis alone is noise).
     */
    fun layout(
        cellX: Float,
        cellW: Float,
        viewW: Float,
        pad: Float,
        gap: Float,
        bellW: Float,
        badgeW: Float,
        minText: Float,
    ): Content {
        val visibleLeft = cellX.coerceAtLeast(0f)
        val visibleRight = (cellX + cellW).coerceAtMost(viewW)
        var left = visibleLeft + pad
        val right = visibleRight - pad
        // Badge sits at the visible right end, so it stays on screen however wide the programme is.
        val badgeX = if (badgeW > 0f && right - left >= badgeW) right - badgeW else null
        val textRight = if (badgeX != null) badgeX - gap else right
        // Bell leads the title, so it reads with it at TV distance instead of hiding at the cell's end.
        val bellX = if (bellW > 0f && textRight - left >= bellW) left else null
        if (bellX != null) left += bellW + gap
        val width = textRight - left
        return Content(bellX, left, if (width >= minText) quantize(width, minText) else 0f, badgeX)
    }

    /**
     * The cell under [time] in a normalized row: cells start at [drawStarts] and end at each programme's stop, and
     * never overlap, so at most one contains it. -1 when none does (a gap, or off the row's data).
     */
    fun cellAt(drawStarts: LongArray, programmes: List<tv.own.owntv.core.database.entity.EpgProgrammeEntity>, time: Long): Int {
        for (i in drawStarts.indices) if (time >= drawStarts[i] && time < programmes[i].stopMs) return i
        return -1
    }

    /** Title widths snap down to this step, so a cell clipped at the timeline edge reuses its layout. */
    const val WIDTH_STEP_PX = 24f

    /**
     * A clipped cell's width changes with every timeline scroll, and each new width was a fresh text
     * measurement (a cache miss) for every row on screen. Snapping the width down to [WIDTH_STEP_PX]
     * makes nearby scroll positions share one layout; the title just ellipsizes up to a step earlier.
     * Never wider than the space available, never below [minText].
     */
    fun quantize(width: Float, minText: Float): Float =
        (width - width % WIDTH_STEP_PX).coerceAtLeast(minText).coerceAtMost(width)
}
