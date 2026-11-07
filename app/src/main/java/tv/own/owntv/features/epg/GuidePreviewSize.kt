package tv.own.owntv.features.epg

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

/**
 * How big the TV Guide's live preview is (4.6.0).
 *
 * The preview grows to [Target] (about 28% wider than Phase 4's [Base]) when the band and the grid have the
 * room for it, and never below [Base]. "Room" means at least [MIN_ROWS] whole channel rows still fit under
 * the time axis. The space is measured, not assumed: the app's UI zoom and font size change how many dp
 * the guide gets, so a fixed size would cost rows on some settings.
 *
 * Only the size changes. The preview is the same view on the same player and decoder; a new size is a
 * resize of that view, not a new surface.
 */
internal object GuidePreviewSize {
    /** Phase 4's preview width, and the floor. */
    val Base = 268.dp

    /** The larger preview, ~28% wider than [Base]. */
    val Target = 344.dp

    /** The "Playing: <channel>" caption under the video: a 6 dp gap and one label line. */
    val Caption = 22.dp

    /** Between the band and the time axis. */
    val GapBelow = 8.dp

    /** The time axis with the spacer under it. */
    val Axis = 36.dp

    /** Kept spare under the last whole row, so rounding to pixels never clips it. */
    val Slack = 2.dp

    /** Whole channel rows that must stay visible below the band. */
    const val MIN_ROWS = 3

    /** Height of [MIN_ROWS] rows with the gaps between them. */
    val gridMin: Dp = GuideGridDefaults.RowHeight * MIN_ROWS + GuideGridDefaults.RowGap * (MIN_ROWS - 1)

    /**
     * Preview width for [available]: the height the band, the time axis and the grid share. As wide as
     * keeps [MIN_ROWS] rows, between [Base] and [Target].
     */
    fun width(available: Dp): Dp {
        val videoHeight = available - GapBelow - Axis - gridMin - Slack - Caption
        val fit = videoHeight * 16f / 9f
        return fit.coerceIn(Base, Target)
    }

    /** The 16:9 video's height for a preview [width]. */
    fun videoHeight(width: Dp): Dp = width * 9f / 16f

    /** The band's height: the video and its caption (the details panel beside it takes the same height). */
    fun bandHeight(width: Dp): Dp = videoHeight(width) + Caption
}
