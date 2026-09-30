package tv.own.owntv.features.multiscreen

import kotlin.math.abs

/** A tile's place in the Multiscreen area, in whatever unit the caller measures with (dp for layout). */
data class TileRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

enum class MultiscreenDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Where each Multiscreen tile goes — the Mobile Multiview "Auto" landscape shapes for one to four
 * tiles (GoatTV-Mobile `MultiviewGridMath`), in display order. Only real channel tiles are counted:
 * the Add control is never a cell.
 *
 * Rects touch edge to edge; the screen insets each tile (as Mobile does) so the gaps are visual only
 * and the shapes are the same either way. Pure geometry, so directional navigation and Move Mode can
 * ask the same shape the screen draws.
 */
object MultiscreenLayout {
    /** Multiscreen's one tile maximum, on every device and brand; the store, screen and picker read it. */
    const val MAX_TILES = 4

    fun rects(count: Int, width: Float, height: Float): List<TileRect> {
        if (count <= 0 || width <= 0f || height <= 0f) return emptyList()
        return when (count.coerceAtMost(MAX_TILES)) {
            1 -> listOf(TileRect(0f, 0f, width, height))
            // Side by side, full height.
            2 -> {
                val w = width / 2f
                listOf(TileRect(0f, 0f, w, height), TileRect(w, 0f, width - w, height))
            }
            // Big tile left (2/3 width, full height); two stacked on the right.
            3 -> {
                val bigW = width * 2f / 3f
                val smallW = width - bigW
                val smallH = height / 2f
                listOf(
                    TileRect(0f, 0f, bigW, height),
                    TileRect(bigW, 0f, smallW, smallH),
                    TileRect(bigW, smallH, smallW, height - smallH),
                )
            }
            else -> {
                val w = width / 2f
                val h = height / 2f
                listOf(
                    TileRect(0f, 0f, w, h),
                    TileRect(w, 0f, width - w, h),
                    TileRect(0f, h, w, height - h),
                    TileRect(w, h, width - w, height - h),
                )
            }
        }
    }

    /**
     * The tile reached from [from] by pressing [direction], or null at that edge of the grid.
     *
     * A candidate must lie wholly on that side. Tiles sharing the perpendicular span are preferred,
     * then the nearest edge, then the closest centre on the other axis; a remaining tie goes to the
     * lower index, so RIGHT from the big 3-tile left lands on the top-right tile.
     */
    fun neighbour(rects: List<TileRect>, from: Int, direction: MultiscreenDirection): Int? {
        val src = rects.getOrNull(from) ?: return null
        var best: Int? = null
        var bestKey: Triple<Int, Float, Float>? = null
        rects.forEachIndexed { i, r ->
            if (i == from) return@forEachIndexed
            val gap = when (direction) {
                MultiscreenDirection.RIGHT -> r.left - src.right
                MultiscreenDirection.LEFT -> src.left - r.right
                MultiscreenDirection.DOWN -> r.top - src.bottom
                MultiscreenDirection.UP -> src.top - r.bottom
            }
            if (gap < -EPSILON) return@forEachIndexed
            val horizontal = direction == MultiscreenDirection.LEFT || direction == MultiscreenDirection.RIGHT
            val overlaps = if (horizontal) {
                r.top < src.bottom - EPSILON && r.bottom > src.top + EPSILON
            } else {
                r.left < src.right - EPSILON && r.right > src.left + EPSILON
            }
            val offAxis = if (horizontal) abs(r.centerY - src.centerY) else abs(r.centerX - src.centerX)
            val key = Triple(if (overlaps) 0 else 1, maxOf(gap, 0f), offAxis)
            val current = bestKey
            if (current == null || compare(key, current) < 0) {
                best = i
                bestKey = key
            }
        }
        return best
    }

    /** True when nothing sits above this tile — UP from it leaves the grid for the control strip. */
    fun isTopRow(rects: List<TileRect>, index: Int): Boolean =
        rects.getOrNull(index)?.let { neighbour(rects, index, MultiscreenDirection.UP) == null } ?: false

    private fun compare(a: Triple<Int, Float, Float>, b: Triple<Int, Float, Float>): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        if (abs(a.second - b.second) > EPSILON) return a.second.compareTo(b.second)
        if (abs(a.third - b.third) > EPSILON) return a.third.compareTo(b.third)
        return 0
    }

    private const val EPSILON = 0.5f
}
