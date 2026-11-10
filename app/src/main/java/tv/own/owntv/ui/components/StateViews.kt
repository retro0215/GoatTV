package tv.own.owntv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.OwnTVTheme
import kotlinx.coroutines.delay

/**
 * Lightweight indeterminate spinner drawn with Canvas (no Material dependency).
 *
 * It turns in [SPINNER_STEPS] steps rather than every frame. On a TV each frame redraws the whole window,
 * so a spinner animated per frame kept the render thread busy for as long as it showed — about a quarter
 * of the app's CPU during a catalogue import, which is also when the sync pill's spinner runs. Only the
 * draw phase reads the step, so a step costs one redraw and no recomposition. Same 0.9 s per turn.
 * Reference: upstream OwnTV 55678114 (render thread 25.6 % -> 13.6 % during a large import).
 */
@Composable
fun OwnTVSpinner(
    modifier: Modifier = Modifier,
    sizeDp: Int = 44,
    color: Color = OwnTVTheme.colors.accent,
) {
    val step = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_STEP_MS)
            step.intValue = nextSpinnerStep(step.intValue)
        }
    }
    Canvas(modifier = modifier.size(sizeDp.dp)) {
        val d = size.minDimension
        val stroke = d * 0.11f
        drawArc(
            color = color,
            startAngle = spinnerAngle(step.intValue),
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(d - stroke, d - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** One turn of [OwnTVSpinner], as before it was stepped. */
internal const val SPINNER_TURN_MS = 900L

/** Positions per turn: 16 is about 18 redraws a second instead of the display's 60. */
internal const val SPINNER_STEPS = 16

/** Time between two spinner positions. */
internal const val SPINNER_STEP_MS = SPINNER_TURN_MS / SPINNER_STEPS

/** The spinner position after [step]; wraps after one turn. */
internal fun nextSpinnerStep(step: Int): Int = (step + 1) % SPINNER_STEPS

/** Start angle of the arc at [step]. */
internal fun spinnerAngle(step: Int): Float = step * (360f / SPINNER_STEPS)

/** Centered loading state: spinner + message (e.g. "Importing channels…"). */
@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OwnTVSpinner()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = OwnTVTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Centered error state: title + message + an optional Retry action. Used for failed loads/syncs. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    val resolvedTitle = title ?: stringResource(R.string.common_something_went_wrong)
    val resolvedRetryLabel = retryLabel ?: stringResource(R.string.common_retry)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = resolvedTitle, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            OwnTVButton(label = resolvedRetryLabel, onClick = onRetry, icon = OwnTVIcon.HISTORY)
        }
    }
}

/** Centered empty state: icon + title + message + optional call-to-action button. */
@Composable
fun EmptyState(
    icon: OwnTVIcon,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OwnTVIcon(icon = icon, tint = colors.textSecondary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            OwnTVButton(label = actionLabel, onClick = onAction, icon = OwnTVIcon.MENU)
        }
    }
}
