package tv.own.owntv.features.pairing

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.rooms.BrandResolver
import tv.own.owntv.rooms.PairingStatus
import tv.own.owntv.rooms.RoomResult
import tv.own.owntv.rooms.SupabaseTvPairingService
import tv.own.owntv.rooms.TvPairingInfo
import tv.own.owntv.rooms.TvPairingService
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

sealed interface ConnectPhoneUiState {
    object Loading : ConnectPhoneUiState
    data class Active(val info: TvPairingInfo, val secondsRemaining: Long) : ConnectPhoneUiState
    data class Claimed(val info: TvPairingInfo) : ConnectPhoneUiState
    object Expired : ConnectPhoneUiState
    object Cancelled : ConnectPhoneUiState
    data class Error(val message: String) : ConnectPhoneUiState
}

@Composable
fun ConnectPhoneScreen(
    modifier: Modifier = Modifier,
    roomId: String? = null,
    pairingService: TvPairingService = remember { SupabaseTvPairingService() },
    onBack: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val actionFocus = remember { FocusRequester() }
    var uiState by remember { mutableStateOf<ConnectPhoneUiState>(ConnectPhoneUiState.Loading) }

    val brandId = remember { BrandResolver.resolveBrandId() }
    val brandName = remember {
        when (brandId) {
            "allaccess" -> "AllAccess"
            "fivestar" -> "5Star"
            "supreme" -> "Supreme"
            else -> "GoatTV"
        }
    }

    LaunchedEffect(roomId) {
        val result = pairingService.createPairingSession(roomId)
        when (result) {
            is RoomResult.Success -> {
                val info = result.data
                val remaining = maxOf(0L, (info.expiresAtMs - System.currentTimeMillis()) / 1000)
                uiState = ConnectPhoneUiState.Active(info, remaining)
            }
            is RoomResult.Error -> {
                uiState = ConnectPhoneUiState.Error(result.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        launch {
            pairingService.observePairingStatus().collectLatest { info ->
                if (info != null) {
                    when (info.status) {
                        PairingStatus.CLAIMED -> {
                            Log.d("RoomNav", "ROOM_NAV pairing-claimed")
                            uiState = ConnectPhoneUiState.Claimed(info)
                        }
                        PairingStatus.CANCELLED -> {
                            uiState = ConnectPhoneUiState.Cancelled
                        }
                        PairingStatus.EXPIRED -> {
                            uiState = ConnectPhoneUiState.Expired
                        }
                        PairingStatus.ACTIVE -> {
                            val current = uiState
                            if (current is ConnectPhoneUiState.Active) {
                                uiState = current.copy(info = info)
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            val current = uiState
            if (current is ConnectPhoneUiState.Active) {
                val now = System.currentTimeMillis()
                val remaining = maxOf(0L, (current.info.expiresAtMs - now) / 1000)
                if (remaining <= 0 || current.info.isExpired(now)) {
                    uiState = ConnectPhoneUiState.Expired
                } else {
                    uiState = current.copy(secondsRemaining = remaining)
                }
            }
        }
    }

    val handleBack: () -> Unit = {
        val current = uiState
        if (current is ConnectPhoneUiState.Active) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                pairingService.cancelPairing()
                pairingService.clear()
            }
        } else if (current is ConnectPhoneUiState.Expired || current is ConnectPhoneUiState.Error || current is ConnectPhoneUiState.Cancelled) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                pairingService.clear()
            }
        }
        onBack()
    }

    Box(modifier.fillMaxSize().roundedPanel()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 700.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "CONNECT YOUR PHONE",
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Link your mobile device to control $brandName Social Rooms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                when (val state = uiState) {
                    is ConnectPhoneUiState.Loading -> {
                        Text("Initializing secure pairing session...", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    is ConnectPhoneUiState.Active -> {
                        val qrBitmap = remember(state.info.qrToken) {
                            val url = state.info.qrUrl()
                            Log.d("ConnectPhone", "QR_DEBUG: tokenValid=${state.info.qrToken.isNotBlank()}, code=${state.info.pairingCode}")
                            CompanionLink.renderQr(url, sizePx = 512)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (qrBitmap != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Scan QR Code", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                                    Spacer(Modifier.height(8.dp))
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "Pairing QR Code",
                                        modifier = Modifier
                                            .size(180.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color.White)
                                            .padding(8.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                                Spacer(Modifier.width(48.dp))
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Or Enter Code", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = formatPairingCode(state.info.pairingCode),
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary,
                                    letterSpacing = 6.sp,
                                )
                                Spacer(Modifier.height(16.dp))
                                val mins = state.secondsRemaining / 60
                                val secs = state.secondsRemaining % 60
                                Text(
                                    text = String.format(java.util.Locale.US, "Code expires in: %02d:%02d", mins, secs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "Don't want to scan?\nGo to connect.goattv.net/pair on your phone\nand enter the 6-digit code above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(Modifier.height(28.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.onSurface.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text("Instructions:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text("1. Open connect.goattv.net/pair on your phone.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Text("2. Scan the QR code or enter the 6-digit code to connect.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }

                        Spacer(Modifier.height(24.dp))
                        OwnTVButton(
                            label = "Cancel",
                            onClick = handleBack,
                            style = OwnTVButtonStyle.SECONDARY,
                            modifier = Modifier.focusRequester(actionFocus)
                        )
                    }
                    is ConnectPhoneUiState.Claimed -> {
                        Text(
                            text = "PHONE CONNECTED",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF22C55E)
                        )
                        Spacer(Modifier.height(12.dp))
                        val detailText = if (state.info.roomId != null) {
                            "Connected to social room successfully."
                        } else {
                            "Your phone is connected."
                        }
                        Text(detailText, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        OwnTVButton(
                            label = "Done",
                            onClick = {
                                Log.d("RoomNav", "ROOM_NAV done")
                                onBack()
                            },
                            modifier = Modifier.focusRequester(actionFocus)
                        )
                    }
                    is ConnectPhoneUiState.Expired -> {
                        Text("Pairing code expired", style = MaterialTheme.typography.titleLarge, color = Color(0xFFEF4444))
                        Spacer(Modifier.height(16.dp))
                        OwnTVButton(
                            label = "Generate New Code",
                            onClick = {
                                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                    uiState = ConnectPhoneUiState.Loading
                                    val result = pairingService.createPairingSession(roomId)
                                    uiState = when (result) {
                                        is RoomResult.Success -> ConnectPhoneUiState.Active(result.data, maxOf(0L, (result.data.expiresAtMs - System.currentTimeMillis()) / 1000))
                                        is RoomResult.Error -> ConnectPhoneUiState.Error(result.message)
                                    }
                                }
                            },
                            modifier = Modifier.focusRequester(actionFocus)
                        )
                        Spacer(Modifier.height(12.dp))
                        OwnTVButton(label = "Back", onClick = handleBack, style = OwnTVButtonStyle.SECONDARY)
                    }
                    is ConnectPhoneUiState.Cancelled -> {
                        Text("Pairing cancelled", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        OwnTVButton(label = "Back", onClick = handleBack, modifier = Modifier.focusRequester(actionFocus))
                    }
                    is ConnectPhoneUiState.Error -> {
                        Text("Error: ${state.message}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEF4444), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        OwnTVButton(
                            label = "Retry",
                            onClick = {
                                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                    uiState = ConnectPhoneUiState.Loading
                                    val result = pairingService.createPairingSession(roomId)
                                    uiState = when (result) {
                                        is RoomResult.Success -> ConnectPhoneUiState.Active(result.data, maxOf(0L, (result.data.expiresAtMs - System.currentTimeMillis()) / 1000))
                                        is RoomResult.Error -> ConnectPhoneUiState.Error(result.message)
                                    }
                                }
                            },
                            modifier = Modifier.focusRequester(actionFocus)
                        )
                        Spacer(Modifier.height(12.dp))
                        OwnTVButton(label = "Back", onClick = handleBack, style = OwnTVButtonStyle.SECONDARY)
                    }
                }
            }
        }
    }
}

fun formatPairingCode(code: String): String {
    val clean = code.filter { it.isDigit() }
    if (clean.length == 6) {
        return "${clean.substring(0, 3)} ${clean.substring(3, 6)}"
    }
    return code
}
