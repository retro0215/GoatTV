package tv.own.owntv.features.rooms

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.network.StreamingHttpClient
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.player.DeliveredMessage
import tv.own.owntv.player.DeliveredReaction
import tv.own.owntv.rooms.ChannelResolutionResult
import tv.own.owntv.rooms.RoomAccessPolicy
import tv.own.owntv.rooms.RoomAccessState
import tv.own.owntv.rooms.RoomChannelResolver
import tv.own.owntv.rooms.RoomRealtimeSession
import tv.own.owntv.rooms.RoomRepository
import tv.own.owntv.rooms.SocialRoom
import tv.own.owntv.rooms.SupabaseRoomRepository
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

sealed interface SocialFeedItem {
    val id: String
    val createdAtMs: Long

    data class MessageItem(val delivered: DeliveredMessage) : SocialFeedItem {
        override val id: String get() = delivered.message.id
        override val createdAtMs: Long get() = delivered.message.createdAtMs
    }

    data class ReactionItem(val delivered: DeliveredReaction) : SocialFeedItem {
        override val id: String get() = delivered.reaction.id
        override val createdAtMs: Long get() = delivered.reaction.createdAtMs
    }
}

@Composable
fun RoomExperienceScreen(
    room: SocialRoom,
    modifier: Modifier = Modifier,
    roomRepository: RoomRepository = remember { SupabaseRoomRepository() },
    onBack: () -> Unit,
    onConnectPhone: () -> Unit = {},
) {
    androidx.activity.compose.BackHandler {
        onBack()
    }

    val colors = OwnTVTheme.colors

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val accessState = RoomAccessPolicy.evaluate(room.status, room.startsAt, room.endsAt, nowMs)

    if (accessState != RoomAccessState.OPEN) {
        Box(modifier.fillMaxSize().roundedPanel(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(48.dp)
            ) {
                Text(
                    text = room.name.uppercase(),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(16.dp))
                val msg = if (accessState == RoomAccessState.UPCOMING) {
                    room.startsAt?.let { "Room opens at " + java.text.SimpleDateFormat("EEE, MMM d • h:mm a", java.util.Locale.US).format(java.util.Date(it)) } ?: "Room is upcoming"
                } else {
                    "This room has ended or is closed"
                }
                Text(
                    text = msg,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFEF4444)
                )
                Spacer(Modifier.height(32.dp))
                OwnTVButton(
                    label = "Back",
                    onClick = onBack,
                    style = OwnTVButtonStyle.SECONDARY
                )
            }
        }
        return
    }

    val streamingHttp = koinInject<StreamingHttpClient>()
    val context = LocalContext.current
    val roomExoEngine = remember { RoomExoEngine(context, streamingHttp) }
    val channelDao = koinInject<ChannelDao>()
    val sourceDao = koinInject<tv.own.owntv.core.database.dao.SourceDao>()
    val settingsRepo = koinInject<tv.own.owntv.features.settings.data.SettingsRepository>()

    val realtimeSession = remember {
        RoomRealtimeSession(
            repository = roomRepository
        )
    }

    val messages by realtimeSession.deliveredMessages.collectAsStateWithLifecycle()
    val reactions by realtimeSession.deliveredReactions.collectAsStateWithLifecycle()

    var channelResolution by remember { mutableStateOf<ChannelResolutionResult?>(null) }
    val profileNames = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    val resolveName: (String, String?) -> String = { userId, explicitName ->
        if (!explicitName.isNullOrBlank()) {
            profileNames[userId] = explicitName
            explicitName
        } else {
            profileNames[userId] ?: run {
                profileNames[userId] = "Viewer"
                scope.launch {
                    val name = roomRepository.fetchDisplayName(userId)
                    if (!name.isNullOrBlank()) {
                        profileNames[userId] = name
                    }
                }
                "Viewer"
            }
        }
    }

    LaunchedEffect(room.id) {
        channelResolution = null
        val resolver = RoomChannelResolver(channelDao, sourceDao, settingsRepo)
        val result = resolver.resolve(room.channelReference)
        channelResolution = result
        if (result is ChannelResolutionResult.Resolved) {
            val ch = result.channel
            val headers = StreamHeaders.decode(ch.httpHeaders)
            roomExoEngine.play(
                url = ch.streamUrl,
                headersMap = headers,
                userAgent = null,
                drmConfig = ch.drmConfig
            )
        }
    }

    val socialFeed = remember(messages, reactions) {
        val msgItems = messages.map { SocialFeedItem.MessageItem(it) }
        val reactItems = reactions.map { SocialFeedItem.ReactionItem(it) }
        (msgItems + reactItems).sortedBy { it.createdAtMs }
    }

    val feedListState = rememberLazyListState()

    val isPhoneConnected by remember(room.id) {
        roomRepository.observeRoomPhonePresence(room.id)
    }.collectAsStateWithLifecycle(initialValue = false)

    val connectFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { connectFocus.requestFocus() }
    }

    LaunchedEffect(socialFeed.lastOrNull()?.id) {
        if (socialFeed.isNotEmpty()) {
            runCatching { feedListState.animateScrollToItem(socialFeed.lastIndex) }
        }
    }

    DisposableEffect(room.id) {
        realtimeSession.joinRoom(room.id)
        onDispose {
            realtimeSession.leaveRoom()
            roomExoEngine.release()
        }
    }

    Box(modifier.fillMaxSize().roundedPanel()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (accessState == RoomAccessState.OPEN) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .background(Color(0xFF22C55E).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF22C55E), RoundedCornerShape(50))
                                )
                                Text(
                                    text = "LIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF22C55E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    when (channelResolution) {
                        null -> {
                            Text(
                                text = stringResource(R.string.room_loading_channel),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        is ChannelResolutionResult.Resolved -> {
                            val resolvedCh = (channelResolution as ChannelResolutionResult.Resolved).channel
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!resolvedCh.displayLogoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = resolvedCh.displayLogoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    text = resolvedCh.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        is ChannelResolutionResult.Unavailable,
                        is ChannelResolutionResult.Ambiguous -> {
                            Text(
                                text = stringResource(R.string.room_channel_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFEF4444),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OwnTVButton(
                        label = if (isPhoneConnected) "Phone Connected" else "Connect Phone",
                        onClick = {
                            if (!isPhoneConnected) {
                                onConnectPhone()
                            }
                        },
                        style = OwnTVButtonStyle.SECONDARY,
                        modifier = Modifier.focusRequester(connectFocus)
                    )
                    OwnTVButton(
                        label = "Back",
                        onClick = onBack,
                        style = OwnTVButtonStyle.SECONDARY
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Main 2-panel Row (72% video, 28% chat)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Video Panel (~72%)
                Box(
                    modifier = Modifier
                        .weight(0.72f)
                        .fillMaxHeight()
                        .background(Color.Black, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    when (channelResolution) {
                        null -> {
                            Text(
                                text = stringResource(R.string.room_loading_feed),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant
                            )
                        }
                        is ChannelResolutionResult.Resolved -> {
                            androidx.compose.ui.viewinterop.AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    android.view.SurfaceView(ctx).apply {
                                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                                roomExoEngine.setSurface(holder.surface)
                                            }
                                            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                                roomExoEngine.setSurface(null)
                                            }
                                        })
                                    }
                                }
                            )
                        }
                        is ChannelResolutionResult.Unavailable,
                        is ChannelResolutionResult.Ambiguous -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.room_channel_unavailable),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.room_channel_unresolved_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Chat Panel (~28%)
                Column(
                    modifier = Modifier
                        .weight(0.28f)
                        .fillMaxHeight()
                        .background(colors.surfaceContainer.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "CHAT",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(12.dp))

                    if (socialFeed.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.room_no_messages),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = feedListState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(socialFeed, key = { it.id }) { item ->
                                when (item) {
                                    is SocialFeedItem.MessageItem -> {
                                        val msg = item.delivered.message
                                        val displayName = resolveName(msg.userId, msg.displayName)
                                        val initial = displayName.firstOrNull()?.uppercaseChar() ?: 'V'

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(colors.primary.copy(alpha = 0.25f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = initial.toString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary
                                                )
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = displayName,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = colors.primary
                                                    )
                                                    Text(
                                                        text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(msg.createdAtMs)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.onSurfaceVariant
                                                    )
                                                }
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = msg.body,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurface
                                                )
                                            }
                                        }
                                    }
                                    is SocialFeedItem.ReactionItem -> {
                                        val react = item.delivered.reaction
                                        val displayName = resolveName(react.userId, react.displayName)
                                        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(react.createdAtMs))

                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = react.emoji,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.room_reaction_format, displayName, react.emoji),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = timeStr,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceContainerHighest.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.room_chat_input_indicator),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
