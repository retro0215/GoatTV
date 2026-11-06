package tv.own.owntv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 4.6.0: a long-press timer must not outlive its window's focus.
 *
 * Compose's `combinedClickable` starts its OK long-press timer on the press and stops it only on the
 * release or when the control loses Compose focus. When the reminder warning (its own window) opens while
 * OK is held, neither happens: the window keeps its Compose focus, and the release Android sends it is a
 * canceled one that [ignoreCanceledKeyReleases] drops. The timer then fired under the warning (seen on the
 * onn: Live TV channel options opened behind it, and took focus after Dismiss).
 *
 * This replaces that timer for keys. It stops the moment the window loses focus, and checks the window is
 * still focused before it acts.
 */
class KeyLongPressGate {
    private var press = 0
    private var pending = false
    private var longFired = false

    /** OK went down. Returns the id the timer must hand back, or null for a key repeat. */
    fun onDown(repeatCount: Int): Int? {
        if (repeatCount != 0) return null
        press++
        pending = true
        longFired = false
        return press
    }

    /** OK came up before the threshold: no long press for this press. */
    fun onUp() { pending = false }

    /** The window or the control lost focus: whatever this press was going to do, it won't. */
    fun cancel() { pending = false }

    /** The timer ran out. True only if the long action should run now. */
    fun onTimer(id: Int, windowFocused: Boolean): Boolean {
        if (!pending || id != press || !windowFocused) {
            pending = false
            return false
        }
        pending = false
        longFired = true
        return true
    }

    /** The release after a long press is not also a click. */
    fun allowClick(): Boolean {
        if (!longFired) return true
        longFired = false
        return false
    }

    companion object {
        /** The keys Compose clicks on (Clickable.kt `isEnter`). */
        fun isOkKey(nativeKeyCode: Int): Boolean =
            nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                nativeKeyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                nativeKeyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                nativeKeyCode == AndroidKeyEvent.KEYCODE_SPACE
    }
}

/**
 * Waits [ms] while the window stays focused. Returns false as soon as it loses focus (or if it already
 * has), true if the whole wait passed with the window focused.
 */
suspend fun waitWhileWindowFocused(ms: Long, windowFocused: Flow<Boolean>): Boolean =
    withTimeoutOrNull(ms) { windowFocused.first { !it } } == null

/**
 * `combinedClickable` for D-pad controls, with a long press that stops when the window loses focus
 * ([KeyLongPressGate]). OK clicks go through a plain `clickable`, so press visuals and focus are unchanged.
 */
@Composable
fun Modifier.windowSafeCombinedClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
): Modifier {
    val gate = remember { KeyLongPressGate() }
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val long by rememberUpdatedState(onLongClick)
    val click by rememberUpdatedState(onClick)
    val timer = remember { arrayOfNulls<Job>(1) }
    fun stop() { timer[0]?.cancel(); timer[0] = null; gate.cancel() }
    return this
        .semantics { if (enabled) onLongClick { long(); true } }
        .onFocusChanged { if (!it.hasFocus) stop() }
        .onPreviewKeyEvent { e ->
            val native = e.nativeKeyEvent
            if (!enabled || !KeyLongPressGate.isOkKey(native.keyCode)) return@onPreviewKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> gate.onDown(native.repeatCount)?.let { id ->
                    timer[0]?.cancel()
                    timer[0] = scope.launch {
                        val stayedFocused = waitWhileWindowFocused(longPressMs, snapshotFlow { windowInfo.isWindowFocused })
                        if (!stayedFocused) gate.cancel()
                        else if (gate.onTimer(id, windowInfo.isWindowFocused)) long()
                    }
                }
                KeyEventType.KeyUp -> { timer[0]?.cancel(); timer[0] = null; gate.onUp() }
            }
            false // the clickable below still sees the press and release
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = { if (gate.allowClick()) click() },
        )
}
