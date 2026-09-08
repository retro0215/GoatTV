package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.multiscreen.MultiscreenViewModel
import tv.own.owntv.player.ExoPreviewSurface
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

@Composable
fun SportsScreen(
    onOpenChannel: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onOpenMultiscreen: () -> Unit,
    onChildFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SportsViewModel = koinViewModel()
    val liveVm: LiveViewModel = koinViewModel()
    val msVm: MultiscreenViewModel = koinViewModel()
    val sections by vm.sportsSections.collectAsStateWithLifecycle()
    val previewChannel by liveVm.previewChannel.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val previewState by liveVm.previewEngine.state.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    val toast = tv.own.owntv.ui.components.rememberInAppToast()
    val multiscreenFullMessage = stringResource(R.string.content_multiscreen_full)
    var contextChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    androidx.compose.runtime.LaunchedEffect(previewChannel?.id, previewArmed) {
        if (!previewArmed) return@LaunchedEffect
        val ch = previewChannel ?: return@LaunchedEffect
        delay(400L) // 400ms Sports focus debounce
        liveVm.playPreview(ch)
    }

    val previewPlaying = previewState != LivePreviewEngine.State.ERROR &&
        previewState != LivePreviewEngine.State.IDLE
    val previewLoading = previewState == LivePreviewEngine.State.LOADING

    Box(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .focusGroup(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // --- STICKY / FIXED TOP AREA ---
            Text(
                text = stringResource(R.string.common_nav_sports).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                val currentChannel = previewChannel
                if (currentChannel != null) {
                    if (!currentChannel.displayLogoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = currentChannel.displayLogoUrl,
                            contentDescription = null,
                            modifier = Modifier.size(90.dp),
                        )
                    } else {
                        OwnTVIcon(
                            icon = OwnTVIcon.LIVE_TV,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    if (previewPlaying) {
                        ExoPreviewSurface(
                            engine = liveVm.previewEngine,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (previewLoading) {
                        OwnTVSpinner(sizeDp = 32)
                    }
                    // Overlay info bar at bottom of preview
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentChannel.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.primary)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.sports_live_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        OwnTVIcon(
                            icon = OwnTVIcon.LIVE_TV,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.content_focus_channel),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- SCROLLABLE LOWER AREA ---
            if (sections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.sports_empty_message),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    sections.forEach { sectionData ->
                        SportsSectionRow(
                            sectionData = sectionData,
                            onChannelClick = { channel ->
                                onOpenChannel(channel, sectionData.channels)
                            },
                            onChannelLongClick = { channel ->
                                contextChannel = channel
                            },
                            onChannelFocused = { channel ->
                                liveVm.onChannelFocused(channel)
                            },
                            onFocused = onChildFocused,
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        // Long press context menu modal
        contextChannel?.let { ch ->
            SportsContextMenu(
                channel = ch,
                isInMultiscreen = msVm.isInMultiscreen(ch.id),
                onToggleMultiscreen = {
                    if (msVm.isInMultiscreen(ch.id)) {
                        msVm.removeChannel(ch.id)
                    } else {
                        if (!msVm.addChannel(ch)) {
                            toast.show(multiscreenFullMessage)
                        }
                    }
                    contextChannel = null
                },
                onOpenMultiscreen = {
                    contextChannel = null
                    onOpenMultiscreen()
                },
                onDismiss = { contextChannel = null },
            )
        }
    }
}

@Composable
private fun SportsSectionRow(
    sectionData: SportsSectionData,
    onChannelClick: (ChannelEntity) -> Unit,
    onChannelLongClick: (ChannelEntity) -> Unit,
    onChannelFocused: (ChannelEntity) -> Unit,
    onFocused: () -> Unit,
) {
    val colors = OwnTVTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(sectionData.section.labelRes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) onFocused() },
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(sectionData.channels, key = { it.id }) { channel ->
                SportsChannelCard(
                    channel = channel,
                    onClick = { onChannelClick(channel) },
                    onLongClick = { onChannelLongClick(channel) },
                    onFocusChanged = { focused ->
                        if (focused) {
                            onChannelFocused(channel)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SportsChannelCard(
    channel: ChannelEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val colors = OwnTVTheme.colors

    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .width(180.dp)
            .aspectRatio(16f / 10f)
            .onFocusChanged { onFocusChanged(it.hasFocus) },
        shape = RoundedCornerShape(Dimens.CardCorner),
        focusedContainerColor = colors.surfaceContainerHigh,
        unfocusedContainerColor = colors.surfaceContainer,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val logo = channel.displayLogoUrl
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    OwnTVIcon(
                        icon = OwnTVIcon.LIVE_TV,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (focused) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SportsContextMenu(
    channel: ChannelEntity,
    isInMultiscreen: Boolean,
    onToggleMultiscreen: () -> Unit,
    onOpenMultiscreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim()
            .trapAllFocusExit()
            .focusGroup()
            .longPressMenuGuard(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.dialogPanel(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))

            // Add/Remove from Multiview
            FocusableSurface(
                onClick = onToggleMultiscreen,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                shape = RoundedCornerShape(12.dp),
                surface = GlassSurface.DIALOGS,
                contentAlignment = Alignment.CenterStart,
            ) { focused ->
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OwnTVIcon(
                        icon = OwnTVIcon.ADD,
                        tint = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (isInMultiscreen) stringResource(R.string.content_multiscreen_remove) else stringResource(R.string.content_multiscreen_add),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (focused) colors.onPrimaryContainer else colors.onSurface,
                    )
                }
            }

            // Open Multiview
            FocusableSurface(
                onClick = onOpenMultiscreen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                surface = GlassSurface.DIALOGS,
                contentAlignment = Alignment.CenterStart,
            ) { focused ->
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OwnTVIcon(
                        icon = OwnTVIcon.ZOOM,
                        tint = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.content_multiscreen),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (focused) colors.onPrimaryContainer else colors.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Close
            FocusableSurface(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                surface = GlassSurface.DIALOGS,
                contentAlignment = Alignment.CenterStart,
            ) { focused ->
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OwnTVIcon(
                        icon = OwnTVIcon.CLOSE,
                        tint = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.content_close),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (focused) colors.onPrimaryContainer else colors.onSurface,
                    )
                }
            }
        }
    }
}
