package tv.own.owntv.features.shell.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.ReminderDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ReminderEntity
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.sync.work.ReminderScheduler
import tv.own.owntv.features.live.EpgNowNext
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.OwnTVTheme

@Composable
fun LiveTvGuideOverlay(
    channel: ChannelEntity?,
    epg: EpgNowNext?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onChannelUp: (() -> Unit)? = null,
    onChannelDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val context = LocalContext.current
    val formatTime = rememberSystemTimeFormatter()
    val colors = OwnTVTheme.colors
    val reminderDao = koinInject<ReminderDao>()
    val scope = rememberCoroutineScope()

    var wallNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20000); wallNow = System.currentTimeMillis() } }

    var selectedProg by remember { mutableStateOf<XtEpgEntry?>(null) }
    var hasReminderForSelected by remember { mutableStateOf(false) }
    var confirmationMsg by remember { mutableStateOf<String?>(null) }

    // 15-second inactivity timer (paused when reminder dialog is open)
    var lastInteractionTime by remember(channel?.id, visible) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible, lastInteractionTime, selectedProg) {
        if (visible && selectedProg == null) {
            while (true) {
                delay(1000)
                if (System.currentTimeMillis() - lastInteractionTime > 15_000L) {
                    onDismiss()
                    break
                }
            }
        }
    }

    BackHandler(enabled = visible) {
        if (selectedProg != null) {
            selectedProg = null
        } else {
            onDismiss()
        }
    }

    val rootFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }

    LaunchedEffect(visible, channel?.id) {
        if (visible) {
            runCatching { rootFocus.requestFocus() }
            runCatching { nextFocus.requestFocus() }
        }
    }

    LaunchedEffect(selectedProg) {
        if (selectedProg != null) {
            runCatching { buttonFocus.requestFocus() }
        }
    }

    LaunchedEffect(confirmationMsg) {
        if (confirmationMsg != null) {
            delay(3500)
            confirmationMsg = null
        }
    }

    LaunchedEffect(selectedProg, channel?.id) {
        val prog = selectedProg
        val ch = channel
        if (prog != null && ch != null) {
            hasReminderForSelected = reminderDao.exists(ch.id, prog.startMs)
        } else {
            hasReminderForSelected = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (selectedProg != null) {
                    if (e.type == KeyEventType.KeyDown) {
                        when (e.key) {
                            Key.Back -> {
                                selectedProg = null
                                true
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                true
                            }
                            else -> false
                        }
                    } else false
                } else if (e.type == KeyEventType.KeyDown) {
                    lastInteractionTime = System.currentTimeMillis()
                    when (e.key) {
                        Key.DirectionLeft, Key.ChannelDown, Key.MediaPrevious -> {
                            onChannelDown?.invoke()
                            true
                        }
                        Key.DirectionRight, Key.ChannelUp, Key.MediaNext -> {
                            onChannelUp?.invoke()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .fillMaxHeight()
                .padding(vertical = 40.dp, horizontal = 28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .padding(24.dp)
                .onFocusChanged { if (it.hasFocus) lastInteractionTime = System.currentTimeMillis() },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Channel Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF004F46)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!channel?.logoUrl.isNullOrBlank()) {
                        AsyncImage(model = channel?.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else {
                        Text((channel?.name ?: stringResource(R.string.content_downloads_unknown_size)).take(3).uppercase(), style = MaterialTheme.typography.titleMedium, color = Color(0xFF6FF8E4), fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    channel?.number?.let { num ->
                        Text(
                            stringResource(R.string.player_channel_number, num),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    channel?.name?.let { channelName ->
                        Text(
                            channelName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Box(Modifier.height(1.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.12f)))

            // Schedule Content
            if (epg == null || (epg.now == null && epg.next == null && epg.upcoming.isEmpty())) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.home_guide_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // NOW
                    epg.now?.let { current ->
                        item {
                            ProgrammeItemRow(
                                title = current.title,
                                description = current.description,
                                startMs = current.startMs,
                                stopMs = current.stopMs,
                                label = stringResource(R.string.content_live_now),
                                labelColor = colors.primary,
                                isNow = true,
                                wallNow = wallNow,
                                channelId = channel?.id ?: 0L,
                                reminderDao = reminderDao,
                                onClick = { lastInteractionTime = System.currentTimeMillis(); selectedProg = current },
                            )
                        }
                    }

                    // NEXT (Default focus target)
                    epg.next?.let { nextProg ->
                        item {
                            ProgrammeItemRow(
                                title = nextProg.title,
                                description = nextProg.description,
                                startMs = nextProg.startMs,
                                stopMs = nextProg.stopMs,
                                label = stringResource(R.string.content_live_next),
                                labelColor = Color.White.copy(alpha = 0.5f),
                                isNow = false,
                                wallNow = wallNow,
                                channelId = channel?.id ?: 0L,
                                reminderDao = reminderDao,
                                modifier = Modifier.focusRequester(nextFocus),
                                onClick = { lastInteractionTime = System.currentTimeMillis(); selectedProg = nextProg },
                            )
                        }
                    }

                    // UPCOMING
                    items(epg.upcoming.take(5), key = { it.startMs }) { prog ->
                        ProgrammeItemRow(
                            title = prog.title,
                            description = prog.description,
                            startMs = prog.startMs,
                            stopMs = prog.stopMs,
                            label = null,
                            labelColor = Color.Transparent,
                            isNow = false,
                            wallNow = wallNow,
                            channelId = channel?.id ?: 0L,
                            reminderDao = reminderDao,
                            onClick = { lastInteractionTime = System.currentTimeMillis(); selectedProg = prog },
                        )
                    }
                }
            }
        }

        // True TV Modal Reminder Action Dialog with Scrim
        selectedProg?.let { prog ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(400.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (hasReminderForSelected) stringResource(R.string.content_reminder_already_set) else prog.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (!hasReminderForSelected) {
                        Text(
                            stringResource(R.string.content_live_time_range, formatTime(prog.startMs), formatTime(prog.stopMs)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val cancelledMsg = stringResource(R.string.content_reminder_cancelled)
                        val setMsg = stringResource(R.string.content_reminder_set_confirmation, prog.title)
                        OwnTVButton(
                            label = if (hasReminderForSelected) stringResource(R.string.content_reminder_cancel) else stringResource(R.string.content_reminder_set),
                            onClick = {
                                scope.launch {
                                    val ch = channel ?: return@launch
                                    if (hasReminderForSelected) {
                                        reminderDao.delete(ch.id, prog.startMs)
                                        ReminderScheduler.cancel(context, ch.id, prog.startMs)
                                        confirmationMsg = cancelledMsg
                                    } else {
                                        val reminder = ReminderEntity(
                                            profileId = 1L,
                                            sourceId = ch.sourceId,
                                            channelId = ch.id,
                                            channelName = ch.name,
                                            channelNumber = ch.number,
                                            programId = null,
                                            programTitle = prog.title,
                                            programStartMs = prog.startMs,
                                            programEndMs = prog.stopMs,
                                            triggerAtMs = prog.startMs - (60_000L), // 1 min before
                                            createdAtMs = System.currentTimeMillis(),
                                        )
                                        val insertedId = reminderDao.insert(reminder)
                                        val saved = reminderDao.getAll().find { it.channelId == ch.id && it.programStartMs == prog.startMs } ?: reminder.copy(id = insertedId)
                                        ReminderScheduler.schedule(context, saved)
                                        confirmationMsg = setMsg
                                    }
                                    hasReminderForSelected = reminderDao.exists(ch.id, prog.startMs)
                                    selectedProg = null
                                }
                            },
                            modifier = Modifier.weight(1f).focusRequester(buttonFocus),
                        )
                        OwnTVButton(
                            label = stringResource(R.string.common_cancel),
                            onClick = { selectedProg = null },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Immediate Confirmation Toast/Banner
        confirmationMsg?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier = Modifier
                        .width(420.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF004F46).copy(alpha = 0.95f))
                        .border(1.dp, Color(0xFF6FF8E4), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeItemRow(
    title: String,
    description: String?,
    startMs: Long,
    stopMs: Long,
    label: String?,
    labelColor: Color,
    isNow: Boolean,
    wallNow: Long,
    channelId: Long,
    reminderDao: ReminderDao,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val formatTime = rememberSystemTimeFormatter()
    var hasReminder by remember { mutableStateOf(false) }
    LaunchedEffect(channelId, startMs) {
        hasReminder = reminderDao.exists(channelId, startMs)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isNow) OwnTVTheme.colors.primary.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (isNow) OwnTVTheme.colors.primary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (label != null) {
                    Text(
                        label.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                        color = labelColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (hasReminder) {
                    Text(
                        "🔔 " + stringResource(R.string.content_reminder_indicator),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF6FF8E4),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                stringResource(R.string.content_live_time_range, formatTime(startMs), formatTime(stopMs)),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Text(
            title,
            style = if (isNow) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = if (isNow) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        description?.takeIf { it.isNotBlank() }?.let { desc ->
            if (isNow) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isNow) {
            val span = (stopMs - startMs).toFloat()
            if (span > 0f) {
                val progress = ((wallNow - startMs) / span).coerceIn(0f, 1f)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress).height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp)).background(OwnTVTheme.colors.primary),
                    )
                }
            }
        }
    }
}
