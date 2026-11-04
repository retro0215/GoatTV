package tv.own.owntv.features.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.features.shell.MainSection
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * 4.6.0: the compact rail's focus label. The rail stays a narrow icon column; while a section icon has
 * D-pad focus, its localized name floats beside it. The label is drawn in the shell's root overlay, so it
 * sits above the content panel (the TV Guide included) and never widens the rail or moves the content.
 * It is not focusable and takes no keys.
 */
@Stable
class SidebarLabelState {
    /** The focused section icon, or null when focus is not on a section icon. */
    var section by mutableStateOf<MainSection?>(null)
        private set

    /** The focused item's bounds in root coordinates. */
    var anchor by mutableStateOf<Rect?>(null)
        private set

    fun focus(section: MainSection, bounds: Rect?) {
        this.section = section
        if (bounds != null) anchor = bounds
    }

    /** The focused item moved (the rail scrolled, or the layout changed). */
    fun move(section: MainSection, bounds: Rect) {
        if (this.section == section) anchor = bounds
    }

    fun clear(section: MainSection? = null) {
        if (section == null || this.section == section) {
            this.section = null
        }
    }
}

/** Where the label goes: beside the item (after it in reading order), centered on it, kept on screen. */
internal object SidebarLabelPlacement {
    fun position(
        anchor: Rect,
        labelWidth: Int,
        labelHeight: Int,
        origin: Offset,
        containerWidth: Int,
        containerHeight: Int,
        gap: Int,
        rtl: Boolean,
    ): IntOffset {
        val left = anchor.left - origin.x
        val right = anchor.right - origin.x
        val centerY = (anchor.top + anchor.bottom) / 2f - origin.y
        val x = if (rtl) left - gap - labelWidth else right + gap
        val y = centerY - labelHeight / 2f
        val maxX = (containerWidth - labelWidth).coerceAtLeast(0)
        val maxY = (containerHeight - labelHeight).coerceAtLeast(0)
        return IntOffset(x.toInt().coerceIn(0, maxX), y.toInt().coerceIn(0, maxY))
    }
}

/** Draws [state]'s label over the whole shell. Place it after the content, in the shell's root Box. */
@Composable
fun SidebarFocusLabel(state: SidebarLabelState, modifier: Modifier = Modifier) {
    val section = state.section ?: return
    val anchor = state.anchor ?: return
    val colors = OwnTVTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = RoundedCornerShape(10.dp)
    Layout(
        content = {
            Box(
                Modifier
                    .background(colors.surfaceContainerHighest, shape)
                    .border(1.dp, colors.primary.copy(alpha = 0.55f), shape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(section.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val label = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth / 3).coerceAtLeast(1)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val p = SidebarLabelPlacement.position(
                anchor = anchor,
                labelWidth = label.width,
                labelHeight = label.height,
                origin = coordinates?.positionInRoot() ?: Offset.Zero,
                containerWidth = constraints.maxWidth,
                containerHeight = constraints.maxHeight,
                gap = 8.dp.roundToPx(),
                rtl = rtl,
            )
            label.place(p.x, p.y)
        }
    }
}
