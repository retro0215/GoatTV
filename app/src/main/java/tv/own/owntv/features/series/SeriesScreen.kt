package tv.own.owntv.features.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.rememberUpdatedState
import tv.own.owntv.ui.components.ResumeDialog
import tv.own.owntv.features.subtitles.SubtitleDeletePopup
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.features.customize.MoveToCategoryDialog
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.features.live.LiveKey
import tv.own.owntv.features.live.displayLabel
import tv.own.owntv.features.settings.data.PanelSection
import tv.own.owntv.features.settings.data.BrowseColumnGap
import tv.own.owntv.features.settings.data.BrowseColumnDividerSpace
import tv.own.owntv.features.settings.data.BrowseContainerPadding
import tv.own.owntv.features.settings.data.browsePanelGapTotal
import tv.own.owntv.features.settings.data.computePanelWidths
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.features.settings.rememberPanelShares
import tv.own.owntv.features.shell.components.CategoryRail
import tv.own.owntv.features.shell.components.MediaDetailsScreen
import tv.own.owntv.features.shell.components.PreviewPane
import tv.own.owntv.features.shell.components.RailCategory
import tv.own.owntv.ui.components.MoveOrderOverlay
import tv.own.owntv.ui.components.InAppToast
import tv.own.owntv.ui.components.rememberInAppToast
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.PosterCard
import tv.own.owntv.ui.components.SetTmdbNameDialog
import tv.own.owntv.ui.components.TrailerPlayerScreen
import tv.own.owntv.ui.components.chNavPaging
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.gridFocusTarget
import tv.own.owntv.ui.components.SearchBar
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.components.SortChip
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.PreviewPanelFill
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.ProgressBar
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.format.localizedInteger

@Composable
fun SeriesScreen(
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onContentScrolled: (Boolean) -> Unit = {},
) {
    val vm: SeriesViewModel = koinViewModel()
    val openedSeries by vm.openedSeries.collectAsStateWithLifecycle()

    if (openedSeries != null) {
        SeriesDetailsPage(
            series = openedSeries!!,
            vm = vm,
            onBack = vm::closeSeries,
            onFullscreen = onFullscreen,
        )
    } else {
        SeriesGrid(
            vm = vm,
            onFullscreen = onFullscreen,
            onChildFocused = onChildFocused,
            restoreFocus = restoreFocus,
            onRestored = onRestored,
            onContentScrolled = onContentScrolled,
            modifier = modifier,
        )
    }
}

@Composable
private fun SeriesGrid(
    vm: SeriesViewModel,
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    restoreFocus: Boolean,
    onRestored: () -> Unit,
    onContentScrolled: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val refetchingTmdbMessage = stringResource(R.string.content_refetching_tmdb)
    val researchingTmdbMessage = stringResource(R.string.content_researching_tmdb)
    val railItems by vm.railItems.collectAsStateWithLifecycle()
    val selectedKey by vm.selectedKey.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val sortMode by vm.sortMode.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val selectedSeries by vm.selectedSeries.collectAsStateWithLifecycle()
    val selectedSeriesMeta by vm.selectedSeriesMeta.collectAsStateWithLifecycle()
    val metadataMode by vm.metadataMode.collectAsStateWithLifecycle()
    val moveState by vm.moveState.collectAsStateWithLifecycle()
    var contextSeries by remember { mutableStateOf<SeriesEntity?>(null) }
    var moveItem by remember { mutableStateOf<SeriesEntity?>(null) }
    var moveOriginKey by remember { mutableStateOf<String?>(null) }
    var moveOriginName by remember { mutableStateOf<String?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var detailsSeries by remember { mutableStateOf<SeriesEntity?>(null) }
    var setTmdbNameSeries by remember { mutableStateOf<SeriesEntity?>(null) }
    var trailerVideoKey by remember { mutableStateOf<String?>(null) }
    val toast = rememberInAppToast()
    var contextSeriesId by remember { mutableStateOf<Long?>(null) }
    var contextSeriesIndex by remember { mutableStateOf(-1) }
    val contextFocus = remember { FocusRequester() }
    val series = vm.series.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val selFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }

    val settingsVm: tv.own.owntv.features.settings.SettingsViewModel = koinViewModel()
    val chNavEnabled by settingsVm.chNavEnabled.collectAsStateWithLifecycle()
    val chNavUpSkip by settingsVm.chNavUpSkip.collectAsStateWithLifecycle()
    val chNavDownSkip by settingsVm.chNavDownSkip.collectAsStateWithLifecycle()
    val rememberSeries by settingsVm.rememberLastSeries.collectAsStateWithLifecycle()

    val perCategoryGrid = remember { mutableStateMapOf<LiveKey, LazyGridState>() }
    val perCategoryList = remember { mutableStateMapOf<LiveKey, LazyListState>() }
    val effectiveGridState = if (rememberSeries) perCategoryGrid.getOrPut(selectedKey) { LazyGridState() } else gridState
    val effectiveListState = if (rememberSeries) perCategoryList.getOrPut(selectedKey) { LazyListState() } else listState

    val catListState = rememberLazyListState()
    val chromeScrollThresholdPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val contentScrolled by remember(
        effectiveGridState,
        effectiveListState,
        catListState,
        viewMode,
        chromeScrollThresholdPx,
    ) {
        androidx.compose.runtime.derivedStateOf {
            val contentMoved = if (viewMode == SettingsRepository.VodViewMode.GRID) {
                effectiveGridState.firstVisibleItemIndex > 0 ||
                    effectiveGridState.firstVisibleItemScrollOffset > chromeScrollThresholdPx
            } else {
                effectiveListState.firstVisibleItemIndex > 0 ||
                    effectiveListState.firstVisibleItemScrollOffset > chromeScrollThresholdPx
            }
            contentMoved || catListState.firstVisibleItemIndex > 0 ||
                catListState.firstVisibleItemScrollOffset > chromeScrollThresholdPx
        }
    }
    LaunchedEffect(contentScrolled) { onContentScrolled(contentScrolled) }

    var gridPaneFocused by remember { mutableStateOf(false) }
    var railPaneFocused by remember { mutableStateOf(false) }

    LaunchedEffect(restoreFocus, series.itemCount) {
        if (!restoreFocus || series.itemCount == 0) return@LaunchedEffect
        val sel = selectedSeries
        val idx = if (sel != null) series.itemSnapshotList.items.indexOfFirst { it.id == sel.id } else -1
        if (idx >= 0) {
            if (viewMode == SettingsRepository.VodViewMode.LIST) {
                runCatching { effectiveListState.scrollToItem(idx) }
            } else {
                runCatching { effectiveGridState.scrollToItem(idx) }
            }
            delay(60)
            runCatching { selFocus.requestFocus() }
        }
        onRestored()
    }

    LaunchedEffect(contextSeries, moveItem, creatingCategory) {
        if (contextSeries != null) return@LaunchedEffect
        if (detailsSeries != null) return@LaunchedEffect
        if (setTmdbNameSeries != null) return@LaunchedEffect
        if (trailerVideoKey != null) return@LaunchedEffect
        if (moveItem != null || creatingCategory) return@LaunchedEffect
        val targetId = contextSeriesId
        if (targetId == null) { contextSeriesIndex = -1; return@LaunchedEffect }
        val items = series.itemSnapshotList.items
        val idx = items.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            runCatching {
                if (viewMode == SettingsRepository.VodViewMode.LIST) effectiveListState.scrollToItem(idx)
                else effectiveGridState.scrollToItem(idx)
            }
            withFrameNanos { }
            runCatching { contextFocus.requestFocus() }
        } else {
            withFrameNanos { }
            val settled = series.itemSnapshotList.items.filterNotNull()
            if (settled.isEmpty()) {
                runCatching { firstItemFocus.requestFocus() }
            } else {
                val neighbor = settled.getOrNull(contextSeriesIndex.coerceAtLeast(0)) ?: settled.last()
                val neighborIdx = items.indexOfFirst { it.id == neighbor.id }.coerceAtLeast(0)
                runCatching {
                    if (viewMode == SettingsRepository.VodViewMode.LIST) effectiveListState.scrollToItem(neighborIdx)
                    else effectiveGridState.scrollToItem(neighborIdx)
                }
                contextSeriesId = neighbor.id
                withFrameNanos { }
                runCatching { contextFocus.requestFocus() }
            }
        }
        contextSeriesIndex = -1
    }

    val panelShares = rememberPanelShares(PanelSection.SERIES, settingsVm)
    val selectedIndex = railItems.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val selectedItem = railItems.getOrNull(selectedIndex)
    val selectedLabel = selectedItem?.displayLabel(R.string.content_category_all_series) ?: stringResource(R.string.content_category_all_series)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel(fillColor = ContentPanelFill)
            .padding(BrowseContainerPadding)
            .onFocusChanged { if (it.hasFocus) onChildFocused() },
    ) {
        val previewVisible = panelShares?.preview != 0
        val innerGapTotal = browsePanelGapTotal(previewVisible)
        val panels = panelShares?.let { computePanelWidths(it, maxWidth, innerGapTotal) }
        Row(modifier = Modifier.fillMaxSize()) {
            CategoryRail(
                width = panels?.category ?: Dimens.RailWidthFixed,
                categories = railItems.map { RailCategory(it.displayLabel(R.string.content_category_all_series), it.icon, showGenreDot = it.key is LiveKey.Folder) },
                selectedIndex = selectedIndex,
                onSelect = { idx -> railItems.getOrNull(idx)?.let { vm.select(it.key) } },
                listState = catListState,
                showPanel = false,
                modifier = Modifier
                    .onFocusChanged { railPaneFocused = it.hasFocus }
                    .chNavPaging(
                        enabled = chNavEnabled,
                        upSkip = chNavUpSkip,
                        downSkip = chNavDownSkip,
                        isFocused = { railPaneFocused },
                        lastIndex = { railItems.size - 1 },
                        currentTargetIndex = { selectedIndex },
                        onJumpToIndex = { idx -> railItems.getOrNull(idx)?.let { vm.select(it.key) } },
                    ),
            )

            Spacer(Modifier.width(BrowseColumnGap))
            Box(Modifier.width(BrowseColumnDividerSpace).fillMaxHeight().padding(vertical = 2.dp).background(OwnTVTheme.colors.outlineVariant.copy(alpha = 0.35f)))
            Spacer(Modifier.width(BrowseColumnGap))

            Column(
                modifier = Modifier
                    .then(if (panels != null) Modifier.width(panels.list) else Modifier.weight(1.8f))
                    .fillMaxSize()
                    .onFocusChanged { gridPaneFocused = it.hasFocus }
                    .chNavPaging(
                        enabled = chNavEnabled,
                        upSkip = chNavUpSkip,
                        downSkip = chNavDownSkip,
                        isFocused = { gridPaneFocused },
                        longPressEnabled = { selectedKey != LiveKey.All },
                        lastIndex = { series.itemCount - 1 },
                        currentTargetIndex = {
                            val sel = selectedSeries
                            if (sel != null) {
                                val idx = series.itemSnapshotList.items.indexOfFirst { it.id == sel.id }
                                if (idx >= 0) idx
                                else if (viewMode == SettingsRepository.VodViewMode.GRID) effectiveGridState.firstVisibleItemIndex
                                else effectiveListState.firstVisibleItemIndex
                            } else {
                                if (viewMode == SettingsRepository.VodViewMode.GRID) effectiveGridState.firstVisibleItemIndex
                                else effectiveListState.firstVisibleItemIndex
                            }
                        },
                        onJumpToIndex = { idx ->
                            scope.launch {
                                val item = series.itemSnapshotList.items.getOrNull(idx)
                                if (viewMode == SettingsRepository.VodViewMode.GRID) {
                                    runCatching { effectiveGridState.scrollToItem(idx) }
                                } else {
                                    runCatching { effectiveListState.scrollToItem(idx) }
                                }
                                withFrameNanos { }
                                if (item != null) {
                                    vm.onSeriesFocused(item)
                                    runCatching { selFocus.requestFocus() }
                                } else {
                                    runCatching { firstItemFocus.requestFocus() }
                                }
                            }
                        },
                    )
                    .focusProperties {
                        onEnter = {
                            if (runCatching { selFocus.requestFocus() }.isFailure) {
                                runCatching { firstItemFocus.requestFocus() }
                            }
                        }
                    }
                    .trapVerticalFocusExit()
                    .focusGroup()
            ) {
                Text(stringResource(R.string.content_section_category, stringResource(R.string.common_nav_series), selectedLabel), style = MaterialTheme.typography.headlineLarge, color = OwnTVTheme.colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(R.plurals.content_count_series, count, selectedLabel, count),
                    style = MaterialTheme.typography.titleMedium, color = OwnTVTheme.colors.primary, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchBar(query = searchQuery, onQueryChange = vm::setSearchQuery, placeholder = stringResource(R.string.content_search_series, selectedLabel), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    SortChip(mode = sortMode, onToggle = vm::toggleSort, playlistLabel = stringResource(R.string.content_provider))
                    Spacer(Modifier.width(10.dp))
                    OwnTVButton(
                        label = stringResource(if (viewMode == SettingsRepository.VodViewMode.GRID) R.string.settings_view_grid else R.string.settings_view_list),
                        onClick = vm::toggleViewMode,
                        icon = if (viewMode == SettingsRepository.VodViewMode.GRID) OwnTVIcon.MENU else OwnTVIcon.SERIES,
                        style = OwnTVButtonStyle.SECONDARY,
                    )
                }
                Spacer(Modifier.height(14.dp))

                if (series.itemCount == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchQuery.isNotBlank()) stringResource(R.string.content_no_series_found, searchQuery.trim()) else stringResource(R.string.content_no_series_here),
                            style = MaterialTheme.typography.bodyLarge, color = OwnTVTheme.colors.onSurfaceVariant,
                        )
                    }
                } else if (viewMode == SettingsRepository.VodViewMode.LIST) {
                    LazyColumn(state = effectiveListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(count = series.itemCount, key = series.itemKey { it.id }, contentType = series.itemContentType { "series" }) { index ->
                            val s = series[index]
                            if (s != null) {
                                SeriesListRow(
                                    series = s,
                                    isFavorite = favoriteIds.contains(s.id),
                                    modifier = Modifier.gridFocusTarget(
                                        itemId = s.id, index = index,
                                        contextId = contextSeriesId, contextFocus = contextFocus,
                                        selectedId = selectedSeries?.id, selectedFocus = selFocus,
                                        firstItemFocus = firstItemFocus,
                                    ),
                                    onFocus = { vm.onSeriesFocused(s) },
                                    onClick = { vm.openSeries(s) },
                                    onLongClick = { contextSeries = s; contextSeriesId = s.id; contextSeriesIndex = index },
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(state = effectiveGridState, columns = GridCells.Adaptive(minSize = 130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(count = series.itemCount, key = series.itemKey { it.id }, contentType = series.itemContentType { "series" }) { index ->
                            val s = series[index]
                            if (s != null) {
                                PosterCard(
                                    posterUrl = s.posterUrl, title = s.name, rating = s.rating, isFavorite = favoriteIds.contains(s.id),
                                    modifier = Modifier.gridFocusTarget(
                                        itemId = s.id, index = index,
                                        contextId = contextSeriesId, contextFocus = contextFocus,
                                        selectedId = selectedSeries?.id, selectedFocus = selFocus,
                                        firstItemFocus = firstItemFocus,
                                    ),
                                    onFocus = { vm.onSeriesFocused(s) },
                                    onClick = { vm.openSeries(s) },
                                    onLongClick = { contextSeries = s; contextSeriesId = s.id; contextSeriesIndex = index },
                                )
                            }
                        }
                    }
                }
            }

            if (previewVisible) {
                Spacer(Modifier.width(BrowseColumnGap))
                Box(modifier = Modifier.then(if (panels != null) Modifier.width(panels.preview) else Modifier.weight(1f)).fillMaxSize().roundedPanel(fillColor = PreviewPanelFill).padding(BrowseContainerPadding)) {
                    SeriesDetailsPane(
                        series = selectedSeries, meta = selectedSeriesMeta?.takeIf { it.seriesId == selectedSeries?.id }?.cache,
                        tmdbWins = metadataMode.tmdbWins, isFavorite = selectedSeries?.let { favoriteIds.contains(it.id) } ?: false,
                        onOpen = { selectedSeries?.let { vm.openSeries(it) } },
                        onToggleFavorite = { selectedSeries?.let { vm.toggleFavorite(it) } },
                    )
                }
            }
        }
    }

    contextSeries?.let { s ->
        val cacheForS = selectedSeriesMeta?.takeIf { it.seriesId == s.id }?.cache
        SeriesContextMenu(
            title = s.name, isFavorite = favoriteIds.contains(s.id),
            canMove = selectedKey is LiveKey.Folder || selectedKey is LiveKey.Custom || selectedKey == LiveKey.Favorites,
            isHistory = selectedKey == LiveKey.History,
            hasTmdbDetails = metadataMode.enrich && cacheForS != null,
            canRefetchTmdb = metadataMode.enrich,
            onShowDetails = { contextSeries = null; detailsSeries = s },
            onToggleFavorite = { vm.toggleFavorite(s); contextSeries = null },
            onMove = { contextSeries = null; vm.enterMoveMode(s, selectedKey) },
            onMoveToCategory = {
                moveOriginKey = when (val k = selectedKey) {
                    is LiveKey.Folder -> vm.folderKey(k.id)
                    is LiveKey.Custom -> k.id
                    LiveKey.Favorites -> ContentOrderEntity.FAV_CONTEXT
                    else -> null
                }
                moveOriginName = railItems.firstOrNull { it.key == selectedKey }?.title
                moveItem = s
                contextSeries = null
            },
            onHide = { vm.hideSeries(s); contextSeries = null },
            onRemoveFromHistory = { vm.removeFromHistory(s.id); contextSeries = null },
            onRefetch = { contextSeries = null; toast.show(refetchingTmdbMessage); vm.refetchSeriesMeta(s) },
            onSetTmdbName = { contextSeries = null; setTmdbNameSeries = s },
            onDismiss = { contextSeries = null },
        )
    }

    val moveTargets by vm.moveTargets.collectAsStateWithLifecycle()
    if (creatingCategory) {
        TextInputDialog(
            title = stringResource(R.string.settings_customize_new_category_title),
            confirmLabel = stringResource(R.string.common_create), allowBlank = false,
            onConfirm = { vm.createCustomCategory(it); creatingCategory = false }, onDismiss = { creatingCategory = false },
        )
    } else {
        moveItem?.let { s ->
            val originKey = moveOriginKey
            if (originKey != null) {
                MoveToCategoryDialog(
                    moveTargets = moveTargets.filterNot { it.id == originKey },
                    originName = moveOriginName ?: stringResource(R.string.settings_customize_this_category),
                    onNewCategory = { creatingCategory = true },
                    onMove = { targetId, keepInOrigin ->
                        vm.moveToCategory(CustomizeKeys.series(s), s.id, originKey, targetId, keepInOrigin)
                        moveItem = null
                    },
                    onDismiss = { moveItem = null },
                )
            }
        }
    }

    detailsSeries?.let { s ->
        val cache = selectedSeriesMeta?.takeIf { it.seriesId == s.id }?.cache
        MediaDetailsScreen(details = buildSeriesDetails(s, cache, metadataMode.tmdbWins), onExit = { detailsSeries = null })
    }

    setTmdbNameSeries?.let { s ->
        var prefill by remember(s.id) { mutableStateOf<SeriesViewModel.TmdbNamePrefill?>(null) }
        LaunchedEffect(s.id) { prefill = vm.seriesTmdbNamePrefill(s) }
        prefill?.let { p ->
            SetTmdbNameDialog(
                initialTitle = p.title, initialYear = p.year, hasOverride = p.hasOverride,
                onSave = { title, year -> setTmdbNameSeries = null; vm.setSeriesTmdbName(s, title, year); toast.show(researchingTmdbMessage) },
                onClear = { setTmdbNameSeries = null; vm.clearSeriesTmdbName(s); toast.show(researchingTmdbMessage) },
                onDismiss = { setTmdbNameSeries = null },
            )
        }
    }

    moveState?.let { ms ->
        MoveOrderOverlay(
            title = stringResource(R.string.content_reorder_series), itemNames = ms.items.map { it.name }, activeIndex = ms.activeIndex,
            onMoveUp = vm::moveUp, onMoveDown = vm::moveDown, onCommit = vm::commitMove, onCancel = vm::cancelMove,
        )
    }

    InAppToast(toast)
}

@Composable
private fun SeriesDetailsPage(
    series: SeriesEntity,
    vm: SeriesViewModel,
    onBack: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val meta by vm.selectedSeriesMeta.collectAsStateWithLifecycle()
    val openedMeta by vm.openedSeriesMeta.collectAsStateWithLifecycle()
    val metadataMode by vm.metadataMode.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val episodeProgress by vm.episodeProgress.collectAsStateWithLifecycle()
    val completedIds by vm.completedEpisodeIds.collectAsStateWithLifecycle()
    val selectedSeason by vm.selectedSeason.collectAsStateWithLifecycle()
    val lastEpId by vm.lastPlayedEpisodeId.collectAsStateWithLifecycle()
    val loading by vm.episodesLoading.collectAsStateWithLifecycle()
    val nextUpId by vm.nextUpEpisodeId.collectAsStateWithLifecycle()
    val episodeViewMode by vm.episodeViewMode.collectAsStateWithLifecycle()
    val seriesOrder by vm.seriesOrder.collectAsStateWithLifecycle()

    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var trailerKey by remember { mutableStateOf<String?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    var contextEpisode by remember { mutableStateOf<EpisodeEntity?>(null) }
    var contextEpisodeSubs by remember { mutableStateOf<List<tv.own.owntv.core.database.dao.LinkedSubtitle>>(emptyList()) }
    var showDeleteSubs by remember { mutableStateOf(false) }
    var resumePrompt by remember { mutableStateOf<Pair<EpisodeEntity, Long>?>(null) }
    val toast = rememberInAppToast()

    BackHandler { onBack() }
    
    LaunchedEffect(series.id) {
        vm.onSeriesFocused(series)
        delay(100)
        runCatching { focus.requestFocus() }
    }

    val backdropUrl = openedMeta?.backdropUrls?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: meta?.cache?.backdropPath?.let { tv.own.owntv.core.metadata.MetadataImages.backdrop(it) }
        ?: series.backdropUrl?.takeIf { it.isNotBlank() }
        ?: series.posterUrl?.takeIf { it.isNotBlank() }

    val trailer = extractYoutubeId(openedMeta?.trailer) ?: meta?.cache?.trailerKey

    val seasons = episodes.map { it.seasonNumber }.distinct().let {
        if (seriesOrder.seasonsDescending) it.sortedDescending() else it.sorted()
    }
    val activeSeason = if (seasons.contains(selectedSeason)) selectedSeason else seasons.firstOrNull() ?: selectedSeason
    val seasonEpisodes = episodes.filter { it.seasonNumber == activeSeason }.let {
        if (seriesOrder.episodesDescending) it.sortedByDescending { e -> e.episodeNumber } else it.sortedBy { e -> e.episodeNumber }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        val episodeMeta by vm.seasonEpisodeMeta.collectAsStateWithLifecycle()

        LazyVerticalGrid(
            columns = if (episodeViewMode == SettingsRepository.VodViewMode.GRID) GridCells.Adaptive(220.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // Hero
            if (!backdropUrl.isNullOrBlank()) {
                item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(21f / 9f)) {
                        AsyncImage(model = backdropUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                    }
                }
            }

            item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp)) {
                    Text(series.name, style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(seriesMetaLine(series, meta?.cache, openedMeta, metadataMode.tmdbWins), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    
                    Spacer(Modifier.height(20.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val nextUp = episodes.find { it.id == nextUpId }
                        if (nextUp != null) {
                            OwnTVButton(
                                label = stringResource(R.string.content_action_play_next, nextUp.seasonNumber, nextUp.episodeNumber),
                                onClick = { 
                                    scope.launch {
                                        val pos = vm.savedPositionMs(nextUp)
                                        if (pos > 0 && vm.resumeMode.value == SettingsRepository.ResumeMode.ASK) {
                                            resumePrompt = nextUp to pos
                                        } else {
                                            vm.playEpisode(nextUp)
                                            onFullscreen()
                                        }
                                    }
                                },
                                icon = OwnTVIcon.PLAY, style = OwnTVButtonStyle.PRIMARY,
                                modifier = Modifier.focusRequester(focus),
                            )
                        }
                        if (!trailer.isNullOrBlank()) {
                            OwnTVButton(label = stringResource(R.string.content_play_trailer), onClick = { trailerKey = trailer }, icon = OwnTVIcon.PLAY, style = OwnTVButtonStyle.SECONDARY,
                                modifier = if (nextUp == null) Modifier.focusRequester(focus) else Modifier
                            )
                        }
                        OwnTVButton(
                            label = if (favoriteIds.contains(series.id)) stringResource(R.string.content_favorited) else stringResource(R.string.content_favorite),
                            onClick = { vm.toggleFavorite(series) }, icon = OwnTVIcon.FAVORITE, style = OwnTVButtonStyle.SECONDARY,
                            modifier = if (nextUp == null && trailer.isNullOrBlank()) Modifier.focusRequester(focus) else Modifier
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    val plot = if (metadataMode.tmdbWins) meta?.cache?.overview ?: openedMeta?.plot else openedMeta?.plot ?: meta?.cache?.overview
                    if (!plot.isNullOrBlank()) {
                        Text(plot, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 10, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(24.dp))
                    }

                    val om = openedMeta
                    if (om?.director != null) {
                        Text(stringResource(R.string.content_media_director), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(om.director, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                    }

                    val cast = tv.own.owntv.core.metadata.MetadataCast.names(meta?.cache?.castJson).takeIf { it.isNotEmpty() }
                        ?: openedMeta?.actors?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    if (cast != null && cast.isNotEmpty()) {
                        Text(stringResource(R.string.content_media_cast), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(cast.take(24).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(24.dp))
                    }

                    if (loading) {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { OwnTVSpinner() }
                    } else if (seasons.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.content_seasons), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                            OwnTVButton(
                                label = stringResource(if (episodeViewMode == SettingsRepository.VodViewMode.GRID) R.string.settings_view_grid else R.string.settings_view_list),
                                onClick = { vm.setEpisodeViewMode(if (episodeViewMode == SettingsRepository.VodViewMode.GRID) SettingsRepository.VodViewMode.LIST else SettingsRepository.VodViewMode.GRID) },
                                icon = if (episodeViewMode == SettingsRepository.VodViewMode.GRID) OwnTVIcon.MENU else OwnTVIcon.SERIES,
                                style = OwnTVButtonStyle.SECONDARY,
                            )
                            Spacer(Modifier.width(10.dp))
                            OwnTVButton(
                                label = stringResource(R.string.content_sorting),
                                onClick = { showSortDialog = true },
                                icon = OwnTVIcon.SORT,
                                style = OwnTVButtonStyle.SECONDARY,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                            lazyItems(seasons) { s ->
                                val doneCount = episodes.count { it.seasonNumber == s && completedIds.contains(it.id) }
                                val totalCount = episodes.count { it.seasonNumber == s }
                                SeasonChip(
                                    season = s, isSelected = s == activeSeason, doneCount = doneCount, totalCount = totalCount,
                                    onClick = { vm.selectSeason(s) },
                                )
                            }
                        }
                    }
                }
            }

            if (!loading && seasons.isNotEmpty()) {
                gridItems(seasonEpisodes, key = { it.id }, span = { if (episodeViewMode == SettingsRepository.VodViewMode.GRID) GridItemSpan(1) else GridItemSpan(maxCurrentLineSpan) }) { ep ->
                    Box(modifier = Modifier.padding(horizontal = 48.dp, vertical = 6.dp)) {
                        if (episodeViewMode == SettingsRepository.VodViewMode.GRID) {
                            EpisodeGridCard(
                                episode = ep,
                                meta = episodeMeta[ep.id],
                                tmdbWins = metadataMode.tmdbWins,
                                isCompleted = completedIds.contains(ep.id),
                                isLastPlayed = ep.id == lastEpId,
                                progress = episodeProgress[ep.id],
                                seriesBackdrop = backdropUrl,
                                seriesName = series.name,
                                onClick = { 
                                    scope.launch {
                                        val pos = vm.savedPositionMs(ep)
                                        if (pos > 0 && vm.resumeMode.value == SettingsRepository.ResumeMode.ASK) {
                                            resumePrompt = ep to pos
                                        } else {
                                            vm.playEpisode(ep)
                                            onFullscreen()
                                        }
                                    }
                                },
                                onLongClick = { contextEpisode = ep },
                            )
                        } else {
                            EpisodeCard(
                                episode = ep,
                                meta = episodeMeta[ep.id],
                                tmdbWins = metadataMode.tmdbWins,
                                isCompleted = completedIds.contains(ep.id),
                                isLastPlayed = ep.id == lastEpId,
                                progress = episodeProgress[ep.id],
                                seriesBackdrop = backdropUrl,
                                seriesName = series.name,
                                onClick = { 
                                    scope.launch {
                                        val pos = vm.savedPositionMs(ep)
                                        if (pos > 0 && vm.resumeMode.value == SettingsRepository.ResumeMode.ASK) {
                                            resumePrompt = ep to pos
                                        } else {
                                            vm.playEpisode(ep)
                                            onFullscreen()
                                        }
                                    }
                                },
                                onLongClick = { contextEpisode = ep },
                            )
                        }
                    }
                }
            }
        }
    }

    trailerKey?.let { key -> TrailerPlayerScreen(videoKey = key, onExit = { trailerKey = null }) }

    if (showSortDialog) {
        var localOrder by remember { mutableStateOf(seriesOrder) }
        SeriesSortingDialog(
            order = localOrder,
            onOrderChange = { localOrder = it },
            onApply = { vm.setSeriesOrder(localOrder.seasonsDescending, localOrder.episodesDescending); showSortDialog = false },
            onDismiss = { showSortDialog = false }
        )
    }

    LaunchedEffect(contextEpisode?.id) {
        contextEpisodeSubs = contextEpisode?.let { runCatching { vm.downloadedSubtitles(it) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    contextEpisode?.let { ep ->
        val watched = completedIds.contains(ep.id)
        EpisodeContextMenu(
            title = ep.name.ifBlank { stringResource(R.string.content_episode_n, ep.episodeNumber) },
            watched = watched,
            onPlay = { 
                scope.launch {
                    val pos = vm.savedPositionMs(ep)
                    vm.playEpisode(ep, pos)
                    onFullscreen()
                }
                contextEpisode = null
            },
            onRestart = { vm.playEpisode(ep, 0); onFullscreen(); contextEpisode = null },
            onToggleWatched = { if (watched) vm.markEpisodeUnwatched(ep) else vm.markEpisodeWatched(ep); contextEpisode = null },
            onDownload = { vm.downloadEpisode(ep); contextEpisode = null },
            onDeleteSubtitles = if (contextEpisodeSubs.isNotEmpty()) ({ showDeleteSubs = true }) else null,
            onDismiss = { contextEpisode = null },
        )
    }

    if (showDeleteSubs) {
        val ep = contextEpisode
        if (ep == null || contextEpisodeSubs.isEmpty()) { showDeleteSubs = false } else {
            SubtitleDeletePopup(
                contentTitle = ep.name.ifBlank { stringResource(R.string.content_episode_n, ep.episodeNumber) },
                items = contextEpisodeSubs,
                onDelete = { sub ->
                    vm.deleteSubtitle(sub.cacheId)
                    contextEpisodeSubs = contextEpisodeSubs.filterNot { it.cacheId == sub.cacheId }
                    if (contextEpisodeSubs.isEmpty()) { showDeleteSubs = false; contextEpisode = null }
                },
                onDismiss = { showDeleteSubs = false },
            )
        }
    }

    resumePrompt?.let { (ep, pos) ->
        ResumeDialog(
            positionMs = pos,
            onResume = { resumePrompt = null; vm.playEpisode(ep, pos); onFullscreen() },
            onStartOver = { resumePrompt = null; vm.playEpisode(ep, 0); onFullscreen() },
            onDismiss = { resumePrompt = null },
        )
    }

    InAppToast(toast)
}

@Composable
private fun SeasonChip(season: Int, isSelected: Boolean, doneCount: Int, totalCount: Int, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick, selected = isSelected, shape = RoundedCornerShape(12.dp),
        surface = GlassSurface.CARDS,
        selectedContainerColor = colors.primary,
        focusedContainerColor = if (isSelected) colors.primary else colors.primaryContainer,
    ) { focused ->
        val contentColor = when {
            isSelected -> colors.onPrimary
            focused -> colors.onPrimaryContainer
            else -> colors.textPrimary
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.content_season_n, season), style = MaterialTheme.typography.labelLarge, color = contentColor)
            if (totalCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.content_count_fraction, doneCount, totalCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) colors.onPrimary.copy(alpha = 0.7f) else colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: EpisodeEntity,
    meta: tv.own.owntv.core.database.entity.MetadataCacheEntity?,
    tmdbWins: Boolean,
    isCompleted: Boolean,
    isLastPlayed: Boolean,
    progress: tv.own.owntv.core.database.entity.PlaybackProgressEntity?,
    seriesBackdrop: String?,
    seriesName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = OwnTVTheme.colors
    val stillUrl = if (tmdbWins) {
        meta?.backdropPath?.let { tv.own.owntv.core.metadata.MetadataImages.backdrop(it) } ?: episode.stillUrl ?: seriesBackdrop
    } else {
        episode.stillUrl ?: meta?.backdropPath?.let { tv.own.owntv.core.metadata.MetadataImages.backdrop(it) } ?: seriesBackdrop
    }

    val title = if (tmdbWins) {
        meta?.title?.takeIf { it.isNotBlank() } ?: cleanEpisodeTitle(episode.name, episode.episodeNumber, seriesName)
    } else {
        val cleaned = cleanEpisodeTitle(episode.name, episode.episodeNumber, seriesName)
        if (cleaned.isNotBlank()) cleaned else meta?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.content_episode_n, episode.episodeNumber)
    }

    val plot = if (tmdbWins) {
        meta?.overview?.takeIf { it.isNotBlank() } ?: episode.plot
    } else {
        episode.plot?.takeIf { it.isNotBlank() } ?: meta?.overview
    }

    val rating = if (tmdbWins) {
        meta?.rating?.takeIf { it > 0 } ?: episode.rating
    } else {
        episode.rating?.takeIf { it > 0 } ?: meta?.rating
    }

    val durationSecs = episode.durationSecs ?: 0
    val airDate = episode.releaseDate ?: meta?.year?.toString()

    FocusableSurface(
        onClick = onClick, onLongClick = onLongClick, shape = RoundedCornerShape(12.dp), surface = GlassSurface.CARDS,
        modifier = Modifier.fillMaxWidth()
    ) { focused ->
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Still
            Box(
                modifier = Modifier
                    .size(width = 160.dp, height = 90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerLowest),
                contentAlignment = Alignment.Center
            ) {
                if (!stillUrl.isNullOrBlank()) {
                    AsyncImage(model = stillUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    OwnTVIcon(OwnTVIcon.SERIES, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
                }
                
                if (isCompleted) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                    Box(
                        modifier = Modifier.align(Alignment.Center).size(32.dp).clip(RoundedCornerShape(50)).background(colors.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        OwnTVIcon(OwnTVIcon.WATCHED_CHECK, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium,
                        color = if (focused || isLastPlayed) colors.primary else colors.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isLastPlayed && !isCompleted) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.common_resume),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors.primary.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Text(
                    stringResource(R.string.content_season_episode, episode.seasonNumber, episode.episodeNumber),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant
                )

                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        plot, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified // Default should be fine
                    )
                }

                Spacer(Modifier.height(8.dp))
                val metaParts = mutableListOf<String>()
                if (durationSecs > 0) {
                    val m = durationSecs / 60
                    metaParts.add(stringResource(R.string.content_duration_minutes, m))
                }
                if (rating != null && rating > 0) metaParts.add(stringResource(R.string.content_rating, rating))
                if (!airDate.isNullOrBlank()) metaParts.add(airDate)
                
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(stringResource(R.string.content_genres_separator)),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                if (progress != null && !isCompleted) {
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(progress.positionMs.toFloat() / progress.durationMs, color = colors.primary, height = 3.dp)
                }
            }
        }
    }
}

/**
 * Normalizes redundant provider titles like "Series Name S01E01 - Episode Title" -> "Episode Title".
 * If the result is empty or just the episode number, returns a blank so caller can fallback.
 */
private fun cleanEpisodeTitle(raw: String, episodeNumber: Int, seriesName: String): String {
    if (raw.isBlank()) return ""
    
    var result = raw.trim()
    
    // 1. Remove the series name if present anywhere (case-insensitive)
    if (seriesName.isNotBlank()) {
        result = result.replace(seriesName, "", ignoreCase = true).trim()
    }
    
    // 2. Remove year if present, e.g. "(2016)"
    result = Regex("""\(\d{4}\)""").replace(result, "").trim()
    
    // 3. Remove "S01E01", "S1E1", "1x01", etc.
    result = Regex("""(?i)s\d+e\d+| \d+x\d+|\d+x\d+""").replace(result, "").trim()
    
    // 4. Remove leading/trailing dashes, dots, spaces, colons, underscores
    result = result.trim { it <= ' ' || it == '-' || it == '·' || it == '•' || it == ':' || it == '_' || it == '|' }
    
    // 5. If the title still looks like it contains the episode number at the end, e.g. "Episode 1",
    // we return blank so the caller uses the localized "Episode N" fallback.
    if (result.equals("Episode $episodeNumber", ignoreCase = true) || 
        result.equals("Ep $episodeNumber", ignoreCase = true) ||
        result.equals("$episodeNumber", ignoreCase = true)) return ""
    
    // 6. If it starts with "Episode N" or "Ep N", strip it if there's more content
    val epPrefix = Regex("""(?i)^(Episode|Ep)\s*\d+\s*[-·•:_]\s*""")
    if (epPrefix.containsMatchIn(result)) {
        result = epPrefix.replace(result, "").trim()
    }

    return result
}

@Composable
private fun EpisodeGridCard(
    episode: EpisodeEntity,
    meta: tv.own.owntv.core.database.entity.MetadataCacheEntity?,
    tmdbWins: Boolean,
    isCompleted: Boolean,
    isLastPlayed: Boolean,
    progress: tv.own.owntv.core.database.entity.PlaybackProgressEntity?,
    seriesBackdrop: String?,
    seriesName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = OwnTVTheme.colors
    val stillUrl = if (tmdbWins) {
        meta?.backdropPath?.let { tv.own.owntv.core.metadata.MetadataImages.backdrop(it) } ?: episode.stillUrl ?: seriesBackdrop
    } else {
        episode.stillUrl ?: meta?.backdropPath?.let { tv.own.owntv.core.metadata.MetadataImages.backdrop(it) } ?: seriesBackdrop
    }

    val title = if (tmdbWins) {
        meta?.title?.takeIf { it.isNotBlank() } ?: cleanEpisodeTitle(episode.name, episode.episodeNumber, seriesName)
    } else {
        val cleaned = cleanEpisodeTitle(episode.name, episode.episodeNumber, seriesName)
        if (cleaned.isNotBlank()) cleaned else meta?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.content_episode_n, episode.episodeNumber)
    }

    FocusableSurface(
        onClick = onClick, onLongClick = onLongClick, shape = RoundedCornerShape(12.dp), surface = GlassSurface.CARDS,
        modifier = Modifier.fillMaxWidth()
    ) { focused ->
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            // Still
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerLowest),
                contentAlignment = Alignment.Center
            ) {
                if (!stillUrl.isNullOrBlank()) {
                    AsyncImage(model = stillUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    OwnTVIcon(OwnTVIcon.SERIES, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
                }
                
                if (isCompleted) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                    Box(
                        modifier = Modifier.align(Alignment.Center).size(32.dp).clip(RoundedCornerShape(50)).background(colors.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        OwnTVIcon(OwnTVIcon.WATCHED_CHECK, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                
                if (progress != null && !isCompleted) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ProgressBar(progress.positionMs.toFloat() / progress.durationMs, color = colors.primary, height = 3.dp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                title, style = MaterialTheme.typography.titleSmall,
                color = if (focused || isLastPlayed) colors.primary else colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            
            Text(
                stringResource(R.string.content_season_episode, episode.seasonNumber, episode.episodeNumber),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SeriesSortingDialog(
    order: SeriesViewModel.SeriesOrder,
    onOrderChange: (SeriesViewModel.SeriesOrder) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    
    Box(modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.dialogPanel().width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.content_sorting), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            
            Text(stringResource(R.string.content_season_order), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OwnTVButton(
                    label = stringResource(R.string.content_ascending),
                    onClick = { onOrderChange(order.copy(seasonsDescending = false)) },
                    style = if (!order.seasonsDescending) OwnTVButtonStyle.PRIMARY else OwnTVButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                OwnTVButton(
                    label = stringResource(R.string.content_descending),
                    onClick = { onOrderChange(order.copy(seasonsDescending = true)) },
                    style = if (order.seasonsDescending) OwnTVButtonStyle.PRIMARY else OwnTVButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.content_episode_order), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OwnTVButton(
                    label = stringResource(R.string.content_ascending),
                    onClick = { onOrderChange(order.copy(episodesDescending = false)) },
                    style = if (!order.episodesDescending) OwnTVButtonStyle.PRIMARY else OwnTVButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f)
                )
                OwnTVButton(
                    label = stringResource(R.string.content_descending),
                    onClick = { onOrderChange(order.copy(episodesDescending = true)) },
                    style = if (order.episodesDescending) OwnTVButtonStyle.PRIMARY else OwnTVButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OwnTVButton(stringResource(R.string.settings_apply), onClick = onApply, style = OwnTVButtonStyle.PRIMARY, modifier = Modifier.weight(1f))
                OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EpisodeContextMenu(
    title: String, watched: Boolean, onPlay: () -> Unit, onRestart: () -> Unit,
    onToggleWatched: () -> Unit, onDownload: () -> Unit,
    onDeleteSubtitles: (() -> Unit)? = null, onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    Box(modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.dialogPanel(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            OwnTVButton(
                stringResource(R.string.content_play), onClick = onPlay, style = OwnTVButtonStyle.PRIMARY, icon = OwnTVIcon.PLAY, 
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            OwnTVButton(stringResource(R.string.common_start_over), onClick = onRestart, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            OwnTVButton(
                if (watched) stringResource(R.string.content_mark_unwatched) else stringResource(R.string.content_mark_watched),
                onClick = onToggleWatched, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth()
            )
            OwnTVButton(stringResource(R.string.content_download), onClick = onDownload, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.DOWNLOADS, modifier = Modifier.fillMaxWidth())
            onDeleteSubtitles?.let {
                OwnTVButton(stringResource(R.string.content_delete_subtitles), onClick = it, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.SUBTITLE, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
            OwnTVButton(stringResource(R.string.content_close), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
    @Composable
private fun SeriesDetailsPane(
    series: SeriesEntity?, meta: tv.own.owntv.core.database.entity.MetadataCacheEntity?,
    tmdbWins: Boolean, isFavorite: Boolean, onOpen: () -> Unit, onToggleFavorite: () -> Unit,
) {
    if (series == null) { PreviewPane(hint = stringResource(R.string.content_focus_series)); return }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.GapLarge)) {
        val backdrop = meta?.backdropPath?.let { tv.own.owntv.core.metadata.MetadataImages.backdrop(it) } ?: series.posterUrl
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.height(16.dp))
        }
        Text(series.name, style = MaterialTheme.typography.titleLarge, color = OwnTVTheme.colors.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(seriesMetaLine(series, meta, null, tmdbWins), style = MaterialTheme.typography.bodyMedium, color = OwnTVTheme.colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwnTVButton(label = stringResource(R.string.common_open), onClick = onOpen, icon = OwnTVIcon.MENU, style = OwnTVButtonStyle.PRIMARY)
            OwnTVButton(label = if (isFavorite) stringResource(R.string.content_favorited) else stringResource(R.string.content_favorite), onClick = onToggleFavorite, icon = OwnTVIcon.FAVORITE, style = OwnTVButtonStyle.SECONDARY)
        }
        val plot = if (tmdbWins) meta?.overview ?: series.plot else series.plot?.takeIf { it.isNotBlank() } ?: meta?.overview
        if (!plot.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(plot, style = MaterialTheme.typography.bodySmall, color = OwnTVTheme.colors.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SeriesContextMenu(
    title: String, isFavorite: Boolean, canMove: Boolean, isHistory: Boolean, hasTmdbDetails: Boolean,
    canRefetchTmdb: Boolean, onShowDetails: () -> Unit, onToggleFavorite: () -> Unit,
    onMove: () -> Unit, onMoveToCategory: () -> Unit, onHide: () -> Unit, onRemoveFromHistory: () -> Unit,
    onRefetch: () -> Unit, onSetTmdbName: () -> Unit, onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    Box(modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.dialogPanel(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            OwnTVButton(
                if (isFavorite) stringResource(R.string.content_remove_favourite) else stringResource(R.string.content_add_favourite),
                onClick = onToggleFavorite, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.FAVORITE, modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            if (canMove) OwnTVButton(stringResource(R.string.content_move), onClick = onMove, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            if (canMove) OwnTVButton(stringResource(R.string.content_move_to_category), onClick = onMoveToCategory, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            if (isHistory) OwnTVButton(stringResource(R.string.content_remove_history), onClick = onRemoveFromHistory, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            OwnTVButton(stringResource(R.string.common_hide), onClick = onHide, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            if (hasTmdbDetails) OwnTVButton(stringResource(R.string.content_tmdb_details), onClick = onShowDetails, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.MENU, modifier = Modifier.fillMaxWidth())
            if (canRefetchTmdb) {
                OwnTVButton(stringResource(R.string.content_refetch_tmdb), onClick = onRefetch, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
                OwnTVButton(stringResource(R.string.content_set_tmdb_name), onClick = onSetTmdbName, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
            OwnTVButton(stringResource(R.string.content_close), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun seriesMetaLine(series: SeriesEntity, meta: tv.own.owntv.core.database.entity.MetadataCacheEntity?, providerMeta: tv.own.owntv.core.parser.XtProviderMetadata?, tmdbWins: Boolean): String {
    val parts = mutableListOf<String>()
    val year = if (tmdbWins) meta?.year ?: providerMeta?.year ?: series.year else providerMeta?.year ?: series.year ?: meta?.year
    val rating = if (tmdbWins) meta?.rating?.takeIf { it > 0 } ?: providerMeta?.rating?.takeIf { it > 0 } ?: series.rating?.takeIf { it > 0 }
    else providerMeta?.rating?.takeIf { it > 0 } ?: series.rating?.takeIf { it > 0 } ?: meta?.rating?.takeIf { it > 0 }
    year?.let { parts.add(localizedInteger(it, grouping = false)) }
    rating?.let { parts.add(stringResource(R.string.content_rating, it)) }
    return parts.joinToString(stringResource(R.string.content_metadata_separator))
}

@Composable
private fun buildSeriesDetails(series: SeriesEntity, meta: tv.own.owntv.core.database.entity.MetadataCacheEntity?, tmdbWins: Boolean): tv.own.owntv.features.shell.components.MediaDetailsUi {
    val poster = if (tmdbWins) tv.own.owntv.core.metadata.MetadataImages.poster(meta?.posterPath) ?: series.posterUrl else series.posterUrl
    val backdrop = tv.own.owntv.core.metadata.MetadataImages.backdrop(meta?.backdropPath) ?: series.backdropUrl
    return tv.own.owntv.features.shell.components.MediaDetailsUi(
        title = series.name, backdropUrl = backdrop, posterUrl = poster, metaLine = seriesMetaLine(series, meta, null, tmdbWins),
        genres = jsonList(meta?.genresJson), plot = if (tmdbWins) meta?.overview ?: series.plot else series.plot ?: meta?.overview,
        cast = tv.own.owntv.core.metadata.MetadataCast.parse(meta?.castJson),
    )
}

@Composable
private fun SeriesListRow(series: SeriesEntity, isFavorite: Boolean, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier) {
    FocusableSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), surface = GlassSurface.CARDS) { focused ->
        LaunchedEffect(focused) { if (focused) onFocus() }
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(width = 44.dp, height = 62.dp).clip(RoundedCornerShape(6.dp)).background(OwnTVTheme.colors.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                if (!series.posterUrl.isNullOrBlank()) AsyncImage(model = series.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                else OwnTVIcon(OwnTVIcon.SERIES, tint = OwnTVTheme.colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(series.name, style = MaterialTheme.typography.titleSmall, color = if (focused) OwnTVTheme.colors.primary else OwnTVTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = seriesMetaLine(series, null, null, false)
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = OwnTVTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isFavorite) OwnTVIcon(OwnTVIcon.FAVORITE, tint = OwnTVTheme.colors.primary, modifier = Modifier.size(18.dp))
        }
    }
}

private fun extractYoutubeId(trailer: String?): String? {
    if (trailer.isNullOrBlank()) return null
    if (trailer.length == 11 && !trailer.contains("/")) return trailer
    return runCatching {
        val uri = trailer.toUri()
        if (uri.host?.contains("youtube.com") == true) uri.getQueryParameter("v")
        else if (uri.host?.contains("youtu.be") == true) uri.path?.removePrefix("/")
        else null
    }.getOrNull() ?: trailer.takeIf { !it.contains("/") }
}

private fun jsonList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}
