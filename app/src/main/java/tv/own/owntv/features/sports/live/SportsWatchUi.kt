package tv.own.owntv.features.sports.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.player.ExoPreviewSurface
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * WHERE TO WATCH (single OK on an event; long press picks the Multiscreen feed). Lists only channels
 * that passed LOCAL verification, in backend rank order; no Watch option exists when none did.
 * OK on a row acts on it ([onPick]: fullscreen / Multiscreen); in WATCH mode, Right reaches Preview.
 */
@Composable
fun SportsWhereToWatchDialog(
    event: SportsEvent,
    league: SportsLeague?,
    state: SportsWhereToWatchState,
    onPick: (Long) -> Unit,
    onPreview: (Long) -> Unit,
    onGameDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val firstFocus = remember { FocusRequester() }
    BackHandler { onDismiss() }
    val rows = remember(event, state) { state.channels?.let { SportsWatchPresentation.rows(event, it, state.selectedChannelId) } }
    LaunchedEffect(rows == null, rows.isNullOrEmpty()) { runCatching { firstFocus.requestFocus() } }
    val sep = stringResource(R.string.sports_list_separator)
    val matchup = if (event.isTeamEvent) {
        listOfNotNull(event.away?.shortName ?: event.away?.name, stringResource(R.string.sports_event_at), event.home?.shortName ?: event.home?.name).joinToString(" ")
    } else {
        event.title.orEmpty()
    }

    Box(
        modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.dialogPanel(width = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(
                    if (state.purpose == SportsWhereToWatchPurpose.MULTISCREEN) R.string.sports_watch_title_multiscreen else R.string.sports_watch_title,
                ).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = listOfNotNull(SportsEventPresentation.competitionLabel(league), matchup.takeIf { it.isNotBlank() }).joinToString(sep),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                EventStatusChip(event)
            }
            when {
                rows == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    OwnTVSpinner(sizeDp = 24)
                    Text(stringResource(R.string.sports_watch_checking), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                rows.isEmpty() -> Text(
                    text = stringResource(R.string.sports_watch_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEachIndexed { index, row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatchRowButton(
                                row = row,
                                onClick = { onPick(row.localChannelId) },
                                modifier = Modifier.weight(1f).then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            )
                            if (state.purpose == SportsWhereToWatchPurpose.WATCH) {
                                DialogButton(OwnTVIcon.VIDEO, stringResource(R.string.sports_watch_preview), onClick = { onPreview(row.localChannelId) })
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                if (state.purpose == SportsWhereToWatchPurpose.WATCH) {
                    DialogButton(OwnTVIcon.INFO, stringResource(R.string.sports_watch_game_details), onClick = onGameDetails)
                }
                DialogButton(
                    icon = OwnTVIcon.CLOSE,
                    label = stringResource(R.string.content_close),
                    onClick = onDismiss,
                    // The dialog always owns focus — also while channels are still being checked — so no
                    // OK press can reach the event card behind it.
                    modifier = if (rows.isNullOrEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun WatchRowButton(row: SportsWatchRow, onClick: () -> Unit, modifier: Modifier) {
    val colors = OwnTVTheme.colors
    val sep = stringResource(R.string.sports_list_separator)
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        surface = GlassSurface.DIALOGS,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OwnTVIcon(icon = OwnTVIcon.LIVE_TV, tint = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) colors.onPrimaryContainer else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(row.network, row.category).joinToString(sep)
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.uhd) Tag(stringResource(R.string.sports_watch_uhd), colors.primary, colors.onPrimary)
            if (row.selected) Tag(stringResource(R.string.sports_watch_selected), Color.White.copy(alpha = 0.16f), colors.onSurface)
        }
    }
}

@Composable
private fun Tag(text: String, background: Color, content: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DialogButton(icon: OwnTVIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.widthIn(min = 120.dp),
        shape = RoundedCornerShape(12.dp),
        surface = GlassSurface.DIALOGS,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon = icon, tint = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (focused) colors.onPrimaryContainer else colors.onSurface)
        }
    }
}

/** How long the tune may take to even start before the pane gives up and returns to Game Center. */
private const val EVENT_VIDEO_START_TIMEOUT_MS = 4000L

/** How long the scoreboard stays over the video once the picture is playing. */
private const val SCOREBOARD_VISIBLE_MS = 4000L

/**
 * Event video in the sticky pane: the ONE existing preview engine's surface (the playback driver tunes
 * it), a brief scoreboard from the event's own data, and the channel name. If the tune fails or never
 * starts, [onFailed] returns the pane to Game Center (no automatic retry).
 */
@Composable
fun SportsEventVideoPane(
    event: SportsEvent,
    channel: ChannelEntity,
    engine: LivePreviewEngine,
    engineState: LivePreviewEngine.State,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    val stateNow by rememberUpdatedState(engineState)
    val failed by rememberUpdatedState(onFailed)
    LaunchedEffect(event.id, channel.id) {
        // Only failures AFTER this tune started count (the engine may still report the last stream's state).
        var started = false
        launch {
            delay(EVENT_VIDEO_START_TIMEOUT_MS)
            if (!started) failed()
        }
        snapshotFlow { stateNow }.collect { s ->
            when (s) {
                LivePreviewEngine.State.LOADING, LivePreviewEngine.State.PLAYING -> started = true
                LivePreviewEngine.State.ERROR -> if (started) failed()
                LivePreviewEngine.State.IDLE -> Unit
            }
        }
    }
    // Up while the feed tunes, then fades a few seconds after the picture actually appears.
    var scoreboardVisible by remember(event.id, channel.id) { mutableStateOf(true) }
    val playing = engineState == LivePreviewEngine.State.PLAYING
    LaunchedEffect(event.id, channel.id, playing) {
        if (!playing) return@LaunchedEffect
        delay(SCOREBOARD_VISIBLE_MS)
        scoreboardVisible = false
    }
    val scoreboard = remember(event) { SportsWatchPresentation.scoreboard(event) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (engineState == LivePreviewEngine.State.PLAYING || engineState == LivePreviewEngine.State.LOADING) {
            ExoPreviewSurface(engine = engine, modifier = Modifier.fillMaxSize())
        }
        if (engineState != LivePreviewEngine.State.PLAYING) OwnTVSpinner(sizeDp = 32)
        if (scoreboard != null) {
            AnimatedVisibility(
                visible = scoreboardVisible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(400)),
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            ) {
                ScoreboardOverlay(scoreboard)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Pill(stringResource(R.string.sports_live_badge), LiveRed, Color.White)
        }
    }
}

@Composable
private fun ScoreboardOverlay(s: SportsScoreboardOverlay) {
    val unknown = stringResource(R.string.sports_score_unknown)
    val detail = if (s.halftime) stringResource(R.string.sports_status_halftime) else s.detail
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.sports_scoreboard_line, s.awayLabel, s.awayScore ?: unknown, s.homeLabel, s.homeScore ?: unknown),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CardText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(detail.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = CardTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
