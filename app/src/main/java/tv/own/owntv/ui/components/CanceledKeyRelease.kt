package tv.own.owntv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * 4.6.0: a release Android sends a window only because focus left it is not a press.
 *
 * When another window takes focus while a key is held (the reminder warning opening over a programme dialog,
 * the Guide or Multiscreen), Android's InputDispatcher sends the window losing focus an ACTION_UP with
 * FLAG_CANCELED for every key still down ("focus left window"). Compose turns it into a normal KeyUp, and a
 * clickable that saw the press runs onClick: holding OK on a dialog button while the warning opened pressed
 * that button. Release-driven handlers (the Guide's two-stage OK, Back that steps out, Multiscreen's tile move)
 * would act on it the same way.
 *
 * Only the activation and Back keys are dropped; other keys keep their release (held digit and channel-paging
 * state is cleared by it). Pure rule, so it can be tested on the JVM.
 */
object CanceledKeyRelease {

    private val GUARDED = setOf(
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        AndroidKeyEvent.KEYCODE_SPACE, // Compose clicks on Space too
        AndroidKeyEvent.KEYCODE_BUTTON_A,
        AndroidKeyEvent.KEYCODE_BACK,
        AndroidKeyEvent.KEYCODE_ESCAPE,
    )

    /** Whether a key event must be dropped before it reaches the window's controls. */
    fun ignore(action: Int, flags: Int, keyCode: Int): Boolean =
        action == AndroidKeyEvent.ACTION_UP && (flags and AndroidKeyEvent.FLAG_CANCELED) != 0 && keyCode in GUARDED
}

/**
 * Drops canceled OK and Back releases ([CanceledKeyRelease]) for everything inside. Put it at the top of a
 * window's content: a preview handler there runs before any focused control below it.
 */
fun Modifier.ignoreCanceledKeyReleases(): Modifier = onPreviewKeyEvent { e ->
    val native = e.nativeKeyEvent
    CanceledKeyRelease.ignore(native.action, native.flags, native.keyCode)
}
