package tv.own.owntv.features.sports.live

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What one OK interaction on an event card meant. Exactly one per interaction, never two. */
enum class SportsOkGesture { SINGLE, DOUBLE, LONG }

/**
 * Pure OK-button gesture recognizer (unit-tested with explicit timestamps).
 *
 *  - LONG fires once the button has been held [longPressMs]; its release fires nothing.
 *  - A second press that starts within [doubleTapMs] of the first release makes the pair a DOUBLE,
 *    reported on the second release; the first tap never reports SINGLE.
 *  - Otherwise a lone tap reports SINGLE once the double-tap window has passed (so Select Channel
 *    opens ~[doubleTapMs] after release; D-pad navigation is never delayed).
 *
 * Callers feed key downs/ups and call [onTick] at [nextDeadline].
 */
class SportsOkGestureRecognizer(
    val doubleTapMs: Long = DOUBLE_TAP_MS,
    val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    private var downAt: Long? = null
    private var longFired = false
    private var secondTap = false
    private var pendingTapUpAt: Long? = null

    /** A key down (repeats while held are ignored). May flush a stale pending SINGLE. */
    fun onDown(now: Long): SportsOkGesture? {
        if (downAt != null) return null // auto-repeat while held
        var out: SportsOkGesture? = null
        val pending = pendingTapUpAt
        if (pending != null) {
            pendingTapUpAt = null
            if (now - pending <= doubleTapMs) secondTap = true else out = SportsOkGesture.SINGLE
        }
        downAt = now
        longFired = false
        return out
    }

    /**
     * The system flagged the held key as a long press (FLAG_LONG_PRESS on the repeat a held remote
     * button produces). Reported at once — the hold is unambiguous — and the release fires nothing.
     */
    fun onSystemLongPress(now: Long): SportsOkGesture? {
        if (downAt == null || longFired) return null
        longFired = true
        secondTap = false
        pendingTapUpAt = null
        return SportsOkGesture.LONG
    }

    fun onUp(now: Long): SportsOkGesture? {
        val down = downAt ?: return null
        downAt = null
        if (longFired) { secondTap = false; return null } // the release of a long press is not a tap
        if (now - down >= longPressMs) { secondTap = false; return SportsOkGesture.LONG } // tick was late
        if (secondTap) { secondTap = false; return SportsOkGesture.DOUBLE }
        pendingTapUpAt = now
        return null
    }

    fun onTick(now: Long): SportsOkGesture? {
        val down = downAt
        if (down != null) {
            if (!longFired && now - down >= longPressMs) {
                longFired = true
                secondTap = false
                return SportsOkGesture.LONG
            }
            return null
        }
        val pending = pendingTapUpAt ?: return null
        if (now - pending > doubleTapMs) {
            pendingTapUpAt = null
            return SportsOkGesture.SINGLE
        }
        return null
    }

    /** When [onTick] next needs to run, or null when nothing is pending. */
    fun nextDeadline(): Long? {
        val down = downAt
        if (down != null) return if (longFired) null else down + longPressMs
        return pendingTapUpAt?.let { it + doubleTapMs + 1 }
    }

    /** Focus left the card: drop everything (a pending tap must not act on a card no longer focused). */
    fun reset() {
        downAt = null
        longFired = false
        secondTap = false
        pendingTapUpAt = null
    }

    companion object {
        /** Double-tap recognition window (product spec: ~250–300 ms). */
        const val DOUBLE_TAP_MS = 280L
        const val DEFAULT_LONG_PRESS_MS = 500L

        fun isOkKey(nativeKeyCode: Int): Boolean =
            nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                nativeKeyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                nativeKeyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }
}

/**
 * Event-card OK handling: single / double / long from the OK button only. Consumes the OK key events
 * (so the card's own click / long-click never also fire) and leaves every other key — the D-pad —
 * untouched. No coroutine runs unless OK is pressed, so sweeping cards costs nothing.
 */
@Composable
fun Modifier.sportsOkGestures(
    onSingle: () -> Unit,
    onDouble: () -> Unit,
    onLong: () -> Unit,
): Modifier {
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val recognizer = remember(longPressMs) { SportsOkGestureRecognizer(longPressMs = longPressMs) }
    val scope = rememberCoroutineScope()
    val single by rememberUpdatedState(onSingle)
    val double by rememberUpdatedState(onDouble)
    val long by rememberUpdatedState(onLong)
    val timer = remember { arrayOfNulls<Job>(1) }
    fun dispatch(g: SportsOkGesture?) {
        when (g) {
            SportsOkGesture.SINGLE -> single()
            SportsOkGesture.DOUBLE -> double()
            SportsOkGesture.LONG -> long()
            null -> Unit
        }
    }
    fun schedule() {
        timer[0]?.cancel()
        val deadline = recognizer.nextDeadline() ?: return
        timer[0] = scope.launch {
            delay((deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
            dispatch(recognizer.onTick(android.os.SystemClock.uptimeMillis()))
            schedule()
        }
    }
    return this
        .onFocusChanged { if (!it.hasFocus) { timer[0]?.cancel(); recognizer.reset() } }
        .onPreviewKeyEvent { e ->
            if (!SportsOkGestureRecognizer.isOkKey(e.key.nativeKeyCode)) return@onPreviewKeyEvent false
            val now = android.os.SystemClock.uptimeMillis()
            when (e.type) {
                KeyEventType.KeyDown -> dispatch(
                    if (e.nativeKeyEvent.isLongPress) recognizer.onSystemLongPress(now) else recognizer.onDown(now),
                )
                KeyEventType.KeyUp -> dispatch(recognizer.onUp(now))
                else -> return@onPreviewKeyEvent false
            }
            schedule()
            true
        }
}
