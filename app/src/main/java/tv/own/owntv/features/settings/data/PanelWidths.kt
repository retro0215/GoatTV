package tv.own.owntv.features.settings.data

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.own.owntv.ui.theme.Dimens
import kotlin.math.roundToInt

/** The three browse sections that own a 3-panel layout (category rail · item list/grid · preview). */
enum class PanelSection { LIVE, MOVIES, SERIES }

/** Inset between the shared browse container edge and its columns. */
val BrowseContainerPadding: Dp = 12.dp

/** The mockup's spacing between each column, the divider, and the raised preview. */
val BrowseColumnGap: Dp = 12.dp

/** The category/list separator itself. */
val BrowseColumnDividerSpace: Dp = 1.dp

/** Non-content width inside the shared browse container. */
fun browsePanelGapTotal(previewVisible: Boolean): Dp =
    BrowseColumnDividerSpace + BrowseColumnGap * if (previewVisible) 3 else 2

/**
 * Manual panel-width adjustment (per section, per panel).
 *
 * Each panel holds its SHARE OF THE SCREEN in percent, and the three must add up to exactly 100 — the
 * user sees a running total and can't save until it reads 100%. That keeps the numbers meaning what
 * they look like they mean: "preview panel 40%" really is 40% of the row.
 *
 * Values are whole multiples of [STEP], so a total of exactly 100 is always reachable by stepping.
 */
object PanelWidthLimits {
    /** Category and list panels stay usable; only the third panel may use 0 to mean hidden. */
    const val MIN = 10
    const val MAX = 80
    const val STEP = 5
    const val TOTAL = 100

    fun clamp(pct: Int): Int = pct.coerceIn(MIN, MAX)

    /** Snap to the nearest [STEP] and clamp — every stored/displayed value goes through here. */
    fun snap(pct: Int): Int = clamp((pct.toFloat() / STEP).roundToInt() * STEP)

    /** The third panel has one extra state: exactly 0 means that it is not composed at all. */
    fun snapPreview(pct: Int): Int = if (pct <= 0) 0 else snap(pct)
}

/** One section's three shares, in percent of the row. */
data class PanelShares(val category: Int, val list: Int, val preview: Int) {
    val total: Int get() = category + list + preview
    val isValid: Boolean get() =
        category in PanelWidthLimits.MIN..PanelWidthLimits.MAX &&
            list in PanelWidthLimits.MIN..PanelWidthLimits.MAX &&
            (preview == 0 || preview in PanelWidthLimits.MIN..PanelWidthLimits.MAX) &&
            total == PanelWidthLimits.TOTAL
}

/** Resolved widths for one screen's three panels. */
data class PanelWidthSpec(val category: Dp, val list: Dp, val preview: Dp)

/**
 * The shares the app uses today, for a row [rowWidth] dp wide — what the dialog seeds with, so
 * "default" starts out looking like the shipped layout. Snapped to [PanelWidthLimits.STEP] and
 * corrected so the three always add up to 100.
 *
 * [gapTotal] is the space the browse row's `Arrangement.spacedBy(4.dp)` puts between the panels (two
 * gaps), which the panels themselves never occupy.
 */
fun defaultPanelShares(
    section: PanelSection,
    rowWidth: Dp,
    gapTotal: Dp = 8.dp
): PanelShares {
    return when (section) {
        PanelSection.LIVE -> PanelShares(20, 35, 45)
        PanelSection.MOVIES -> PanelShares(20, 80, 0)
        PanelSection.SERIES -> PanelShares(20, 80, 0)
    }
}

/**
 * Nudges [shares] until they add up to exactly 100, moving the difference onto the biggest panel
 * first (it can absorb it least visibly) and spilling onto the others if that one hits a limit.
 */
fun balanceToTotal(shares: PanelShares): PanelShares {
    val values = intArrayOf(
        PanelWidthLimits.snap(shares.category),
        PanelWidthLimits.snap(shares.list),
        PanelWidthLimits.snapPreview(shares.preview),
    )
    val minimums = intArrayOf(PanelWidthLimits.MIN, PanelWidthLimits.MIN, if (values[2] == 0) 0 else PanelWidthLimits.MIN)
    // Biggest first, so the correction lands where it shows least.
    val order = values.indices.sortedByDescending { values[it] }
    var diff = PanelWidthLimits.TOTAL - values.sum()
    for (i in order) {
        if (diff == 0) break
        val moved = (values[i] + diff).coerceIn(minimums[i], PanelWidthLimits.MAX)
        diff -= moved - values[i]
        values[i] = moved
    }
    return PanelShares(values[0], values[1], values[2])
}

/** Turns validated shares into concrete widths for a row [total] dp wide. */
fun computePanelWidths(
    shares: PanelShares,
    total: Dp,
    gapTotal: Dp = if (shares.preview == 0) 4.dp else 8.dp,
): PanelWidthSpec {
    val content = (total - gapTotal).value.coerceAtLeast(1f)
    // Normalize by the real sum rather than trusting it to be 100: a value written by an older build
    // (or an abandoned edit) must still produce a sane layout instead of over/under-filling the row.
    val sum = shares.total.coerceAtLeast(1)
    val category = content * shares.category / sum
    val list = content * shares.list / sum
    return PanelWidthSpec(
        category = category.dp,
        list = list.dp,
        // The remainder, so rounding never leaves a sliver of background down the right edge.
        // Zero is kept exact: the screen then omits this panel and its second inter-panel gap.
        preview = if (shares.preview == 0) 0.dp else (content - category - list).coerceAtLeast(1f).dp,
    )
}

