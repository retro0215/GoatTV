package tv.own.owntv.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay

/**
 * Apply to the root of a menu/dialog opened by an OK press that may still be **held**. A held OK
 * auto-repeats, and those repeats land on the dialog the moment it appears — instantly "clicking" its
 * focused button before the user can choose. This swallows OK/Enter until the key is known to be up.
 *
 * Arming is time-based, not release-based. Waiting for a KeyUp looks correct and is not: the press
 * that opens the dialog is handled on **KeyDown** by the view underneath, so its KeyUp fires while the
 * popup is still being composed and never reaches this modifier. The guard then stayed disarmed and ate
 * the user's next, deliberate press — the long-standing "I have to press Watch from start twice" bug,
 * on both the Guide and the Live TV catch-up picker.
 *
 * A held key repeats far faster than [QUIET_MS], so any gap that long means the key is genuinely up,
 * whether or not the release event was ever delivered here.
 */
private const val QUIET_MS = 300L
private const val POLL_MS = 100L

fun Modifier.longPressMenuGuard(): Modifier = composed {
    var armed by remember { mutableStateOf(false) }
    // Initialize to current time so the guard doesn't arm immediately on composition.
    var lastOkEventAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    LaunchedEffect(Unit) {
        while (!armed) {
            delay(POLL_MS)
            // If no OK events (including repeats) have been seen for QUIET_MS, assume the key is up.
            if (System.currentTimeMillis() - lastOkEventAt > QUIET_MS) armed = true
        }
    }
    
    onPreviewKeyEvent { e ->
        if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
            lastOkEventAt = System.currentTimeMillis()
            val wasArmed = armed
            
            if (e.type == KeyEventType.KeyUp) {
                // The release event arrived — we are now safely armed for the next press.
                armed = true
            }
            
            // Consume the event if we were not armed at the start of this call.
            // This ensures the KeyUp that arms us is also consumed.
            !wasArmed
        } else {
            false
        }
    }
}
