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
        return Content(bellX, left, if (width >= minText) width else 0f, badgeX)
    }
}
