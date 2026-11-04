package tv.own.owntv.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

/**
 * 4.6.0: a place on a screen where the shell draws its live video view (see
 * [tv.own.owntv.features.shell.GuideVideoDock]). The screen lays out an empty [VideoDockTarget]; the shell
 * places its one video view over it. Bounds are in root coordinates.
 */
@Stable
class VideoDockSlot {
    var bounds by mutableStateOf<Rect?>(null)
        private set
    private var holder: Any? = null

    /** [owner] is laid out at [rect]. */
    fun report(owner: Any, rect: Rect) {
        holder = owner
        if (bounds != rect) bounds = rect
    }

    /** [owner] left. A target that already replaced it keeps its bounds. */
    fun clear(owner: Any) {
        if (holder !== owner) return
        holder = null
        bounds = null
    }

    companion object {
        /** Where a view inside a container at [origin] (root coordinates) covers [rect], in whole pixels. */
        fun placement(rect: Rect, origin: Offset): IntRect {
            val left = (rect.left - origin.x).roundToInt()
            val top = (rect.top - origin.y).roundToInt()
            return IntRect(
                left, top,
                (rect.right - origin.x).roundToInt().coerceAtLeast(left),
                (rect.bottom - origin.y).roundToInt().coerceAtLeast(top),
            )
        }
    }
}

/** An empty box that reports where it is to [slot]; nothing is drawn, so the video view over it shows. */
@Composable
fun VideoDockTarget(slot: VideoDockSlot, modifier: Modifier = Modifier) {
    val owner = remember { Any() }
    DisposableEffect(slot) { onDispose { slot.clear(owner) } }
    Box(modifier.onGloballyPositioned { slot.report(owner, it.boundsInRoot()) })
}

/** Places this view exactly over [rect] (root coordinates), inside a container that starts at [origin]. */
fun Modifier.dockedAt(rect: Rect, origin: Offset): Modifier {
    val at = VideoDockSlot.placement(rect, origin)
    return this
        .offset { IntOffset(at.left, at.top) }
        .layout { measurable, _ ->
            val placeable = measurable.measure(Constraints.fixed(at.width, at.height))
            layout(at.width, at.height) { placeable.place(0, 0) }
        }
}
