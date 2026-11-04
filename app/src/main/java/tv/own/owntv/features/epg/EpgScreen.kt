package tv.own.owntv.features.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.ChannelGenre
import tv.own.owntv.ui.components.ErrorState
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.SearchBar
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.features.epg.GuideGridDefaults
import tv.own.owntv.features.epg.ProgrammeDetailDialog
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

@Composable
private fun guideSortLabel(sort: SettingsRepository.GuideSort): String = stringResource(
    when (sort) {
        SettingsRepository.GuideSort.ALPHA -> R.string.content_epg_sort_alpha
        SettingsRepository.GuideSort.PROVIDER -> R.string.content_epg_sort_provider
        SettingsRepository.GuideSort.LIVE_TV -> R.string.content_epg_sort_live
        SettingsRepository.GuideSort.CATCHUP -> R.string.content_epg_sort_catchup
        SettingsRepository.GuideSort.FAVORITES -> R.string.content_epg_sort_favorites
    },
)

@Composable
private fun epgMessageText(message: EpgMessage): String = when (message) {
    EpgMessage.CreateProfile -> stringResource(R.string.content_epg_create_profile)
    EpgMessage.AddPlaylist -> stringResource(R.string.content_epg_add_playlist)
    is EpgMessage.NoChannelsForQuery -> stringResource(R.string.content_epg_no_channels_query, message.query)
    EpgMessage.MismatchedIds -> stringResource(R.string.content_epg_mismatched_ids)
}

@Composable
private fun epgStatsText(stats: EpgStats): String {
    val catchup = if (stats.catchupChannels > 0) {
        pluralStringResource(R.plurals.content_epg_catchup_count, stats.catchupChannels, stats.catchupChannels)
    } else {
        stringResource(R.string.content_epg_no_catchup_channels)
    }
    return if (stats.programmes > 0) {
        stringResource(
            R.string.content_epg_stats_loaded,
            pluralStringResource(R.plurals.content_epg_stats_channels, stats.guideChannels, stats.guideChannels),
            pluralStringResource(R.plurals.content_epg_stats_programmes, stats.programmes, stats.programmes),
            catchup,
        )
    } else if (stats.catchupChannels > 0) {
        pluralStringResource(
            R.plurals.content_epg_stats_catchup_available,
            stats.catchupChannels,
            stats.catchupChannels,
        )
    } else {
        stringResource(R.string.content_epg_stats_no_catchup)
    }
}

@Composable
private fun epgMatchSummaryText(summary: EpgMatchSummary): String = when (summary) {
    EpgMatchSummary.CatchupUnavailable -> stringResource(R.string.content_epg_catchup_unavailable)
    EpgMatchSummary.MatchedNoProgrammes -> stringResource(R.string.content_epg_matched_no_programmes)
    EpgMatchSummary.AddPlaylist -> stringResource(R.string.content_epg_add_playlist_first)
    EpgMatchSummary.NoData -> stringResource(R.string.content_epg_no_match_data)
    EpgMatchSummary.AllMatched -> stringResource(R.string.content_epg_all_matched)
    is EpgMatchSummary.NoMatch -> stringResource(R.string.content_epg_no_match, summary.channelName)
    is EpgMatchSummary.AutoMatched -> if (summary.review > 0) {
        stringResource(
            R.string.content_epg_auto_matched,
            pluralStringResource(R.plurals.content_epg_auto_matched_applied, summary.applied, summary.applied),
            pluralStringResource(R.plurals.content_epg_auto_matched_review, summary.review, summary.review),
        )
    } else {
        pluralStringResource(
            R.plurals.content_epg_auto_matched_no_review,
            summary.applied,
            summary.applied,
        )
    }
}

/**
 * The full TV Guide: a frozen channel column (logo, number, name) beside a horizontal programme
 * timeline that every row shares, with the current time marked and a details panel for the highlighted
 * programme. The grid is ONE focus target driven by a [GuideNav.Cursor]: Up/Down change channel, Left/
 * Right change programme, OK opens the programme (it never tunes by itself), and holding OK (or Menu)
 * opens the channel's options. Rows still read the shared EpgViewModel cache — nothing reloads on a move.
 */
@Composable
fun EpgScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFullscreen: () -> Unit = {},
    onAddEpg: () -> Unit = {},
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onContentScrolled: (Boolean) -> Unit = {},
    /** Required: every live tune in the app goes through the one shared path in LiveViewModel, so a
     *  channel gets the same Prefer HLS handling, ExoPlayer→mpv ladder, per-channel engine pin and
     *  external-player routing however the user reached it. */
    onPlayChannel: (channel: ChannelEntity, channels: List<ChannelEntity>) -> Unit,
    /** "Watch from start" on a catch-up programme. Required for the same reason, and additionally so the
     *  archive is tracked as catch-up playback (which decides what engine toggle the player HUD offers). */
    onPlayCatchup: (channel: ChannelEntity, programme: EpgProgrammeEntity) -> Unit,
    /** The channel Live TV is on, if any: the guide opens on its row. */
    watchingChannelId: Long? = null,
    /** Phase 4: the channel still playing in the preview (from Live TV), for its caption. */
    previewChannel: ChannelEntity? = null,
    /** Phase 4: the playing channel's picture, hosted by the shell's shared player (never a second one).
     *  Null: nothing is playing here, and the preview says so — browsing never starts a channel. */
    previewVideo: (@Composable (Modifier) -> Unit)? = null,
) {
    val vm: EpgViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val matching by vm.matching.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val matchSummary by vm.matchSummary.collectAsStateWithLifecycle()
    val sortGuide by vm.sortGuide.collectAsStateWithLifecycle()
    val categoryFilter by vm.categoryFilter.collectAsStateWithLifecycle()
    val guideCategories by vm.guideCategories.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteChannelIds.collectAsStateWithLifecycle()
    val catchupPlayer by vm.catchupPlayer.collectAsStateWithLifecycle()
    val showAllChannels by vm.showAllChannels.collectAsStateWithLifecycle()
    val cacheRevision by vm.cacheRevision.collectAsStateWithLifecycle()
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val colors = OwnTVTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // One text measurer for every row, so a title repeated down the grid is measured once.
    // Bounded: a few screens of cell titles. Titles of clipped cells snap to width steps (GuideCellLayout),
    // so scrolling reuses these instead of filling the cache with one layout per pixel offset.
    val stripMeasurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 256)
    // Channel numbers are identifiers, not quantities: no thousands separator ("5001", not "5,001").
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    val channelNumberFormat = remember(locale) { java.text.NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false } }
    val hScroll = rememberScrollState()
    // Default prefetch (the next row in the scroll direction). Phase 3C kept two row-heights either side
    // composed, which moved ~27 ms row builds into idle frames and raised jank (57-77 % on the onn); rows
    // are now a single Canvas, cheap to build, and their data is prepared off the main thread (rowStore).
    val rowListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rowStridePx = with(density) { (GuideGridDefaults.RowHeight + GuideGridDefaults.RowGap).roundToPx() }
    val chromeScrollThresholdPx = with(density) { 8.dp.roundToPx() }
    val contentScrolled by remember(hScroll, rowListState, chromeScrollThresholdPx) {
        androidx.compose.runtime.derivedStateOf {
            hScroll.value > chromeScrollThresholdPx ||
                rowListState.firstVisibleItemIndex > 0 ||
                rowListState.firstVisibleItemScrollOffset > chromeScrollThresholdPx
        }
    }
    LaunchedEffect(contentScrolled) { onContentScrolled(contentScrolled) }
    var detail by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    // Reminders: the shared action every guide uses, and the active profile's slots for the bells.
    val programReminders = org.koin.compose.koinInject<tv.own.owntv.core.sync.work.ProgramReminders>()
    val reminderSlotsState = tv.own.owntv.ui.components.rememberReminderSlots(programReminders)
    val reminderSlots by reminderSlotsState
    val reminderMessages = tv.own.owntv.ui.components.rememberReminderMessages()
    var reminderNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(detail) { reminderNote = null }
    var matchingChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var matchChooser by remember { mutableStateOf<ChannelEntity?>(null) }
    var offsetChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    // The clock drives the header time, the now marker and what counts as on air; the window itself
    // only moves on a reload. A 30-second tick is plenty for a minute-resolution guide.
    val nowState = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by nowState
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }

    // No BackHandler here: the Guide is a top-level section, so Back is the shell's job (content →
    // sidebar → exit dialog). Dialogs register their own, which win while they are open.
    LaunchedEffect(Unit) { vm.onGuideOpened(); vm.load() } // reuses the list when nothing changed (GuideLoad)
    // Lets the shared ViewModel skip data-change reloads while the guide is not on screen.
    androidx.compose.runtime.DisposableEffect(Unit) {
        vm.setScreenVisible(true)
        onDispose { vm.setScreenVisible(false) }
    }

    // Rows on screen: channels whose loaded window is empty are hidden unless "Show all channels".
    val rows = remember(state.channels, showAllChannels, cacheRevision) {
        GuideNav.visibleChannels(state.channels, showAllChannels) { vm.hasGuideInWindow(it) }
    }
    val hiddenEmpty = !showAllChannels && rows.size < state.channels.size

    // ---- Row data (off the main thread), logos, the shared renderer ----------------------------------
    val rowStore = remember { GuideRowStore() }
    // Cell texts measured off the UI thread for the rows in view (bounded; see GuideRowRenderer.prepareTexts).
    val textStore = remember { KeyedStates<Long, GuideRowTexts>(ROW_TEXT_ROWS) }
    // The guide's one text thread: text layout for rows and the details panel runs here, in order.
    val textDispatcher = remember { kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val logoSizePx = with(density) { 48.dp.roundToPx() }
    val logoCache = remember(logoSizePx) { GuideLogoCache(context.applicationContext, logoSizePx) }
    val rowRenderer = rememberGuideRowRenderer(stripMeasurer)
    // Category dot colours by category id, once per category list (was a keyword match per row build).
    val categoryDots = remember(guideCategories) {
        guideCategories.mapNotNull { c -> c.categoryId?.let { id -> ChannelGenre.dotFor(c.name)?.let { id to it } } }.toMap()
    }

    // ---- Cursor --------------------------------------------------------------------------------------
    var cursor by remember { mutableStateOf<GuideNav.Cursor?>(null) }
    // Tracked by id, so a re-sort, filter or cache refresh keeps the same channel highlighted.
    var cursorChannelId by remember { mutableStateOf<Long?>(null) }
    var gridFocused by remember { mutableStateOf(false) }
    val gridFocus = remember { FocusRequester() }
    fun moveTo(row: Int, timeMs: Long) {
        cursor = GuideNav.Cursor(row, timeMs)
        cursorChannelId = rows.getOrNull(row)?.id
    }
    fun programmesOf(channel: ChannelEntity): List<EpgProgrammeEntity> =
        rowStore[channel.id]?.programmes ?: vm.cachedProgrammes(channel).orEmpty()
    // The current row list for code that outlives a composition (derived states, collectors).
    val rowsState = androidx.compose.runtime.rememberUpdatedState(rows)

    val pxPerMin = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    suspend fun scrollTimelineTo(minutes: Int) {
        // The time axis must be laid out before maxValue is known; scrolling earlier clamps to 0.
        kotlinx.coroutines.withTimeoutOrNull(2000) {
            androidx.compose.runtime.snapshotFlow { hScroll.maxValue }.first { it > 0 }
        }
        runCatching { hScroll.scrollTo((minutes * pxPerMin).toInt()) }
    }

    // Opening: the watched channel's row (or the one tuned from the guide, when coming back from it),
    // at the current time, with the timeline at Now. Later list changes keep the cursor's channel.
    LaunchedEffect(state.loading, rows) {
        if (state.loading || rows.isEmpty()) return@LaunchedEffect
        val ids = rows.map { it.id }
        val keptRow = cursorChannelId?.let { ids.indexOf(it) }?.takeIf { it >= 0 }
        val current = cursor
        if (current != null && keptRow != null) {
            if (keptRow != current.row) cursor = current.copy(row = keptRow)
            return@LaunchedEffect
        }
        // Back from a channel tuned here: that one. Otherwise the channel playing now, then the last one
        // watched (Live TV clears its own selection when you leave it, so history is the fallback).
        val preferred = listOf(
            if (restoreFocus) vm.lastTunedChannelId else null,
            watchingChannelId,
            vm.lastWatchedChannelId(),
        )
        val open = GuideNav.openingCursor(ids, preferred, System.currentTimeMillis()) ?: return@LaunchedEffect
        cursor = open
        cursorChannelId = ids[open.row]
        runCatching { rowListState.scrollToItem((open.row - 2).coerceAtLeast(0)) }
        scrollTimelineTo(GuideNav.openingScrollMinutes(open.timeMs, state.windowStart))
    }

    // Phase 4B timing: the first frame drawn with rows on screen (logcat GuideTiming).
    LaunchedEffect(state.loading, rows.isNotEmpty()) {
        if (state.loading || rows.isEmpty()) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        vm.timing.firstUsableFrame()
    }

    // Prepare row data for the rows on screen and ROW_PREP_MARGIN either side: programmes from the VM
    // cache (one indexed query the first time a channel has none), catch-up ids on a background thread,
    // logos through the shared image loader. A row is drawn the moment its data lands; nothing is composed
    // ahead. Re-runs on a new list or cache revision; data from an older revision is refreshed in place.
    val renderer = androidx.compose.runtime.rememberUpdatedState(rowRenderer)
    LaunchedEffect(rows, cacheRevision, state.windowStart) {
        androidx.compose.runtime.snapshotFlow {
            val info = rowListState.layoutInfo
            val viewportMin = (hScroll.viewportSize / pxPerMin).toInt()
            listOf(
                info.visibleItemsInfo.firstOrNull()?.index ?: rowListState.firstVisibleItemIndex,
                info.visibleItemsInfo.size,
                GuideNav.timelineBucket((hScroll.value / pxPerMin).toInt()),
                viewportMin,
            )
        }.collectLatest { (first, visible, bucket, viewportMin) ->
            val list = rowsState.value
            if (list.isEmpty()) return@collectLatest
            val order = GuideNav.prepOrder(first, visible.coerceAtLeast(1), ROW_PREP_MARGIN, list.size)
            kotlinx.coroutines.coroutineScope {
                for (i in order) {
                    val ch = list[i]
                    ch.logoUrl?.let { url -> if (logoCache[url] == null) launch { logoCache.load(url) } }
                    if (!rowStore.isCurrent(ch.id, cacheRevision)) {
                        val progs = vm.cachedProgrammes(ch) ?: vm.programmesFor(ch)
                        val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            GuideNav.prepareRow(ch, progs, System.currentTimeMillis(), cacheRevision)
                        }
                        rowStore.put(data)
                        // Phase 4C timing: the first on-screen row with programme cells to draw.
                        if (data.programmes.isNotEmpty() && i in first until first + visible) vm.timing.firstProgrammes()
                    }
                    // Cell texts for rows in view (and one either side), for this part of the timeline.
                    if (i !in (first - 1)..(first + visible) || viewportMin <= 0) continue
                    val key = GuideNav.textKey(cacheRevision, bucket)
                    if (textStore[ch.id]?.key == key) continue
                    val data = rowStore[ch.id] ?: continue
                    val (from, to) = GuideNav.textSpan(state.windowStart, bucket, viewportMin)
                    val texts = kotlinx.coroutines.withContext(textDispatcher) {
                        renderer.value.prepareTexts(data, from, to, System.currentTimeMillis(), state.windowStart, state.windowEnd, key)
                    }
                    textStore.put(ch.id, texts)
                }
            }
        }
    }

    // Back from a channel tuned in the guide: focus the grid again (the cursor is on that channel).
    LaunchedEffect(restoreFocus, state.loading, rows.isNotEmpty()) {
        if (!restoreFocus || state.loading || rows.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { gridFocus.requestFocus() }
        onRestored()
    }

    // Keep the highlighted programme on screen — the least scroll that does it, rows and time alike.
    // Collected from a snapshot flow rather than keyed on the cursor, so this screen's body never reads
    // the cursor during composition: a D-pad move recomposes only the two rows whose highlight changed
    // and the details panel (Phase 3 recomposed everything, ~300 ms per move on the onn).
    val windowStartNow by androidx.compose.runtime.rememberUpdatedState(state.windowStart)
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { cursor }.collectLatest { c ->
            if (c == null) return@collectLatest
            android.os.Trace.beginSection("Guide.scrollIntoView")
            val info = rowListState.layoutInfo
            val fullyVisible = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
            val firstFull = fullyVisible.firstOrNull()?.index ?: rowListState.firstVisibleItemIndex
            android.os.Trace.endSection()
            GuideNav.rowScrollTarget(c.row, firstFull, fullyVisible.size)?.let { target ->
                // A few rows: scroll by pixels, which lets the list prefetch the rows that follow. A long
                // jump (paging): go straight to the index.
                if (kotlin.math.abs(target - firstFull) <= 3) {
                    val delta = GuideNav.rowScrollDeltaPx(target, rowListState.firstVisibleItemIndex, rowListState.firstVisibleItemScrollOffset, rowStridePx)
                    runCatching { rowListState.scrollBy(delta.toFloat()) }
                } else {
                    runCatching { rowListState.scrollToItem(target) }
                }
            }
            val scrollMin = (hScroll.value / pxPerMin).toInt()
            val viewportMin = (hScroll.viewportSize / pxPerMin).toInt()
            GuideNav.timelineScrollTarget(c.timeMs, windowStartNow, scrollMin, viewportMin)
                ?.let { runCatching { hScroll.scrollTo((it * pxPerMin).toInt()) } }
        }
    }

    val jumpToNow: () -> Unit = {
        val t = System.currentTimeMillis()
        now = t
        cursor?.let { cursor = it.copy(timeMs = t) }
        scope.launch {
            scrollTimelineTo(GuideNav.openingScrollMinutes(t, state.windowStart))
            runCatching { gridFocus.requestFocus() }
        }
    }

    val moreFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val jumpNowFocus = remember { FocusRequester() }
    // A control focus should land on when it next enters the guide from outside (a closing menu or
    // dialog). The guide's default entry (the grid) must not redirect such a request, and a request is
    // disarmed once it has run, so it can never redirect a later one.
    val pendingEnter = remember { GuideNav.PendingFocus<FocusRequester>() }
    suspend fun restoreFocus(target: FocusRequester) {
        pendingEnter.arm(target)
        kotlinx.coroutines.delay(80)
        runCatching { target.requestFocus() }
        kotlinx.coroutines.android.awaitFrame()
        pendingEnter.disarm(target)
    }
    fun focusTarget(target: GuideNav.FocusTarget): FocusRequester = when (target) {
        GuideNav.FocusTarget.GRID -> gridFocus
        GuideNav.FocusTarget.MORE_BUTTON -> moreFocus
        GuideNav.FocusTarget.CATEGORY_BUTTON -> categoryFocus
    }
    // More and Category hand focus back to their header button when they close (Phase 3 left it on the
    // sidebar after More, so the next key moved somewhere unexpected).
    var menuWasOpen by remember { mutableStateOf<GuideNav.Overlay?>(null) }
    LaunchedEffect(showOptions, showCategoryPicker) {
        val open = when {
            showOptions -> GuideNav.Overlay.MORE
            showCategoryPicker -> GuideNav.Overlay.CATEGORY
            else -> null
        }
        val closed = menuWasOpen
        menuWasOpen = open
        if (open == null && closed != null) restoreFocus(focusTarget(GuideNav.focusAfterClose(closed)))
    }

    // Closing any guide dialog hands focus back to the grid, on the programme it was opened from.
    val anyDialogOpen = detail != null || matchChooser != null || matchingChannel != null || offsetChannel != null
    var hadDialog by remember { mutableStateOf(false) }
    LaunchedEffect(anyDialogOpen) {
        if (anyDialogOpen) { hadDialog = true; return@LaunchedEffect }
        if (!hadDialog) return@LaunchedEffect
        hadDialog = false
        restoreFocus(gridFocus)
    }

    // Back in the grid steps out to the header (press consumed, release acts — so it can't also reach the
    // shell's Back); Back from the header then leaves the guide as before.
    var backDown by remember { mutableStateOf(false) }
    val jumpNowShown = now in state.windowStart..state.windowEnd
    fun focusHeader() {
        val target = when (GuideNav.headerTarget(jumpNowShown, guideCategories.isNotEmpty())) {
            GuideNav.HeaderTarget.JUMP_TO_NOW -> jumpNowFocus
            GuideNav.HeaderTarget.CATEGORY -> categoryFocus
            GuideNav.HeaderTarget.MORE -> moreFocus
        }
        runCatching { target.requestFocus() }
    }

    // OK is acted on at release, so a long press (channel options) never also opens the programme.
    var okDown by remember { mutableStateOf(false) }
    var okLongPressed by remember { mutableStateOf(false) }
    fun onGridKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val c = cursor ?: return false
        val channel = rows.getOrNull(c.row) ?: return false
        val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (e.key == Key.Back) {
            if (e.type == KeyEventType.KeyDown) backDown = true
            else if (e.type == KeyEventType.KeyUp && backDown) {
                backDown = false
                // Back steps out to Categories (Phase 4B), so it is one press away from any row; Back again
                // leaves the guide (to the player when the guide was opened from it — the shell's Back).
                focusHeader()
            }
            return true
        }
        if (e.type == KeyEventType.KeyUp) {
            if (!isOk || !okDown) return false
            okDown = false
            if (okLongPressed) { okLongPressed = false; return true }
            GuideNav.programmeAt(programmesOf(channel), c.timeMs)?.let { detail = channel to it }
            return true
        }
        if (e.type != KeyEventType.KeyDown) return false
        return when (e.key) {
            // Up from the first row leaves for the header; Down stops at the last row.
            Key.DirectionUp -> GuideNav.stepRow(c.row, -1, rows.size)?.let { moveTo(it, c.timeMs); true } ?: false
            Key.DirectionDown -> { GuideNav.stepRow(c.row, 1, rows.size)?.let { moveTo(it, c.timeMs) }; true }
            // Physical by design: the guide is an LTR timeline, earlier is always left. Left past the
            // window start returns to the menu; Right past the end lets focus reach a docked mini-player.
            Key.DirectionLeft -> GuideNav.stepTime(programmesOf(channel), c.timeMs, -1, state.windowStart, state.windowEnd)
                ?.let { cursor = c.copy(timeMs = it); true } ?: run { onBack(); true }
            Key.DirectionRight -> GuideNav.stepTime(programmesOf(channel), c.timeMs, 1, state.windowStart, state.windowEnd)
                ?.let { cursor = c.copy(timeMs = it); true } ?: false
            Key.Menu -> { matchChooser = channel; true }
            // CH+/CH−/Page: a screen of rows at a time — 11k channels are not browsed one row at a time.
            Key.ChannelUp, Key.PageUp, Key.ChannelDown, Key.PageDown -> {
                val pages = if (e.key == Key.ChannelUp || e.key == Key.PageUp) -1 else 1
                val info = rowListState.layoutInfo
                val full = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
                GuideNav.pageMove(c.row, pages, full.size, full.firstOrNull()?.index ?: rowListState.firstVisibleItemIndex, rows.size)?.let { move ->
                    scope.launch {
                        runCatching { rowListState.scrollToItem(move.firstVisible) }
                        moveTo(move.row, c.timeMs)
                    }
                }
                true
            }
            else -> if (isOk) {
                if (e.nativeKeyEvent.repeatCount == 0) {
                    okDown = true
                    okLongPressed = false
                } else if (okDown && !okLongPressed) {
                    okLongPressed = true
                    matchChooser = channel // hold OK: favourite, EPG match, time offset
                }
                true
            } else false
        }
    }

    // Phase 4 rendering (GuideLayers): read once per opening, debug builds only.
    val cachedLayers = remember {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        GuideLayers.enabled(debuggable, if (debuggable) GuideLayers.readProperty() else null)
    }
    Box(modifier.fillMaxSize()) {
    // The panel's fill, border and light gradients: drawn once into their own layer, then composited, instead
    // of being re-issued with every frame of a move. The content is not clipped to the rounded corners
    // (its padding keeps it inside them), so no full-panel rounded clip is drawn per frame either.
    Box(
        Modifier.matchParentSize()
            .then(if (cachedLayers) Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen } else Modifier)
            .roundedPanel(fillColor = ContentPanelFill),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Entry from the sidebar lands on the grid (the cursor already sits on the watched channel).
            .focusProperties {
                onEnter = {
                    val pending = pendingEnter.take()
                    val target = if (pending != null) pending else if (rows.isNotEmpty() && !state.loading) gridFocus else null
                    target?.let { t -> runCatching { t.requestFocus() } }
                }
            }
            // Held Up/Down can outrun the guide's row composition and escape this pane
            // (landing on the top bar) — trap vertical exits; Left/Right/Back leave normally.
            .trapVerticalFocusExit()
            .focusGroup()
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        // ---- Header: title, date and time · Jump to Now · Category · More --------------------------
        val formatTime = rememberSystemTimeFormatter()
        val formatHeaderDate = rememberBestDateFormatter("EEEdMMM")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(stringResource(R.string.content_epg_title), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The day being browsed follows the cursor into the archive or the next days. Derived,
                    // so this text recomposes when the DATE changes, not on every move.
                    val nowState by androidx.compose.runtime.rememberUpdatedState(now)
                    val headerDate by remember(formatHeaderDate) {
                        androidx.compose.runtime.derivedStateOf {
                            val c = cursor?.takeIf { gridFocused }
                            val day = c?.let { cur -> GuideNav.selectionDay(rowsState.value, cur) { programmesOf(it) } } ?: nowState
                            formatHeaderDate(day)
                        }
                    }
                    Text(headerDate, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                    Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.onSurfaceVariant))
                    Text(formatTime(now), style = MaterialTheme.typography.titleSmall, color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            if (matching || state.refreshing) {
                // Matching, or a reload / category change running while the current list stays usable.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OwnTVSpinner(sizeDp = 20)
                    Text(
                        stringResource(if (matching) R.string.content_epg_matching else R.string.content_epg_updating),
                        style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                    )
                }
            }
            if (now in state.windowStart..state.windowEnd) {
                OwnTVButton(stringResource(R.string.content_epg_jump_now), onClick = jumpToNow, icon = OwnTVIcon.HISTORY, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.focusRequester(jumpNowFocus))
            }
            if (guideCategories.isNotEmpty()) {
                val catLabel = categoryFilter?.let { key -> guideCategories.firstOrNull { it.key == key }?.name } ?: stringResource(R.string.content_epg_all)
                OwnTVButton(stringResource(R.string.content_epg_category_button, catLabel), onClick = { showCategoryPicker = true }, icon = OwnTVIcon.MENU, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.focusRequester(categoryFocus))
            }
            OwnTVButton(stringResource(R.string.content_epg_more), onClick = { showOptions = true }, icon = OwnTVIcon.GEAR, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.focusRequester(moreFocus))
        }
        // Outcome of the last auto-match run (auto-applied count / how many need review). Dismissible.
        matchSummary?.let { summary ->
            if (review.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                FocusableSurface(onClick = vm::clearReview, shape = RoundedCornerShape(10.dp), unfocusedContainerColor = colors.surfaceContainerHigh, contentAlignment = Alignment.CenterStart, surface = GlassSurface.CARDS) { _ ->
                    Text(epgMatchSummaryText(summary), style = MaterialTheme.typography.labelLarge, color = colors.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        if (showSearch || query.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            SearchBar(
                query = query,
                onQueryChange = vm::setQuery,
                placeholder = stringResource(R.string.content_epg_search_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        // ---- Details beside the live preview, above the timeline ----------------------------------------
        // The band and the grid share the height left under the header. The preview is as large as keeps
        // three whole rows visible (GuidePreviewSize), measured here so the UI zoom and font size are
        // respected. Only its size depends on this: the video view and its player stay the same.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val previewWidth = remember(maxHeight) { GuidePreviewSize.width(maxHeight) }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(GuidePreviewSize.bandHeight(previewWidth)), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (!state.loading && rows.isNotEmpty()) {
                            // The panel reads the cursor only in its own collector and updates once it settles.
                            GuideDetailsPanel(
                                cursor = { cursor },
                                rows = rows,
                                programmesOf = { programmesOf(it) },
                                now = now,
                                reminderSlots = reminderSlots,
                                hiddenEmpty = hiddenEmpty,
                                vm = vm,
                                cacheRevision = cacheRevision,
                                textDispatcher = textDispatcher,
                            )
                        }
                    }
                    GuidePreview(channel = previewChannel, video = previewVideo, width = previewWidth)
                }
                Spacer(Modifier.height(GuidePreviewSize.GapBelow))

                when {
                    state.loading -> CenterBox { OwnTVSpinner(sizeDp = 56) }
                    // No EPG feed added yet → guide it can't fill. Point the user to EPG Sources.
                    !state.hasEpgSources && state.channels.isEmpty() -> CenterBox {
                        Text(stringResource(R.string.content_epg_empty), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.content_epg_add_description),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        OwnTVButton(stringResource(R.string.content_epg_add), onClick = onAddEpg, icon = OwnTVIcon.ADD)
                    }
                    state.channels.isEmpty() -> CenterBox {
                        Text(state.message?.let { epgMessageText(it) } ?: stringResource(R.string.content_epg_no_guide), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    // Every channel in this view has an empty window: say so, and offer the hidden rows.
                    rows.isEmpty() -> CenterBox {
                        Text(stringResource(R.string.content_epg_no_guide_rows), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        OwnTVButton(stringResource(R.string.content_epg_show_all_channels), onClick = { vm.setShowAllChannels(true) }, style = OwnTVButtonStyle.SECONDARY)
                    }
                    else -> {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            // Its own layer: redrawn when the timeline scrolls or the clock ticks, never by a cursor move.
                            Box(Modifier.graphicsLayer()) {
                                GuideTimeAxis(windowStart = state.windowStart, windowEnd = state.windowEnd, now = now, hScroll = hScroll)
                            }
                            Spacer(Modifier.height(6.dp))
                            // The grid: ONE focus target; rows are drawn, not focused.
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .focusRequester(gridFocus)
                                    .onFocusChanged { gridFocused = it.isFocused }
                                    .onKeyEvent { onGridKey(it) }
                                    .focusable(),
                            ) {
                                LazyColumn(modifier = Modifier.fillMaxSize(), state = rowListState, verticalArrangement = Arrangement.spacedBy(GuideGridDefaults.RowGap)) {
                                    itemsIndexed(rows, key = { _, ch -> ch.id }) { index, channel ->
                                        // Each row derives its own highlight: only the rows the cursor leaves and
                                        // enters see a change, so only those two recompose and redraw on a move.
                                        val highlight = remember(index) {
                                            androidx.compose.runtime.derivedStateOf { GuideNav.highlightFor(cursor, gridFocused, index) }
                                        }
                                        val numberText = remember(channel.number, channelNumberFormat) { channel.number?.let { channelNumberFormat.format(it) } }
                                        val dot = channel.categoryId?.let { categoryDots[it] }
                                        val windowStart = state.windowStart
                                        val windowEnd = state.windowEnd
                                        // This row's OWN states: a write to another channel's data, logo or reminders
                                        // never invalidates this row (Phase 3D shared one map across all rows).
                                        val rowData = remember(channel.id) { rowStore.state(channel.id) }
                                        val logo = remember(channel.logoUrl) { channel.logoUrl?.let { logoCache.state(it) } }
                                        val reminderStarts = remember(channel.id) {
                                            androidx.compose.runtime.derivedStateOf { GuideNav.reminderStarts(reminderSlotsState.value, channel.id) }
                                        }
                                        val texts = remember(channel.id) { textStore.state(channel.id) }
                                        // One layout node per row. The focus overlay (outer) is drawn over the row's
                                        // static content, which sits in its own cached layer (inner): a cursor move
                                        // re-records only the overlay of the two rows involved; data, logo, reminder,
                                        // scroll and clock changes re-record only this row's base.
                                        Spacer(
                                            Modifier.fillMaxWidth().height(GuideGridDefaults.RowHeight)
                                                .drawWithContent {
                                                    drawContent()
                                                    val hl = highlight.value ?: return@drawWithContent
                                                    with(rowRenderer) {
                                                        drawRowFocus(
                                                            channel = channel,
                                                            numberText = numberText,
                                                            categoryDot = dot,
                                                            logo = logo?.value,
                                                            data = rowData.value,
                                                            highlight = hl,
                                                            now = nowState.longValue,
                                                            scrollPx = hScroll.value.toFloat(),
                                                            windowStart = windowStart,
                                                            windowEnd = windowEnd,
                                                            reminderStarts = reminderStarts.value,
                                                            texts = texts.value,
                                                        )
                                                    }
                                                }
                                                // The row's static content in its own layer: cached (offscreen) so a cursor
                                                // move or a vertical scroll composites it instead of re-issuing its cells.
                                                .graphicsLayer { if (cachedLayers) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                                                .drawBehind {
                                                    with(rowRenderer) {
                                                        drawRowBase(
                                                            channel = channel,
                                                            numberText = numberText,
                                                            categoryDot = dot,
                                                            logo = logo?.value,
                                                            data = rowData.value,
                                                            now = nowState.longValue,
                                                            scrollPx = hScroll.value.toFloat(),
                                                            windowStart = windowStart,
                                                            windowEnd = windowEnd,
                                                            reminderStarts = reminderStarts.value,
                                                            texts = texts.value,
                                                        )
                                                    }
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // The guide's own dialogs read in the app's main font, like the guide itself (the shared popup font
    // defaults to a serif face that looked out of place over the grid). Other screens keep theirs.
    CompositionLocalProvider(tv.own.owntv.ui.theme.LocalPopupFontFamily provides tv.own.owntv.ui.theme.LocalMainFontFamily.current) {
    detail?.let { (channel, p) ->
        val reminderSet = tv.own.owntv.core.sync.work.ProgramReminders.Key(channel.id, p.startMs) in reminderSlots
        val wallNow = System.currentTimeMillis()
        val canCatchup = vm.canCatchup(channel, p, wallNow)
        // Future programmes can take a reminder; one already set stays removable until its programme ends.
        val offerReminder = p.stopMs > wallNow && (p.startMs > wallNow || reminderSet)
        ProgrammeDetailDialog(
            channelName = channel.name,
            programme = p,
            loadDescription = { vm.programmeDescription(it) },
            canCatchup = canCatchup,
            isFavorite = channel.id in favoriteIds,
            onToggleFavorite = { vm.toggleFavoriteChannel(channel) },
            onWatch = { detail = null; vm.noteChannelTuned(channel); onPlayChannel(channel, state.channels) },
            onPlayCatchup = {
                detail = null
                vm.noteChannelTuned(channel)
                onPlayCatchup(channel, p)
            },
            // External play needs no shell involvement: nothing is mounted in-app, so it goes straight
            // through the Guide's own VM (which owns the archive-URL builder) in both hosting modes.
            onPlayCatchupExternal = { detail = null; vm.noteChannelTuned(channel); vm.playCatchupExternal(channel, p) },
            catchupPlayer = catchupPlayer,
            onDismiss = { detail = null },
            onToggleReminder = if (!offerReminder) null else { {
                scope.launch {
                    reminderNote = if (reminderSet) {
                        reminderMessages.forRemove(programReminders.remove(channel, p.startMs))
                    } else {
                        reminderMessages.forSet(programReminders.set(channel, p.title, p.startMs, p.stopMs), p.title)
                    }
                }
            } },
            reminderSet = reminderSet,
            reminderMessage = reminderNote,
            initialFocus = GuideNav.detailFocus(p, wallNow, canCatchup),
        )
    }

    if (showOptions) {
        val sortLabel = when {
            sortGuide == SettingsRepository.GuideSort.CATCHUP && state.catchupCount == 0 -> guideSortLabel(SettingsRepository.GuideSort.LIVE_TV)
            sortGuide == SettingsRepository.GuideSort.FAVORITES && state.favoriteCount == 0 -> guideSortLabel(SettingsRepository.GuideSort.LIVE_TV)
            else -> guideSortLabel(sortGuide)
        }
        GuideOptionsDialog(
            sortLabel = sortLabel,
            onCycleSort = vm::cycleGuideSort,
            matching = matching,
            onAutoMatch = { showOptions = false; vm.autoMatchEpg() },
            statsText = state.stats?.let { epgStatsText(it) },
            showAllChannels = showAllChannels,
            onToggleShowAll = { vm.setShowAllChannels(!showAllChannels) },
            onSearch = { showOptions = false; showSearch = true },
            onDismiss = { showOptions = false },
        )
    }

    // Long-press a channel → channel options: favourite toggle + EPG match (auto or manual pick).
    matchChooser?.let { channel ->
        EpgMatchChooserDialog(
            channelName = channel.name,
            isFavorite = channel.id in favoriteIds,
            onToggleFavorite = { vm.toggleFavoriteChannel(channel) },
            onAuto = { vm.autoMatchOne(channel); matchChooser = null },
            onManual = { matchChooser = null; matchingChannel = channel },
            onOffset = { matchChooser = null; offsetChannel = channel },
            onDismiss = { matchChooser = null },
        )
    }

    offsetChannel?.let { channel ->
        tv.own.owntv.features.live.EpgOffsetDialog(
            channelName = channel.name,
            currentMinutes = vm.currentEpgShift(channel),
            globalMinutes = vm.globalEpgShift(),
            onSet = { vm.setEpgShift(channel, it) },
            onDismiss = { offsetChannel = null },
        )
    }

    matchingChannel?.let { channel ->
        tv.own.owntv.features.live.EpgMatchDialog(
            channelName = channel.name,
            currentMatch = vm.currentEpgMatch(channel),
            loadChannels = { vm.availableEpgChannels(channel.name, it) },
            onPick = { vm.setEpgMatch(channel, it); matchingChannel = null },
            onClear = { vm.setEpgMatch(channel, null); matchingChannel = null },
            onDismiss = { matchingChannel = null },
        )
    }

    if (review.isNotEmpty()) {
        EpgMatchReviewDialog(
            suggestions = review,
            onAccept = vm::acceptSuggestion,
            onSkip = vm::dismissSuggestion,
            onAcceptAll = vm::acceptAllSuggestions,
            onSkipAll = vm::clearReview,
            onDone = vm::clearReview,
        )
    }

    } // guide dialogs font

    if (showCategoryPicker) CompositionLocalProvider(tv.own.owntv.ui.theme.LocalPopupFontFamily provides tv.own.owntv.ui.theme.LocalMainFontFamily.current) {
        tv.own.owntv.features.settings.PickerDialog(
            title = stringResource(R.string.content_epg_guide_category),
            options = listOf("ALL" to stringResource(R.string.content_epg_all_categories)) + guideCategories.map { it.key to it.name },
            selected = categoryFilter ?: "ALL",
            onSelect = { vm.setCategoryFilter(it.takeUnless { value -> value == "ALL" }); showCategoryPicker = false },
            onDismiss = { showCategoryPicker = false },
            searchable = true,
        )
    }
}

/**
 * Review screen for the smart EPG matcher (#13): lists the lower-confidence suggestions the auto-match
 * couldn't apply on its own, so the user can accept the good ones and skip the rest. High-confidence
 * matches are applied automatically and never reach here.
 */
@Composable
private fun EpgMatchReviewDialog(
    suggestions: List<EpgViewModel.EpgMatchSuggestion>,
    onAccept: (EpgViewModel.EpgMatchSuggestion) -> Unit,
    onSkip: (EpgViewModel.EpgMatchSuggestion) -> Unit,
    onAcceptAll: () -> Unit,
    onSkipAll: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    BackHandler { onDone() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }

    // Popup(focusable=true) creates a hard focus boundary — clicking Accept/Skip removes an item
    // from the LazyColumn, but focus stays inside instead of escaping to the main nav bar.
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDone) {
    tv.own.owntv.ui.theme.PopupFontTheme(fontScale = 0.75f) {
    Box(
        Modifier.fillMaxSize().modalScrim(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(width = 576.dp, corner = 18.dp, padding = 18.dp)) {
            Text(stringResource(R.string.content_epg_review_matches), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.content_epg_review_description),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // Actions live in a right-hand column so a D-pad right from ANY suggestion row reaches
            // Accept all/Skip all/Done directly — no scrolling to the bottom of a long list.
            val listHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 200.dp).coerceIn(160.dp, 300.dp)
            Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxWidth().height(listHeight), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(suggestions, key = { _, s -> s.channel.id }) { index, s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.channel.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.content_epg_channel_score, s.epgName ?: s.epgChannelId, (s.score * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FocusableSurface(
                            onClick = { onAccept(s) },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            shape = RoundedCornerShape(10.dp),
                            unfocusedContainerColor = colors.primaryContainer,
                            contentAlignment = Alignment.Center,
                            surface = GlassSurface.DIALOGS,
                        ) { _ -> Text(stringResource(R.string.content_epg_accept), style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                        FocusableSurface(
                            onClick = { onSkip(s) },
                            shape = RoundedCornerShape(10.dp),
                            unfocusedContainerColor = colors.surfaceContainerHigh,
                            contentAlignment = Alignment.Center,
                            surface = GlassSurface.DIALOGS,
                        ) { _ -> Text(stringResource(R.string.content_epg_skip), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    }
                }
            }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Bulk actions only make sense for a multi-channel run; a single auto-match shows just accept/skip.
                if (suggestions.size > 1) {
                    OwnTVButton(stringResource(R.string.content_epg_accept_all), onClick = onAcceptAll, icon = OwnTVIcon.PLAY, modifier = Modifier.fillMaxWidth())
                    OwnTVButton(stringResource(R.string.content_epg_skip_all), onClick = onSkipAll, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
                }
                OwnTVButton(stringResource(R.string.common_done), onClick = onDone, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
            }
        }
    }
    } // PopupFontTheme
    } // Popup
}

/**
 * Long-press chooser for a Guide channel's EPG: either auto-match just this channel by name, or open
 * the manual picker to choose its guide channel from the full list (#10/#13).
 */
@Composable
private fun EpgMatchChooserDialog(
    channelName: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAuto: () -> Unit,
    onManual: () -> Unit,
    onOffset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    BackHandler { onDismiss() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup()
            .longPressMenuGuard(), // long-press OK is still held — don't auto-click the first option
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel()) {
            Text(channelName, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.content_epg_favourite_description),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OwnTVButton(
                stringResource(if (isFavorite) R.string.content_epg_remove_favourite else R.string.content_epg_add_favourite),
                onClick = { onToggleFavorite(); onDismiss() },
                icon = OwnTVIcon.FAVORITE,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
            Spacer(Modifier.height(10.dp))
            OwnTVButton(stringResource(R.string.content_epg_match_button), onClick = onAuto, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.EPG, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OwnTVButton(stringResource(R.string.content_epg_pick_manually), onClick = onManual, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.SEARCH, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OwnTVButton(stringResource(R.string.content_epg_time_offset), onClick = onOffset, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.EPG, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
        }
    }
}

/**
 * Time labels across the top, scrolled with the rows (it owns the shared [hScroll]), with a red marker
 * at the current time that lines up with the now line drawn through every row.
 */
@Composable
private fun GuideTimeAxis(windowStart: Long, windowEnd: Long, now: Long, hScroll: androidx.compose.foundation.ScrollState) {
    val colors = OwnTVTheme.colors
    val formatTime = rememberSystemTimeFormatter()
    val slots = ((windowEnd - windowStart) / (GuideGridDefaults.SlotMin * 60_000L)).toInt()
    val nowColor = Color(0xFFFF5C5C)
    Row {
        Spacer(Modifier.width(GuideGridDefaults.ChannelCol))
        Box(Modifier.weight(1f).height(28.dp)) {
            Row(Modifier.horizontalScroll(hScroll)) {
                for (i in 0 until slots) {
                    val slotMs = windowStart + i * GuideGridDefaults.SlotMin * 60_000L
                    Text(
                        formatTime(slotMs),
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                        color = colors.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width((GuideGridDefaults.SlotMin * GuideGridDefaults.PxPerMin.value).dp).padding(start = 6.dp, top = 4.dp),
                    )
                }
            }
            val pxPerMin = with(androidx.compose.ui.platform.LocalDensity.current) { GuideGridDefaults.PxPerMin.toPx() }
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val scrollPx = hScroll.value.toFloat() // read while drawing: scrolling redraws, no recomposition
                if (now !in windowStart..windowEnd) return@Canvas
                val x = ((now - windowStart) / 60_000f) * pxPerMin - scrollPx
                if (x < 0f || x > size.width) return@Canvas
                // A short tick at the bottom of the axis, continued by each row's now line.
                drawRoundRect(
                    color = nowColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x - 3f, size.height - 10f),
                    size = androidx.compose.ui.geometry.Size(6f, 10f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                )
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/**
 * Details for the programme under the cursor (and what follows it on that channel). Never focusable —
 * the grid keeps the D-pad; OK opens the full dialog.
 *
 * On the onn this panel's six Text composables re-measured on every cursor move (~13-15 ms of rebuild
 * plus text measurement inside the move's frame). Now: the cursor is read only in a collector, never
 * while composing; the panel waits for the cursor to settle (see [GuideNav.panelSettleMs]); its texts
 * are laid out on the guide's text thread; and the panel is one Canvas that only draws those layouts.
 * The synopsis (a database read) is fetched once the cursor rests on a programme. The update rules
 * live in [GuideNav.panelRequests] / [GuideNav.showPanel]: unchanged text is never laid out again.
 */
@Composable
private fun GuideDetailsPanel(
    cursor: () -> GuideNav.Cursor?,
    rows: List<ChannelEntity>,
    // The row's drawn (normalized) programmes, so the panel describes the cell that is highlighted.
    programmesOf: (ChannelEntity) -> List<EpgProgrammeEntity>,
    now: Long,
    reminderSlots: Set<tv.own.owntv.core.sync.work.ProgramReminders.Key>,
    hiddenEmpty: Boolean,
    vm: EpgViewModel,
    cacheRevision: Int,
    textDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    val colors = OwnTVTheme.colors
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val formatTime = rememberSystemTimeFormatter()
    val rangeTemplate = stringResource(R.string.content_epg_time_range)
    val nowLabel = stringResource(R.string.content_live_now_label)
    val catchup = stringResource(R.string.content_epg_catchup)
    val reminderSet = stringResource(R.string.content_reminder_active)
    val separator = stringResource(R.string.content_epg_details_separator)
    val noProgramme = stringResource(R.string.content_epg_no_programme)
    val hint = stringResource(if (hiddenEmpty) R.string.content_epg_empty_hidden else R.string.content_epg_controls_hint)
    // One instance until the wording changes. It is compared by identity, and Phase 3F built a new one
    // on every composition: each layout recomposed the panel, which looked like new input, which laid
    // it out again — ~50 layouts a second while idle, and on behind the home screen.
    val strings = remember(rangeTemplate, nowLabel, catchup, reminderSet, separator, noProgramme, hint, formatTime, context) {
        GuideNav.PanelStrings(
            nowLabel = nowLabel, catchup = catchup, reminderSet = reminderSet, separator = separator,
            noProgramme = noProgramme, hint = hint,
            range = { a, b -> String.format(java.util.Locale.ROOT, rangeTemplate, formatTime(a), formatTime(b)) },
            runtime = { m -> context.resources.getString(R.string.content_epg_runtime, m) },
            nextLabel = { t -> context.resources.getString(R.string.content_live_next_label, formatTime(t)) },
        )
    }
    val styles = remember(colors, typography) { PanelStyles(colors, typography) }
    // Used only on the guide's text thread (a TextMeasurer is not thread-safe).
    val measurer = remember(fontResolver, density, layoutDirection) {
        androidx.compose.ui.text.TextMeasurer(fontResolver, density, layoutDirection, 0)
    }
    val padH = with(density) { 18.dp.toPx() }
    val padV = with(density) { 12.dp.toPx() }
    val gap = with(density) { 24.dp.toPx() }
    val rightMax = with(density) { 320.dp.toPx() }

    var widthPx by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<PanelLayout?>(null) }
    // Everything except the cursor that changes what the panel shows; read only by the collector below.
    val inputs = androidx.compose.runtime.rememberUpdatedState(PanelInputs(rows, now, reminderSlots, cacheRevision, strings))

    // Only while the screen is started: Compose keeps producing frames for a stopped activity, so a
    // collector left running would keep working behind the home screen.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, measurer, styles) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            val states = androidx.compose.runtime.snapshotFlow { GuideNav.PanelState(cursor(), widthPx, inputs.value) }
            val requests = GuideNav.panelRequests(states, clock = { android.os.SystemClock.uptimeMillis() }) { c, inp ->
                val channel = c?.row?.let { inp.rows.getOrNull(it) }
                val programmes = channel?.let { programmesOf(it) }.orEmpty()
                val reminderStarts = channel?.let { GuideNav.reminderStarts(inp.reminderSlots, it.id) }.orEmpty()
                GuideNav.panelText(
                    programmes, c?.timeMs ?: inp.now, inp.now, reminderStarts,
                    canCatchup = { p -> channel != null && vm.canCatchup(channel, p, inp.now) },
                    synopsis = null, hint = inp.strings.hint, strings = inp.strings,
                )
            }
            GuideNav.showPanel(requests, SYNOPSIS_SETTLE_MS, loadSynopsis = { vm.programmeDescription(it) }) { text, w ->
                val sep = inputs.value.strings.separator
                layout = kotlinx.coroutines.withContext(textDispatcher) { layoutPanel(text, w, measurer, styles, padH, gap, rightMax, sep) }
            }
        }
    }

    val description = layout?.description.orEmpty()
    val panelFill = colors.surfaceContainerHigh
    val panelCorner = with(density) { 14.dp.toPx() }
    androidx.compose.foundation.Canvas(
        // Its own layer, redrawn only when a new layout lands. The rounded fill is drawn, not clipped.
        Modifier.fillMaxSize().graphicsLayer()
            .onSizeChanged { widthPx = it.width }
            // Drawn text has no semantics of its own; screen readers get the panel as one description.
            .semantics { contentDescription = description },
    ) {
        drawRoundRect(color = panelFill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(panelCorner, panelCorner))
        val l = layout ?: return@Canvas
        var y = padV
        for (t in listOfNotNull(l.bits, l.title, l.synopsis)) {
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(padH, y))
            y += t.size.height
        }
        var ry = padV
        for (t in listOfNotNull(l.nextLabel, l.nextTitle)) {
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(size.width - padH - t.size.width, ry))
            ry += t.size.height
        }
        l.hint?.let { t ->
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(size.width - padH - t.size.width, size.height - padV - t.size.height))
        }
    }
}

/** What the panel shows besides the cursor (see GuideDetailsPanel). */
private data class PanelInputs(
    val rows: List<ChannelEntity>,
    val now: Long,
    val reminderSlots: Set<tv.own.owntv.core.sync.work.ProgramReminders.Key>,
    val cacheRevision: Int,
    val strings: GuideNav.PanelStrings,
)

/** The panel's laid-out texts (null = not shown). */
private class PanelLayout(
    val bits: androidx.compose.ui.text.TextLayoutResult?,
    val title: androidx.compose.ui.text.TextLayoutResult,
    val synopsis: androidx.compose.ui.text.TextLayoutResult?,
    val nextLabel: androidx.compose.ui.text.TextLayoutResult?,
    val nextTitle: androidx.compose.ui.text.TextLayoutResult?,
    val hint: androidx.compose.ui.text.TextLayoutResult?,
    val description: String,
)

private class PanelStyles(colors: tv.own.owntv.ui.theme.OwnTVColors, typography: androidx.tv.material3.Typography) {
    val bits = typography.labelMedium.copy(color = colors.onSurfaceVariant)
    val bitsOnAir = typography.labelMedium.copy(color = colors.primary)
    val title = typography.titleMedium.copy(color = colors.onSurface)
    val synopsis = typography.bodySmall.copy(color = colors.onSurfaceVariant)
    val nextLabel = typography.labelMedium.copy(color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
    val nextTitle = typography.bodyMedium.copy(color = colors.onSurface)
    val hint = typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val empty = typography.bodyMedium.copy(color = colors.onSurfaceVariant)
}

/** Lays out the panel's texts for a panel [widthPx] wide. Runs on the guide's text thread. */
private fun layoutPanel(
    text: GuideNav.PanelText,
    widthPx: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
    styles: PanelStyles,
    padH: Float,
    gap: Float,
    rightMax: Float,
    // Joins the parts of the screen-reader description (the localized details separator).
    separator: String,
): PanelLayout {
    val inner = (widthPx - padH * 2f).coerceAtLeast(1f)
    fun m(s: String, style: androidx.compose.ui.text.TextStyle, maxW: Float, lines: Int = 1) =
        measurer.measure(s, style, overflow = TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
    if (text.programmeId == null) {
        return PanelLayout(null, m(text.title, styles.empty, inner), null, null, null, null, text.title)
    }
    val rightW = minOf(rightMax, inner / 3f)
    val leftW = (inner - rightW - gap).coerceAtLeast(1f)
    return PanelLayout(
        bits = text.bits.takeIf { it.isNotEmpty() }?.let { m(it, if (text.onAir) styles.bitsOnAir else styles.bits, leftW) },
        title = m(text.title, styles.title, leftW),
        synopsis = text.synopsis?.takeIf { it.isNotBlank() }?.let { m(it, styles.synopsis, leftW, lines = 3) },
        nextLabel = text.nextLabel?.let { m(it, styles.nextLabel, rightW) },
        nextTitle = text.nextTitle?.let { m(it, styles.nextTitle, rightW) },
        hint = m(text.hint, styles.hint, rightW),
        description = listOfNotNull(text.title, text.bits, text.nextLabel, text.nextTitle).joinToString(separator),
    )
}


/**
 * Phase 4: the TV Guide's video preview. Shows the channel still playing from Live TV, hosted by the
 * shell's shared player ([video]); it never follows the cursor. With nothing playing it says how to start
 * one, and starts nothing itself. The picture is a SurfaceView: nothing is drawn over it and it is not
 * clipped, so it stays on the hardware video path.
 */
@Composable
private fun GuidePreview(channel: ChannelEntity?, video: (@Composable (Modifier) -> Unit)?, width: androidx.compose.ui.unit.Dp) {
    val colors = OwnTVTheme.colors
    Column(Modifier.width(width).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (video != null) {
                video(Modifier.fillMaxSize())
            } else {
                Text(
                    stringResource(R.string.content_epg_preview_idle),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (video != null && channel != null) {
            Text(
                stringResource(R.string.content_epg_preview_playing, channel.name),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Guide → More: the advanced actions the header used to carry — sort, auto-match, search, the
 * empty-channel filter and the EPG statistics. Back closes it before anything else.
 */
@Composable
private fun GuideOptionsDialog(
    sortLabel: String,
    onCycleSort: () -> Unit,
    matching: Boolean,
    onAutoMatch: () -> Unit,
    statsText: String?,
    showAllChannels: Boolean,
    onToggleShowAll: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    BackHandler { onDismiss() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel()) {
            Text(stringResource(R.string.content_epg_options_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            statsText?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
            Spacer(Modifier.height(16.dp))
            // Sort cycles in place, so the label shows the order the guide now uses.
            OwnTVButton(stringResource(R.string.content_epg_sort_button, sortLabel), onClick = onCycleSort, icon = OwnTVIcon.SORT, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth().focusRequester(firstFocus))
            Spacer(Modifier.height(10.dp))
            if (!matching) {
                OwnTVButton(stringResource(R.string.content_epg_match_button), onClick = onAutoMatch, icon = OwnTVIcon.EPG, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            OwnTVButton(stringResource(R.string.content_epg_search_channels), onClick = onSearch, icon = OwnTVIcon.SEARCH, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OwnTVButton(
                stringResource(if (showAllChannels) R.string.content_epg_hide_empty_channels else R.string.content_epg_show_all_channels),
                onClick = onToggleShowAll, icon = OwnTVIcon.LIVE_TV, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OwnTVButton(stringResource(R.string.settings_close), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY)
        }
    }
}

/** How long the cursor rests on a programme before its synopsis is read from the database. */
private const val SYNOPSIS_SETTLE_MS = 150L

/** Rows prepared beyond the screen on each side (data only — nothing is composed ahead). */
private const val ROW_PREP_MARGIN = 6

/** Rows whose cell texts are kept prepared (the rows in view plus a margin). */
private const val ROW_TEXT_ROWS = 24
