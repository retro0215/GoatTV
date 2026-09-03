package tv.own.owntv.features.shell.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.ReminderEntity
import tv.own.owntv.core.sync.work.ReminderScheduler
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.OwnTVTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BottomTvGuideOverlay(
    channel: ChannelEntity?,
    visible: Boolean,
    onDismiss: () -> Unit,
    loadScheduleWindow: suspend (ChannelEntity, Long, Long) -> List<EpgProgrammeEntity>,
    onChannelUp: (() -> Unit)? = null,   // Next channel (Down arrow)
    onChannelDown: (() -> Unit)? = null, // Previous channel (Up arrow)
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val context = LocalContext.current
    val formatTime = rememberSystemTimeFormatter()
    val dateFormat = remember { SimpleDateFormat("EEE MMM d", Locale.getDefault()) }
    val colors = OwnTVTheme.colors
    val reminderDao = koinInject<ReminderDao>()
    val scope = rememberCoroutineScope()

    var wallNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20000); wallNow = System.currentTimeMillis() } }

    var programmes by remember { mutableStateOf<List<EpgProgrammeEntity>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(0) }

    // Load 5-day future + 1-day past schedule for current channel & position on CURRENT live programme
    LaunchedEffect(channel?.id, wallNow) {
        val ch = channel ?: return@LaunchedEffect
        val from = wallNow - 24 * 3600_000L
        val to = wallNow + 5 * 24 * 3600_000L
        val list = runCatching {
            loadScheduleWindow(ch, from, to)
        }.getOrDefault(emptyList())
        programmes = list
        // Default focus on live/current programme (startMs <= wallNow && stopMs > wallNow) or nearest future (startMs > wallNow)
        val currentIdx = list.indexOfFirst { it.startMs <= wallNow && it.stopMs > wallNow }
        selectedIndex = if (currentIdx >= 0) {
            currentIdx
        } else {
            val futureIdx = list.indexOfFirst { it.startMs > wallNow }
            if (futureIdx >= 0) futureIdx else 0
        }
    }

    // 15-second inactivity timer (paused when reminder dialog is open)
    var lastInteractionTime by remember(channel?.id, visible) { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedProgForReminder by remember { mutableStateOf<EpgProgrammeEntity?>(null) }
    var hasReminderForSelected by remember { mutableStateOf(false) }
    var confirmationMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visible, lastInteractionTime, selectedProgForReminder) {
        if (visible && selectedProgForReminder == null) {
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
        if (selectedProgForReminder != null) {
            selectedProgForReminder = null
        } else {
            onDismiss()
        }
    }

    val rootFocus = remember { FocusRequester() }
    val setReminderFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(visible, channel?.id) {
        if (visible && selectedProgForReminder == null) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(selectedProgForReminder) {
        if (selectedProgForReminder != null) {
            kotlinx.coroutines.android.awaitFrame()
            runCatching { setReminderFocus.requestFocus() }
        } else {
            kotlinx.coroutines.android.awaitFrame()
            runCatching { rootFocus.requestFocus() }
            runCatching { listState.animateScrollToItem(selectedIndex) }
        }
    }

    LaunchedEffect(selectedIndex, programmes) {
        if (programmes.isNotEmpty() && selectedIndex in programmes.indices && selectedProgForReminder == null) {
            runCatching { listState.animateScrollToItem(selectedIndex) }
        }
    }

    LaunchedEffect(selectedProgForReminder, channel?.id) {
        val prog = selectedProgForReminder
        val ch = channel
        if (prog != null && ch != null) {
            hasReminderForSelected = reminderDao.exists(ch.id, prog.startMs)
        } else {
            hasReminderForSelected = false
        }
    }

    LaunchedEffect(confirmationMsg) {
        if (confirmationMsg != null) {
            delay(3500)
            confirmationMsg = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .focusProperties {
                canFocus = selectedProgForReminder == null
            }
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (selectedProgForReminder != null) {
                    if (e.type == KeyEventType.KeyDown) {
                        when (e.key) {
                            Key.Back -> {
                                selectedProgForReminder = null
                                true
                            }
                            Key.DirectionUp, Key.DirectionDown -> true
                            else -> false
                        }
                    } else false
                } else if (e.type == KeyEventType.KeyDown) {
                    lastInteractionTime = System.currentTimeMillis()
                    when (e.key) {
                        Key.DirectionUp -> {
                            // UP = Previous channel
                            onChannelDown?.invoke()
                            true
                        }
                        Key.DirectionDown -> {
                            // DOWN = Next channel
                            onChannelUp?.invoke()
                            true
                        }
                        Key.DirectionLeft -> {
                            // LEFT = Previous programme
                            if (programmes.isNotEmpty() && selectedIndex > 0) {
                                selectedIndex--
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            // RIGHT = Next programme
                            if (programmes.isNotEmpty() && selectedIndex < programmes.size - 1) {
                                selectedIndex++
                            }
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (programmes.isNotEmpty() && selectedIndex in programmes.indices) {
                                val p = programmes[selectedIndex]
                                if (p.stopMs > wallNow) {
                                    selectedProgForReminder = p
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color.Black.copy(alpha = 0.85f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Channel Info + Date/Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF004F46)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!channel?.logoUrl.isNullOrBlank()) {
                            AsyncImage(model = channel?.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                        } else {
                            Text((channel?.name ?: "").take(3).uppercase(), style = MaterialTheme.typography.labelMedium, color = Color(0xFF6FF8E4), fontWeight = FontWeight.Bold)
                        }
                    }
                    Column {
                        channel?.number?.let { num ->
                            Text(
                                stringResource(R.string.player_channel_number, num),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            channel?.name ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Text(
                    "NOW \u00b7 " + dateFormat.format(Date(wallNow)).uppercase() + " \u00b7 " + formatTime(wallNow),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Box(Modifier.height(1.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.12f)))

            // Timeline Programme Cards
            if (programmes.isEmpty()) {
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
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    itemsIndexed(programmes, key = { _, p -> p.id }) { idx, prog ->
                        val isSelected = idx == selectedIndex
                        val isLive = prog.startMs <= wallNow && prog.stopMs > wallNow
                        ProgrammeTimelineCard(
                            programme = prog,
                            isSelected = isSelected,
                            isLive = isLive,
                            wallNow = wallNow,
                            channelId = channel?.id ?: 0L,
                            reminderDao = reminderDao,
                            onClick = {
                                lastInteractionTime = System.currentTimeMillis()
                                selectedIndex = idx
                                if (prog.stopMs > wallNow) {
                                    selectedProgForReminder = prog
                                }
                            },
                        )
                    }
                }
            }
        }

        // True TV Modal Reminder Action Dialog with Exclusive Focus Ownership
        selectedProgForReminder?.let { prog ->
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
                        ReminderModalButton(
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
                                    selectedProgForReminder = null
                                }
                            },
                            focusRequester = setReminderFocus,
                            modifier = Modifier.weight(1f),
                        )
                        ReminderModalButton(
                            label = stringResource(R.string.common_cancel),
                            onClick = { selectedProgForReminder = null },
                            focusRequester = cancelFocus,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Confirmation Banner
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
private fun ReminderModalButton(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val bg = if (isFocused) colors.primary else Color(0xFF2A2A2A)
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.2f)
    val borderWidth = if (isFocused) 4.dp else 1.dp
    val textColor = if (isFocused) colors.onPrimary else Color.White

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .clickable { onClick() }
            .graphicsLayer {
                if (isFocused) {
                    scaleX = 1.05f
                    scaleY = 1.05f
                }
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProgrammeTimelineCard(
    programme: EpgProgrammeEntity,
    isSelected: Boolean,
    isLive: Boolean,
    wallNow: Long,
    channelId: Long,
    reminderDao: ReminderDao,
    onClick: () -> Unit,
) {
    val formatTime = rememberSystemTimeFormatter()
    val colors = OwnTVTheme.colors
    var hasReminder by remember { mutableStateOf(false) }
    LaunchedEffect(channelId, programme.startMs) {
        hasReminder = reminderDao.exists(channelId, programme.startMs)
    }

    val cardBg = when {
        isSelected -> colors.primaryContainer.copy(alpha = 0.85f)
        isLive -> colors.primary.copy(alpha = 0.2f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val borderColor = when {
        isSelected -> colors.primary
        isLive -> colors.primary.copy(alpha = 0.6f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val borderWidth = if (isSelected) 4.dp else 1.dp

    Column(
        modifier = Modifier
            .width(260.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isLive) {
                    Text(
                        stringResource(R.string.content_live_now).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
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
                formatTime(programme.startMs) + " - " + formatTime(programme.stopMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Text(
            programme.title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = if (isSelected || isLive) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (isLive) {
            val span = (programme.stopMs - programme.startMs).toFloat()
            if (span > 0f) {
                val progress = ((wallNow - programme.startMs) / span).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress).height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp)).background(colors.primary),
                    )
                }
            }
        }
    }
}
