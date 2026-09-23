package tv.own.owntv.features.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.network.StreamingHttpClient
import tv.own.owntv.rooms.ChannelResolutionResult
import tv.own.owntv.rooms.RoomAccessPolicy
import tv.own.owntv.rooms.RoomAccessState
import tv.own.owntv.rooms.RoomChannelResolver
import tv.own.owntv.rooms.RoomRepository
import tv.own.owntv.rooms.SocialRoom
import tv.own.owntv.rooms.SupabaseRoomRepository
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

@Composable
fun RoomExperienceScreen(
    room: SocialRoom,
    modifier: Modifier = Modifier,
    roomRepository: RoomRepository = remember { SupabaseRoomRepository() },
    onBack: () -> Unit,
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

    var channelResolution by remember { mutableStateOf<ChannelResolutionResult?>(null) }

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

    DisposableEffect(room.id) {
        onDispose {
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
                    Spacer(Modifier.height(4.dp))
                    when (channelResolution) {
                        null -> {
                            Text(
                                text = "Loading channel...",
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
                                text = "Channel unavailable",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFEF4444),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                OwnTVButton(
                    label = "Back",
                    onClick = onBack,
                    style = OwnTVButtonStyle.SECONDARY
                )
            }

            Spacer(Modifier.height(20.dp))

            // Full Video Player Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                when (channelResolution) {
                    null -> {
                        Text(
                            text = "Loading stream...",
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
                                text = "Channel unavailable",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Could not resolve matching channel in local catalog.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
