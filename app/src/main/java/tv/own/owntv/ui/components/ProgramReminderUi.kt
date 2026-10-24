package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.sync.work.ProgramReminders

/** The active profile's reminder slots, live: every screen's bells read this one set. */
@Composable
fun rememberReminderSlots(reminders: ProgramReminders = koinInject()): State<Set<ProgramReminders.Key>> {
    val flow = remember(reminders) { reminders.observeScheduled() }
    return flow.collectAsState(initial = emptySet())
}

/** What to tell the viewer after a set/remove, resolved once per composition. */
class ReminderMessages internal constructor(
    private val setTemplate: String,
    private val removed: String,
    private val alreadySet: String,
    private val otherProfile: String,
    private val unavailable: String,
    private val ended: String,
) {
    fun forSet(result: ProgramReminders.SetResult, title: String): String = when (result) {
        ProgramReminders.SetResult.SET -> String.format(java.util.Locale.ROOT, setTemplate, title)
        ProgramReminders.SetResult.ALREADY_SET -> alreadySet
        ProgramReminders.SetResult.OTHER_PROFILE -> otherProfile
        ProgramReminders.SetResult.NOT_IN_PROFILE,
        ProgramReminders.SetResult.CHANNEL_MISSING -> unavailable
        ProgramReminders.SetResult.ENDED -> ended
    }

    fun forRemove(result: ProgramReminders.RemoveResult): String = when (result) {
        ProgramReminders.RemoveResult.REMOVED, ProgramReminders.RemoveResult.NOT_SET -> removed
        ProgramReminders.RemoveResult.OTHER_PROFILE -> otherProfile
    }
}

@Composable
fun rememberReminderMessages(): ReminderMessages {
    val setTemplate = stringResource(R.string.content_reminder_set_confirmation)
    val removed = stringResource(R.string.content_reminder_cancelled)
    val alreadySet = stringResource(R.string.content_reminder_already_set)
    val otherProfile = stringResource(R.string.content_reminder_other_profile)
    val unavailable = stringResource(R.string.content_channel_unavailable)
    val ended = stringResource(R.string.content_reminder_ended)
    return remember(setTemplate, removed, alreadySet, otherProfile, unavailable, ended) {
        ReminderMessages(setTemplate, removed, alreadySet, otherProfile, unavailable, ended)
    }
}
