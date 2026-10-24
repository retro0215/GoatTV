package tv.own.owntv.features.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ReminderEntity
import tv.own.owntv.core.sync.work.ProgramReminders
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Settings → Upcoming reminders: the active profile's programme reminders, soonest first. Selecting one
 * asks to cancel it, which removes the row and its WorkManager warning/tune jobs (shared
 * [ProgramReminders] action — the same one the guides use).
 */
@Composable
fun UpcomingRemindersScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    val context = LocalContext.current
    val programReminders = koinInject<ProgramReminders>()
    val scope = rememberCoroutineScope()
    val all by remember(programReminders) { programReminders.observeUpcoming() }.collectAsState(initial = emptyList())
    // Rows for programmes that already ended are cleared at the next start; never offer them here.
    val upcoming = all.filter { it.programEndMs > System.currentTimeMillis() }

    var confirmCancel by remember { mutableStateOf<ReminderEntity?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(upcoming.isEmpty()) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Header(stringResource(R.string.content_reminder_upcoming), onBack)
        Spacer(Modifier.height(12.dp))
        if (upcoming.isEmpty()) {
            Text(
                stringResource(R.string.content_reminder_upcoming_empty),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            upcoming.forEachIndexed { index, reminder ->
                val time = DateUtils.formatDateTime(
                    context, reminder.programStartMs,
                    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                )
                Row2(
                    icon = OwnTVIcon.EPG,
                    title = reminder.programTitle,
                    desc = stringResource(R.string.content_reminder_upcoming_item, reminder.channelName, time),
                    chip = stringResource(R.string.content_reminder_remove), primaryChip = false,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { confirmCancel = reminder },
                )
            }
        }
    }

    confirmCancel?.let { reminder ->
        ConfirmDialog(
            title = stringResource(R.string.content_reminder_remove),
            message = stringResource(R.string.content_reminder_cancel_message, reminder.programTitle),
            onConfirm = {
                confirmCancel = null
                scope.launch { programReminders.cancel(reminder.id) }
            },
            onDismiss = { confirmCancel = null },
            confirmLabel = stringResource(R.string.content_reminder_remove),
            dismissLabel = stringResource(R.string.content_reminder_keep),
        )
    }
}
