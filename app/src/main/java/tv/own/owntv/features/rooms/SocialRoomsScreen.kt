package tv.own.owntv.features.rooms

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.own.owntv.features.pairing.ConnectPhoneScreen
import tv.own.owntv.rooms.BrandResolver
import tv.own.owntv.rooms.RoomAccessPolicy
import tv.own.owntv.rooms.RoomAccessState
import tv.own.owntv.rooms.RoomRepository
import tv.own.owntv.rooms.RoomResult
import tv.own.owntv.rooms.SocialRoom
import tv.own.owntv.rooms.SupabaseRoomRepository
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

sealed interface SocialRoomsViewState {
    object Landing : SocialRoomsViewState
    data class RoomExperience(val room: SocialRoom) : SocialRoomsViewState
    data class Pairing(val roomId: String?, val returnToRoom: SocialRoom?) : SocialRoomsViewState
}

@Composable
fun SocialRoomsScreen(
    modifier: Modifier = Modifier,
    roomRepository: RoomRepository = remember { SupabaseRoomRepository() },
    initialRoomId: String? = null,
    onBack: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val brandId = remember { BrandResolver.resolveBrandId() }
    val brandName = remember {
        when (brandId) {
            "allaccess" -> "AllAccess"
            "fivestar" -> "5Star"
            "supreme" -> "Supreme"
            else -> "GoatTV"
        }
    }

    var viewState by remember { mutableStateOf<SocialRoomsViewState>(SocialRoomsViewState.Landing) }
    var rooms by remember { mutableStateOf<List<SocialRoom>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val loadRooms: () -> Unit = {
        scope.launch {
            isLoading = true
            loadError = null
            when (val result = roomRepository.fetchRoomsForBrand(brandId)) {
                is RoomResult.Success -> {
                    rooms = result.data
                    isLoading = false
                    if (!initialRoomId.isNullOrBlank()) {
                        val target = rooms.firstOrNull { it.id == initialRoomId } ?: SocialRoom(
                            id = initialRoomId,
                            name = "Event Room",
                            status = "open"
                        )
                        viewState = SocialRoomsViewState.RoomExperience(target)
                    }
                }
                is RoomResult.Error -> {
                    loadError = result.message
                    rooms = emptyList()
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(brandId) {
        loadRooms()
    }

    LaunchedEffect(viewState) {
        if (viewState is SocialRoomsViewState.Landing) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    when (val state = viewState) {
        is SocialRoomsViewState.RoomExperience -> {
            RoomExperienceScreen(
                room = state.room,
                roomRepository = roomRepository,
                onBack = { viewState = SocialRoomsViewState.Landing },
                onConnectPhone = {
                    viewState = SocialRoomsViewState.Pairing(roomId = state.room.id, returnToRoom = state.room)
                }
            )
        }
        is SocialRoomsViewState.Pairing -> {
            ConnectPhoneScreen(
                roomId = state.roomId,
                onBack = {
                    if (state.returnToRoom != null) {
                        Log.d("RoomNav", "ROOM_NAV opening-room-experience")
                        viewState = SocialRoomsViewState.RoomExperience(state.returnToRoom)
                    } else {
                        viewState = SocialRoomsViewState.Landing
                    }
                }
            )
        }
        is SocialRoomsViewState.Landing -> {
            Box(modifier.fillMaxSize().roundedPanel()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "SOCIAL ROOMS",
                        style = MaterialTheme.typography.headlineLarge,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "$brandName Social Rooms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))

                    if (noticeMessage != null) {
                        Text(
                            text = noticeMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    Text(
                        text = "AVAILABLE ROOMS",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(Modifier.height(16.dp))

                    when {
                        isLoading -> {
                            Text("Loading rooms...", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                        loadError != null -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Unable to Load Social Rooms", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFEF4444))
                                Spacer(Modifier.height(12.dp))
                                OwnTVButton(
                                    label = "Retry",
                                    onClick = loadRooms,
                                    modifier = Modifier.focusRequester(focusRequester)
                                )
                            }
                        }
                        rooms.isEmpty() -> {
                            Text("No Social Rooms Available", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                        else -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rooms.forEach { room ->
                                    val accessState = RoomAccessPolicy.evaluate(room.status, room.startsAt, room.endsAt, nowMs)
                                    val statusLabel = when (accessState) {
                                        RoomAccessState.UPCOMING -> {
                                            val timeStr = room.startsAt?.let { java.text.SimpleDateFormat("EEE • h:mm a", java.util.Locale.US).format(java.util.Date(it)) } ?: ""
                                            if (timeStr.isNotBlank()) "UPCOMING • Starts $timeStr" else "UPCOMING"
                                        }
                                        RoomAccessState.OPEN -> "LIVE"
                                        RoomAccessState.CLOSED -> "ENDED"
                                    }

                                    FocusableSurface(
                                        onClick = {
                                            if (accessState == RoomAccessState.OPEN) {
                                                viewState = SocialRoomsViewState.RoomExperience(room)
                                            } else {
                                                noticeMessage = room.startsAt?.let { "Room opens at " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(it)) } ?: "Room is not open"
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        unfocusedContainerColor = colors.surfaceContainer,
                                        focusedContainerColor = colors.surfaceContainerHigh,
                                    ) { focused ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = room.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (focused) colors.onSurface else colors.onSurface
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = statusLabel,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (accessState == RoomAccessState.OPEN) Color(0xFF22C55E) else colors.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = if (accessState == RoomAccessState.OPEN) "Select →" else "Locked 🔒",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (accessState == RoomAccessState.OPEN) colors.primary else colors.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                    OwnTVButton(
                        label = "Back",
                        onClick = onBack,
                        style = OwnTVButtonStyle.SECONDARY
                    )
                }
            }
        }
    }
}
