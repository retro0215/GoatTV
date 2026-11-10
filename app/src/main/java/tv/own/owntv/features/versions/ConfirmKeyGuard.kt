package tv.own.owntv.features.versions

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Swallows OK/Enter for a moment after a version was chosen.
 *
 * Choosing in the version picker hands focus back (to the control that opened it, or one that stays on
 * screen) and starts playback or a version switch. A quick double OK on the remote then sent the second
 * press wherever focus was at that instant — the top bar's Search chip in 4.6.1 RC2, which opened Search
 * behind the film; or the opener, which would open the picker again or start the episode twice. For
 * [windowMs] after [arm], confirm keys are dropped (both the press and its release, so no click can
 * form). Other keys, and every key after the window, pass as usual.
 */
class ConfirmKeyGuard(private val windowMs: Long = WINDOW_MS) {
    private var armedAt: Long = Long.MIN_VALUE

    /** A choice was just made at [nowUptimeMs] (SystemClock.uptimeMillis, the key events' clock). */
    fun arm(nowUptimeMs: Long) { armedAt = nowUptimeMs }

    fun swallows(isConfirmKey: Boolean, eventUptimeMs: Long): Boolean {
        if (!isConfirmKey || armedAt == Long.MIN_VALUE) return false
        val age = eventUptimeMs - armedAt
        return age in 0 until windowMs
    }

    companion object {
        /** Longer than a fast double press on a TV remote (~150–300 ms), short enough to go unnoticed. */
        const val WINDOW_MS = 600L

        fun isConfirm(key: Key): Boolean = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
    }
}

/**
 * The one guard version pickers arm on a choice ([VersionPicker]); applied at the shell's root, so the
 * swallowed press cannot land anywhere in the window — the top bar included.
 */
object VersionChoiceKeyGuard {
    val guard = ConfirmKeyGuard()
    fun armNow() = guard.arm(android.os.SystemClock.uptimeMillis())
}

/** Applies [guard] to every key event that reaches this subtree (see [ConfirmKeyGuard]). */
fun Modifier.swallowConfirmKeys(guard: ConfirmKeyGuard): Modifier = onPreviewKeyEvent { event ->
    guard.swallows(ConfirmKeyGuard.isConfirm(event.key), event.nativeKeyEvent.eventTime)
}
