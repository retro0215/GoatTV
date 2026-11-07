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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.multiscreen.MultiscreenViewModel
import tv.own.owntv.features.sports.live.SportsEvent
import tv.own.owntv.features.sports.live.SportsEventDetails
import tv.own.owntv.features.sports.live.SportsEventRow
import tv.own.owntv.features.sports.live.SportsBrowseRestorer
import tv.own.owntv.features.sports.live.SportsEventOkHandlers
import tv.own.owntv.features.sports.live.SportsEventVideoPane
import tv.own.owntv.features.sports.live.SportsEventsViewModel
import tv.own.owntv.features.sports.live.SportsWhereToWatchDialog
import tv.own.owntv.features.sports.live.SportsWhereToWatchPurpose
import tv.own.owntv.features.sports.live.SportsRowFocusRestore
import tv.own.owntv.features.sports.live.rememberSportsRowState
import tv.own.owntv.features.sports.live.SportsGameCenterPreview
import tv.own.owntv.features.sports.live.SportsPreviewMode
import tv.own.owntv.features.sports.live.SportsLiveState
import tv.own.owntv.features.sports.live.SportsRowHeader
import tv.own.owntv.features.sports.live.SportsSlateLogic
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
    /** True when coming back from fullscreen / Multiscreen: restore scroll, row positions and focus. */
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
) {
    val vm: SportsViewModel = koinViewModel()
    val liveVm: LiveViewModel = koinViewModel()
    val msVm: MultiscreenViewModel = koinViewModel()
    val sections by vm.sportsSections.collectAsStateWithLifecycle()
    val previewChannel by liveVm.previewChannel.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val previewState by liveVm.previewEngine.state.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    val eventsVm: SportsEventsViewModel = koinViewModel()
    val eventsState by eventsVm.state.collectAsStateWithLifecycle()
    val eventsQuery by eventsVm.query.collectAsStateWithLifecycle()
    // Fullscreen and Multiscreen replace Sports in the shell, so nothing remembered here survives them;
    // the browsing position lives in the (activity-scoped) view model instead. Decided once per entry:
    // returning restores it, entering from the menu starts at the top as before.
    val currentOnRestored by androidx.compose.runtime.rememberUpdatedState(onRestored)
    val restorer = remember {
        if (!restoreFocus) eventsVm.browse.clear()
        SportsBrowseRestorer(eventsVm.browse, restoring = restoreFocus) { currentOnRestored() }
    }
    val scroll = remember { ScrollState(eventsVm.browse.scrollY) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { eventsVm.browse.scrollY = scroll.value }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!restorer.pending) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        // Content may have measured shorter on the first frame; re-apply the saved offset once, no animation.
        if (scroll.value != eventsVm.browse.scrollY) scroll.scrollTo(eventsVm.browse.scrollY)
        delay(600L)
        restorer.finish() // the focused row (if any) normally finishes first; never leave the shell waiting
    }
    val selectedEventId by eventsVm.selectedEventId.collectAsStateWithLifecycle()
    // The card that opened the details panel gets focus back when the panel closes.
    var eventReturnFocus by remember { mutableStateOf<androidx.compose.ui.focus.FocusRequester?>(null) }
    val whereToWatch by eventsVm.whereToWatch.collectAsStateWithLifecycle()
    var contextChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    val eventOverlayOpen = selectedEventId != null || whereToWatch != null || contextChannel != null
    // The long-press Multiscreen menu is an event modal too: no automatic preview behind it.
    androidx.compose.runtime.LaunchedEffect(contextChannel != null) { eventsVm.setEventMenuOpen(contextChannel != null) }
    androidx.compose.runtime.LaunchedEffect(eventOverlayOpen) {
        if (!eventOverlayOpen) {
            val target = eventReturnFocus ?: return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { } // let the panel leave composition first
            runCatching { target.requestFocus() }
            eventReturnFocus = null
        }
    }
    // One refresh loop, alive only while Sports is composed AND the app is started: leaving Sports or
    // backgrounding the app cancels it, so nothing polls off-screen and navigation never duplicates it.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { eventsVm.runWhileVisible() }
    }

    val toast = tv.own.owntv.ui.components.rememberInAppToast()
    val multiscreenFullMessage = stringResource(R.string.content_multiscreen_full)

    SportsPreviewDriver(eventsVm, liveVm, previewChannel, previewArmed)
    val onEventFocused = remember(eventsVm) { { event: SportsEvent -> eventsVm.onEventFocused(event) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val slateNow = (eventsState as? SportsLiveState.Content)?.slate

    // Event channel actions. Every play / add re-verifies the channel locally at action time.
    fun watchEventChannel(event: SportsEvent, channelId: Long) {
        scope.launch {
            val verified = eventsVm.verifiedChannels(event)
            val pick = verified.firstOrNull { it.channel.id == channelId } ?: return@launch
            // Explicit choice first (the pane resumes this feed when fullscreen returns), then close:
            // closing a modal with an explicit feed playing never restarts the automatic dwell.
            eventsVm.previewFeed(event, pick.channel)
            eventsVm.closeWhereToWatch()
            eventsVm.closeEvent()
            onOpenChannel(pick.channel, verified.map { it.channel })
        }
    }
    fun previewEventChannel(event: SportsEvent, channelId: Long) {
        scope.launch {
            val pick = eventsVm.reverify(event, channelId) ?: return@launch
            eventsVm.previewFeed(event, pick.channel) // intentional: plays now, no dwell
            eventsVm.closeWhereToWatch()
        }
    }
    fun addEventToMultiscreen(event: SportsEvent) {
        scope.launch {
            val verified = eventsVm.verifiedChannels(event)
            if (verified.size == 1) {
                contextChannel = verified.single().channel // the existing Multiscreen menu
            } else {
                eventsVm.openWhereToWatch(event, SportsWhereToWatchPurpose.MULTISCREEN) // 0 explains; 2+ pick first
            }
        }
    }
    val okGestures = remember(eventsVm) {
        if (!eventsVm.eventChannelsEnabled) {
            null
        } else {
            // An open event modal owns input: nothing fires through it from a background card.
            SportsEventOkHandlers(
                blocked = { eventsVm.isEventModalOpen },
                onSingle = { event, requester ->
                    eventReturnFocus = requester
                    eventsVm.openWhereToWatch(event, SportsWhereToWatchPurpose.WATCH)
                },
                onDouble = { event -> eventsVm.toggleEventVideo(event) },
                onLong = { event, requester ->
                    eventReturnFocus = requester
                    addEventToMultiscreen(event)
                },
            )
        }
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
                SportsPreviewPane(eventsVm, eventsState, liveVm, previewState) {
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
            }

            Spacer(Modifier.height(16.dp))

            // --- SCROLLABLE LOWER AREA ---
            // Sports Live event rows (DigitalOcean Sports API) first, then the existing sports channel
            // rows unchanged — they still drive the sticky preview, OK → fullscreen and long-press →
            // Multiscreen. Event cards never start playback (no channels are exposed in Phase C1).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SportsEventsArea(
                    state = eventsState,
                    query = eventsQuery,
                    onEventClick = { event, requester ->
                        eventReturnFocus = requester
                        eventsVm.openEvent(event.id)
                    },
                    onFocused = onChildFocused,
                    onEventFocused = onEventFocused,
                    restorer = restorer,
                    okGestures = okGestures,
                )

                if (sections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sports_empty_message),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else {
                    // Traditional live sports channels: a complementary browsing mode, kept below the
                    // event rows (focus → sticky preview, OK → fullscreen, long-press → Multiscreen).
                    SportsRowHeader(
                        title = stringResource(R.string.sports_channels_heading).uppercase(),
                        prominent = true,
                    )
                    sections.forEach { sectionData ->
                        key(sectionData.section) {
                            SportsSectionRow(
                                sectionData = sectionData,
                                onChannelClick = { channel ->
                                    onOpenChannel(channel, sectionData.channels)
                                },
                                onChannelLongClick = { channel ->
                                    contextChannel = channel
                                },
                                onChannelFocused = { channel ->
                                    eventsVm.onChannelFocused()
                                    liveVm.onChannelFocused(channel)
                                },
                                onFocused = onChildFocused,
                                restorer = restorer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }

        // Event details (OK on an event card) — information only in Phase C1.
        val selectedEvent = selectedEventId?.let { id -> (eventsState as? SportsLiveState.Content)?.slate?.eventsById?.get(id) }
        if (selectedEvent != null) {
            val selectedLeague = (eventsState as? SportsLiveState.Content)?.slate?.leagues?.firstOrNull { it.id == selectedEvent.leagueId }
            if (tv.own.owntv.features.sports.live.SportsEventPresentation.isFightCard(selectedEvent, selectedLeague)) {
                tv.own.owntv.features.sports.live.SportsFightDetails(event = selectedEvent, league = selectedLeague, onDismiss = { eventsVm.closeEvent() })
            } else {
                // Actions over LOCALLY verified channels only (Close alone while event channels are off).
                val verified by androidx.compose.runtime.produceState<List<tv.own.owntv.features.sports.live.ResolvedSportsChannel>>(emptyList(), selectedEvent.id) {
                    value = eventsVm.verifiedChannels(selectedEvent)
                }
                SportsEventDetails(
                    event = selectedEvent,
                    league = selectedLeague,
                    onDismiss = { eventsVm.closeEvent() },
                    actions = tv.own.owntv.features.sports.live.SportsEventPresentation.detailActions(verified.size, eventsVm.eventChannelsEnabled),
                    onAction = { action ->
                        when (action) {
                            tv.own.owntv.features.sports.live.SportsEventAction.WATCH ->
                                verified.firstOrNull()?.let { watchEventChannel(selectedEvent, it.channel.id) }
                            tv.own.owntv.features.sports.live.SportsEventAction.SELECT_CHANNEL -> {
                                eventsVm.openWhereToWatch(selectedEvent, SportsWhereToWatchPurpose.WATCH)
                                eventsVm.closeEvent()
                            }
                            tv.own.owntv.features.sports.live.SportsEventAction.ADD_TO_MULTISCREEN -> {
                                eventsVm.closeEvent()
                                addEventToMultiscreen(selectedEvent)
                            }
                            tv.own.owntv.features.sports.live.SportsEventAction.CLOSE -> eventsVm.closeEvent()
                        }
                    },
                )
            }
        }

        // Where to Watch (single OK on an event; long press picks the Multiscreen feed).
        whereToWatch?.let { wtw ->
            val wtwEvent = slateNow?.eventsById?.get(wtw.eventId)
            if (wtwEvent == null) {
                androidx.compose.runtime.LaunchedEffect(wtw.eventId) { eventsVm.closeWhereToWatch() }
            } else {
                SportsWhereToWatchDialog(
                    event = wtwEvent,
                    league = slateNow.leagues.firstOrNull { it.id == wtwEvent.leagueId },
                    state = wtw,
                    onPick = { channelId ->
                        if (wtw.purpose == SportsWhereToWatchPurpose.MULTISCREEN) {
                            scope.launch {
                                val pick = eventsVm.reverify(wtwEvent, channelId) ?: return@launch
                                contextChannel = pick.channel
                                eventsVm.closeWhereToWatch()
                            }
                        } else {
                            watchEventChannel(wtwEvent, channelId)
                        }
                    },
                    onPreview = { channelId -> previewEventChannel(wtwEvent, channelId) },
                    onGameDetails = {
                        eventsVm.openEvent(wtwEvent.id)
                        eventsVm.closeWhereToWatch()
                    },
                    onDismiss = { eventsVm.closeWhereToWatch() },
                )
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

/**
 * The ONE in-pane playback driver (one preview engine, one audio path):
 *  - channel mode: the existing channel preview after the 400ms focus debounce;
 *  - event Game Center: stops the pane video (crossing event cards stops nothing twice — no-op when idle);
 *  - event video: tunes the event's locally verified channel (its dwell already happened).
 * Reads the preview mode here, not in [SportsScreen], so a card focus change never recomposes the rows.
 */
@Composable
private fun SportsPreviewDriver(
    eventsVm: SportsEventsViewModel,
    liveVm: LiveViewModel,
    previewChannel: ChannelEntity?,
    previewArmed: Boolean,
) {
    val previewMode by eventsVm.previewMode.collectAsStateWithLifecycle()
    val channelKey = if (previewMode == SportsPreviewMode.ChannelVideo) previewChannel?.id else null
    androidx.compose.runtime.LaunchedEffect(previewMode, channelKey, previewArmed) {
        when (val mode = previewMode) {
            SportsPreviewMode.ChannelVideo -> {
                if (!previewArmed) return@LaunchedEffect
                val ch = previewChannel ?: return@LaunchedEffect
                delay(400L) // 400ms Sports focus debounce
                liveVm.playPreview(ch)
            }
            is SportsPreviewMode.EventGameCenter -> liveVm.stopPanePreview()
            is SportsPreviewMode.EventVideo -> liveVm.playPreview(mode.channel)
        }
    }
}

/**
 * Sticky preview content: the focused event's Game Center, otherwise [channelContent] (the existing
 * channel video preview). The only place that observes focus-driven preview state.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SportsPreviewPane(
    eventsVm: SportsEventsViewModel,
    eventsState: SportsLiveState,
    liveVm: LiveViewModel,
    engineState: LivePreviewEngine.State,
    channelContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val previewMode by eventsVm.previewMode.collectAsStateWithLifecycle()
    val gameCenterDetail by eventsVm.gameCenterDetail.collectAsStateWithLifecycle()
    val slate = (eventsState as? SportsLiveState.Content)?.slate
    val focusedEvent = when (val mode = previewMode) {
        is SportsPreviewMode.EventGameCenter -> mode.eventId
        is SportsPreviewMode.EventVideo -> mode.eventId
        SportsPreviewMode.ChannelVideo -> null
    }?.let { id -> slate?.eventsById?.get(id) }
    val videoMode = previewMode as? SportsPreviewMode.EventVideo
    if (focusedEvent != null && videoMode != null) {
        SportsEventVideoPane(
            event = focusedEvent,
            channel = videoMode.channel,
            engine = liveVm.previewEngine,
            engineState = engineState,
            onFailed = { eventsVm.onEventVideoFailed(focusedEvent.id) },
        )
    } else if (focusedEvent != null) {
        SportsGameCenterPreview(
            event = focusedEvent,
            league = slate?.leagues?.firstOrNull { it.id == focusedEvent.leagueId },
            detailState = gameCenterDetail,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        channelContent()
    }
}

/**
 * Popular Events + league rows from the Sports API. Loading / unavailable states render inline and
 * never displace the channel rows below; once a slate exists it stays visible through failed refreshes.
 */
@Composable
private fun SportsEventsArea(
    state: SportsLiveState,
    query: String,
    onEventClick: (SportsEvent, androidx.compose.ui.focus.FocusRequester) -> Unit,
    onFocused: () -> Unit,
    onEventFocused: (SportsEvent) -> Unit,
    restorer: SportsBrowseRestorer,
    okGestures: SportsEventOkHandlers?,
) {
    val colors = OwnTVTheme.colors
    when (state) {
        SportsLiveState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 4.dp),
        ) {
            OwnTVSpinner(sizeDp = 24)
            Text(stringResource(R.string.sports_events_loading), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        SportsLiveState.TemporarilyUnavailable -> Text(
            text = stringResource(R.string.sports_events_unavailable),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        is SportsLiveState.Content -> {
            val popularTitle = stringResource(R.string.sports_popular_events)
            // One row per grouped sport (Soccer = every enabled competition); titles localized here.
            val soccerTitle = stringResource(R.string.sports_section_soccer)
            val mmaTitle = stringResource(R.string.sports_section_mma)
            val boxingTitle = stringResource(R.string.sports_section_boxing)
            val eventSections = remember(state.slate, query, popularTitle, soccerTitle, mmaTitle, boxingTitle) {
                SportsSlateLogic.sections(state.slate, query, popularTitle, mapOf("soccer" to soccerTitle, "mma" to mmaTitle, "boxing" to boxingTitle))
            }
            val leaguesById = remember(state.slate.leagues) { state.slate.leagues.associateBy { it.id } }
            if (state.stale) {
                Text(
                    text = stringResource(R.string.sports_events_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (eventSections.isEmpty()) {
                Text(
                    text = stringResource(R.string.sports_events_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            eventSections.forEach { section ->
                // Keyed by section so an inserted/removed row never re-creates (and unfocuses) the others.
                key(section.key) {
                    SportsEventRow(
                        section = section,
                        leaguesById = leaguesById,
                        onEventClick = onEventClick,
                        onFocused = onFocused,
                        onEventFocused = onEventFocused,
                        restorer = restorer,
                        okGestures = okGestures,
                    )
                }
            }
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
    restorer: SportsBrowseRestorer,
) {
    val colors = OwnTVTheme.colors
    // Stable identity: the channel id (same key the LazyRow uses).
    val rowKey = "channels:" + sectionData.section.name
    val keys: List<Any> = remember(sectionData.channels) { sectionData.channels.map { it.id } }
    val listState = rememberSportsRowState(restorer, rowKey, keys)
    val requesters = remember(rowKey) { HashMap<Any, androidx.compose.ui.focus.FocusRequester>() }
    SportsRowFocusRestore(restorer, rowKey, keys, listState, requesters)

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
            state = listState,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) onFocused() },
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(sectionData.channels, key = { _, ch -> ch.id }) { index, channel ->
                val requester = remember(channel.id) { requesters.getOrPut(channel.id) { androidx.compose.ui.focus.FocusRequester() } }
                SportsChannelCard(
                    channel = channel,
                    onClick = { onChannelClick(channel) },
                    onLongClick = { onChannelLongClick(channel) },
                    onFocusChanged = { focused ->
                        if (focused) {
                            restorer.onItemFocused(rowKey, channel.id, index)
                            onChannelFocused(channel)
                        }
                    },
                    modifier = Modifier.focusRequester(requester),
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
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors

    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
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
