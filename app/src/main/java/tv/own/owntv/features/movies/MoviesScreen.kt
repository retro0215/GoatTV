package tv.own.owntv.features.movies

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.features.customize.MoveToCategoryDialog
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.core.model.DownloadStatus
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
import tv.own.owntv.ui.components.ResumeDialog
import tv.own.owntv.ui.components.SetTmdbNameDialog
import tv.own.owntv.ui.components.TrailerPlayerScreen
import tv.own.owntv.ui.components.chNavPaging
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.gridFocusTarget
import androidx.compose.foundation.layout.width
import tv.own.owntv.ui.components.SearchBar
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.trapVerticalFocusExit
import tv.own.owntv.ui.components.SortChip
import tv.own.owntv.ui.components.formatCount
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.PreviewPanelFill
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.format.localizedInteger

@Composable
fun MoviesScreen(
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onContentScrolled: (Boolean) -> Unit = {},
) {
    val vm: MovieViewModel = koinViewModel()
    val alreadyDownloadedMessage = stringResource(R.string.content_already_downloaded)
    val refetchingTmdbMessage = stringResource(R.string.content_refetching_tmdb)
    val researchingTmdbMessage = stringResource(R.string.content_researching_tmdb)
    val railItems by vm.railItems.collectAsStateWithLifecycle()
    val selectedKey by vm.selectedKey.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val sortMode by vm.sortMode.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val selectedMovie by vm.selectedMovie.collectAsStateWithLifecycle()
    val detailedMovie by vm.detailedMovie.collectAsStateWithLifecycle()
    val selectedMovieMeta by vm.selectedMovieMeta.collectAsStateWithLifecycle()
    val providerMeta by vm.providerMeta.collectAsStateWithLifecycle()
    val metadataMode by vm.metadataMode.collectAsStateWithLifecycle()
    val moveState by vm.moveState.collectAsStateWithLifecycle()
    var contextMovie by remember { mutableStateOf<MovieEntity?>(null) }
    var moveItem by remember { mutableStateOf<MovieEntity?>(null) }
    var moveOriginKey by remember { mutableStateOf<String?>(null) }
    var moveOriginName by remember { mutableStateOf<String?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var detailsMovie by remember { mutableStateOf<MovieEntity?>(null) }
    var setTmdbNameMovie by remember { mutableStateOf<MovieEntity?>(null) }
    var trailerVideoKey by remember { mutableStateOf<String?>(null) }
    var contextMovieSubs by remember { mutableStateOf<List<tv.own.owntv.core.database.dao.LinkedSubtitle>>(emptyList()) }
    var showDeleteSubs by remember { mutableStateOf(false) }
    val toast = rememberInAppToast()
    var contextMovieId by remember { mutableStateOf<Long?>(null) }
    var contextMovieIndex by remember { mutableStateOf(-1) }
    val contextFocus = remember { FocusRequester() }
    val selectedProgress by vm.selectedProgress.collectAsStateWithLifecycle()
    val movieProgress by vm.movieProgress.collectAsStateWithLifecycle()
    val downloadStates by vm.downloadStates.collectAsStateWithLifecycle()
    val movies = vm.movies.collectAsLazyPagingItems()
    val resumeMode by vm.resumeMode.collectAsStateWithLifecycle()
    val externalPlayerOn by vm.externalPlayerOn.collectAsStateWithLifecycle()
    val goFullscreen: () -> Unit = { if (!externalPlayerOn) onFullscreen() }

    val selectedIndex = railItems.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val selectedItem = railItems.getOrNull(selectedIndex)
    val selectedLabel = selectedItem?.displayLabel(R.string.content_category_all_movies) ?: stringResource(R.string.content_category_all_movies)

    val scope = rememberCoroutineScope()
    var resumePrompt by remember { mutableStateOf<Pair<MovieEntity, Long>?>(null) }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val selFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }

    val settingsVm: tv.own.owntv.features.settings.SettingsViewModel = koinViewModel()
    val chNavEnabled by settingsVm.chNavEnabled.collectAsStateWithLifecycle()
    val chNavUpSkip by settingsVm.chNavUpSkip.collectAsStateWithLifecycle()
    val chNavDownSkip by settingsVm.chNavDownSkip.collectAsStateWithLifecycle()
    val rememberMovies by settingsVm.rememberLastMovies.collectAsStateWithLifecycle()

    val perCategoryGrid = remember { mutableStateMapOf<LiveKey, LazyGridState>() }
    val perCategoryList = remember { mutableStateMapOf<LiveKey, LazyListState>() }
    val effectiveGridState = if (rememberMovies) perCategoryGrid.getOrPut(selectedKey) { LazyGridState() } else gridState
    val effectiveListState = if (rememberMovies) perCategoryList.getOrPut(selectedKey) { LazyListState() } else listState
    
    LaunchedEffect(selectedKey, rememberMovies) {
        if (!rememberMovies) { runCatching { gridState.scrollToItem(0) }; runCatching { listState.scrollToItem(0) } }
    }
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
    
    LaunchedEffect(restoreFocus, movies.itemCount) {
        if (!restoreFocus || movies.itemCount == 0) return@LaunchedEffect
        val sel = selectedMovie
        val idx = if (sel != null) movies.itemSnapshotList.items.indexOfFirst { it.id == sel.id } else -1
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
    
    LaunchedEffect(contextMovie, moveItem, creatingCategory) {
        if (contextMovie != null) return@LaunchedEffect
        if (detailsMovie != null) return@LaunchedEffect
        if (setTmdbNameMovie != null) return@LaunchedEffect
        if (trailerVideoKey != null) return@LaunchedEffect
        if (moveItem != null || creatingCategory) return@LaunchedEffect
        val targetId = contextMovieId
        if (targetId == null) { contextMovieIndex = -1; return@LaunchedEffect }
        val items = movies.itemSnapshotList.items
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
            val settled = movies.itemSnapshotList.items.filterNotNull()
            if (settled.isEmpty()) {
                runCatching { firstItemFocus.requestFocus() }
            } else {
                val neighbor = settled.getOrNull(contextMovieIndex.coerceAtLeast(0)) ?: settled.last()
                val neighborIdx = items.indexOfFirst { it.id == neighbor.id }.coerceAtLeast(0)
                runCatching {
                    if (viewMode == SettingsRepository.VodViewMode.LIST) effectiveListState.scrollToItem(neighborIdx)
                    else effectiveGridState.scrollToItem(neighborIdx)
                }
                contextMovieId = neighbor.id
                withFrameNanos { }
                runCatching { contextFocus.requestFocus() }
            }
        }
        contextMovieIndex = -1
    }

    val panelShares = rememberPanelShares(PanelSection.MOVIES, settingsVm)
    
    if (detailedMovie != null) {
        MovieDetailsPage(
            movie = detailedMovie!!,
            vm = vm,
            onBack = vm::closeMovieDetails,
            onFullscreen = goFullscreen,
            onPlayTrailer = { trailerVideoKey = it },
        )
    } else {
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
                    categories = railItems.map { RailCategory(it.displayLabel(R.string.content_category_all_movies), it.icon, showGenreDot = it.key is LiveKey.Folder) },
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
                            lastIndex = { movies.itemCount - 1 },
                            currentTargetIndex = {
                                val sel = selectedMovie
                                if (sel != null) {
                                    val idx = movies.itemSnapshotList.items.indexOfFirst { it.id == sel.id }
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
                                    val item = movies.itemSnapshotList.items.getOrNull(idx)
                                    if (viewMode == SettingsRepository.VodViewMode.GRID) {
                                        runCatching { effectiveGridState.scrollToItem(idx) }
                                    } else {
                                        runCatching { effectiveListState.scrollToItem(idx) }
                                    }
                                    withFrameNanos { }
                                    if (item != null) {
                                        vm.onMovieFocused(item)
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
                    Text(stringResource(R.string.content_section_category, stringResource(R.string.common_nav_movies), selectedLabel), style = MaterialTheme.typography.headlineLarge, color = OwnTVTheme.colors.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pluralStringResource(R.plurals.content_count_movies, count, selectedLabel, count),
                        style = MaterialTheme.typography.titleMedium,
                        color = OwnTVTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchBar(query = searchQuery, onQueryChange = vm::setSearchQuery, placeholder = stringResource(R.string.content_search_movies, selectedLabel), modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        SortChip(mode = sortMode, onToggle = vm::toggleSort, playlistLabel = stringResource(R.string.content_provider))
                        Spacer(Modifier.width(10.dp))
                        OwnTVButton(
                            label = stringResource(if (viewMode == SettingsRepository.VodViewMode.GRID) R.string.settings_view_grid else R.string.settings_view_list),
                            onClick = vm::toggleViewMode,
                            icon = if (viewMode == SettingsRepository.VodViewMode.GRID) OwnTVIcon.MENU else OwnTVIcon.MOVIES,
                            style = OwnTVButtonStyle.SECONDARY,
                        )
                    }
                    Spacer(Modifier.height(14.dp))

                    if (movies.itemCount == 0) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (searchQuery.isNotBlank()) stringResource(R.string.content_no_movies_found, searchQuery.trim()) else stringResource(R.string.content_no_movies_here),
                                style = MaterialTheme.typography.bodyLarge, color = OwnTVTheme.colors.onSurfaceVariant,
                            )
                        }
                    } else if (viewMode == SettingsRepository.VodViewMode.LIST) {
                        LazyColumn(state = effectiveListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(count = movies.itemCount, key = movies.itemKey { it.id }, contentType = movies.itemContentType { "movie" }) { index ->
                                val movie = movies[index]
                                if (movie != null) {
                                    val prog = movieProgress[movie.id]
                                    MovieListRow(
                                        movie = movie,
                                        isFavorite = favoriteIds.contains(movie.id),
                                        completed = prog?.let { vm.isMovieCompleted(it) } == true,
                                        modifier = Modifier.gridFocusTarget(
                                            itemId = movie.id, index = index,
                                            contextId = contextMovieId, contextFocus = contextFocus,
                                            selectedId = selectedMovie?.id, selectedFocus = selFocus,
                                            firstItemFocus = firstItemFocus,
                                        ),
                                        onFocus = { vm.onMovieFocused(movie) },
                                        onClick = { vm.openMovieDetails(movie) },
                                        onLongClick = { contextMovie = movie; contextMovieId = movie.id; contextMovieIndex = index },
                                    )
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(state = effectiveGridState, columns = GridCells.Adaptive(minSize = 130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(count = movies.itemCount, key = movies.itemKey { it.id }, contentType = movies.itemContentType { "movie" }) { index ->
                                val movie = movies[index]
                                if (movie != null) {
                                    val prog = movieProgress[movie.id]
                                    val done = prog?.let { vm.isMovieCompleted(it) } == true
                                    PosterCard(
                                        posterUrl = movie.posterUrl,
                                        title = movie.name,
                                        rating = movie.rating,
                                        completed = done,
                                        progressFraction = if (done || prog == null || prog.durationMs <= 0) null
                                            else (prog.positionMs.toFloat() / prog.durationMs).takeIf { it > 0f },
                                        isFavorite = favoriteIds.contains(movie.id),
                                        modifier = Modifier.gridFocusTarget(
                                            itemId = movie.id, index = index,
                                            contextId = contextMovieId, contextFocus = contextFocus,
                                            selectedId = selectedMovie?.id, selectedFocus = selFocus,
                                            firstItemFocus = firstItemFocus,
                                        ),
                                        onFocus = { vm.onMovieFocused(movie) },
                                        onClick = { vm.openMovieDetails(movie) },
                                        onLongClick = { contextMovie = movie; contextMovieId = movie.id; contextMovieIndex = index },
                                    )
                                }
                            }
                        }
                    }
                }

                if (previewVisible) {
                    Spacer(Modifier.width(BrowseColumnGap))
                    Box(modifier = Modifier.then(if (panels != null) Modifier.width(panels.preview) else Modifier.weight(1f)).fillMaxSize().roundedPanel(fillColor = PreviewPanelFill).padding(BrowseContainerPadding)) {
                        MovieDetailsPane(
                            movie = selectedMovie,
                            meta = selectedMovieMeta?.takeIf { it.movieId == selectedMovie?.id }?.cache,
                            providerMeta = providerMeta?.takeIf { it.remoteId == selectedMovie?.remoteId },
                            tmdbWins = metadataMode.tmdbWins,
                            resumePositionMs = selectedProgress?.takeIf { !vm.isMovieCompleted(it) }?.positionMs?.takeIf { it > 0 },
                            downloadStrip = selectedMovie?.let { m -> downloadStates[m.id]?.let { tv.own.owntv.ui.components.downloadStripFor(listOf(it)) } },
                            onWatchNow = { selectedMovie?.let { vm.openMovieDetails(it) } },
                            onPlayTrailer = { trailerVideoKey = it },
                            onToggleFavorite = { selectedMovie?.let { vm.toggleFavorite(it) } },
                            isFavorite = selectedMovie?.let { favoriteIds.contains(it.id) } ?: false,
                        )
                    }
                }
            }
        }
    }

    resumePrompt?.let { (m, pos) ->
        ResumeDialog(
            positionMs = pos,
            onResume = { resumePrompt = null; vm.play(m, pos); goFullscreen() },
            onStartOver = { resumePrompt = null; vm.play(m, 0); goFullscreen() },
            onDismiss = { resumePrompt = null },
        )
    }

    LaunchedEffect(contextMovie?.id) {
        contextMovieSubs = contextMovie?.let { runCatching { vm.downloadedSubtitles(it) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    contextMovie?.let { m ->
        val alreadyDownloaded = downloadStates[m.id] != null
        val cacheForM = selectedMovieMeta?.takeIf { it.movieId == m.id }?.cache
        val watched = selectedProgress?.takeIf { selectedMovie?.id == m.id }?.let { vm.isMovieCompleted(it) } ?: false
        MovieContextMenu(
            title = m.name, isFavorite = favoriteIds.contains(m.id), watched = watched,
            canMove = selectedKey is LiveKey.Folder || selectedKey is LiveKey.Custom || selectedKey == LiveKey.Favorites,
            isHistory = selectedKey == LiveKey.History,
            hasTmdbDetails = metadataMode.enrich && cacheForM != null,
            trailerKey = if (metadataMode.enrich) cacheForM?.trailerKey else null,
            canRefetchTmdb = metadataMode.enrich,
            onShowDetails = { contextMovie = null; detailsMovie = m },
            onToggleFavorite = { vm.toggleFavorite(m); contextMovie = null },
            onToggleWatched = { if (watched) vm.markMovieUnwatched(m) else vm.markMovieWatched(m); contextMovie = null },
            onMove = { contextMovie = null; vm.enterMoveMode(m, selectedKey) },
            onMoveToCategory = {
                moveOriginKey = when (val k = selectedKey) {
                    is LiveKey.Folder -> vm.folderKey(k.id)
                    is LiveKey.Custom -> k.id
                    LiveKey.Favorites -> ContentOrderEntity.FAV_CONTEXT
                    else -> null
                }
                moveOriginName = railItems.firstOrNull { it.key == selectedKey }?.title
                moveItem = m; contextMovie = null
            },
            onHide = { vm.hideMovie(m); contextMovie = null },
            onRemoveFromHistory = { vm.removeFromHistory(m.id); contextMovie = null },
            onDownload = {
                contextMovie = null
                if (alreadyDownloaded) toast.show(alreadyDownloadedMessage) else vm.download(m)
            },
            onPlayExternal = { contextMovie = null; vm.playExternal(m) },
            onRefetch = { contextMovie = null; toast.show(refetchingTmdbMessage); vm.refetchMovieMeta(m) },
            onSetTmdbName = { contextMovie = null; setTmdbNameMovie = m },
            onPlayTrailer = { key -> contextMovie = null; trailerVideoKey = key },
            onDeleteSubtitles = if (contextMovieSubs.isNotEmpty()) ({ showDeleteSubs = true }) else null,
            onDismiss = { contextMovie = null },
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
        moveItem?.let { m ->
            val originKey = moveOriginKey
            if (originKey != null) {
                MoveToCategoryDialog(
                    moveTargets = moveTargets.filterNot { it.id == originKey },
                    originName = moveOriginName ?: stringResource(R.string.settings_customize_this_category),
                    onNewCategory = { creatingCategory = true },
                    onMove = { targetId, keepInOrigin ->
                        vm.moveToCategory(CustomizeKeys.movie(m), m.id, originKey, targetId, keepInOrigin)
                        moveItem = null
                    },
                    onDismiss = { moveItem = null },
                )
            }
        }
    }

    if (showDeleteSubs) {
        val m = contextMovie
        if (m == null || contextMovieSubs.isEmpty()) { showDeleteSubs = false } else {
            tv.own.owntv.features.subtitles.SubtitleDeletePopup(
                contentTitle = m.name, items = contextMovieSubs,
                onDelete = { sub ->
                    vm.deleteSubtitle(sub.cacheId)
                    contextMovieSubs = contextMovieSubs.filterNot { it.cacheId == sub.cacheId }
                    if (contextMovieSubs.isEmpty()) { showDeleteSubs = false; contextMovie = null }
                },
                onDismiss = { showDeleteSubs = false },
            )
        }
    }

    LaunchedEffect(detailsMovie) {
        if (detailsMovie == null && contextMovieId != null) { withFrameNanos { }; runCatching { contextFocus.requestFocus() } }
    }

    detailsMovie?.let { m ->
        val cache = selectedMovieMeta?.takeIf { it.movieId == m.id }?.cache
        MediaDetailsScreen(details = buildMovieDetails(m, cache, metadataMode.tmdbWins), onExit = { detailsMovie = null })
    }

    LaunchedEffect(setTmdbNameMovie) {
        if (setTmdbNameMovie == null && contextMovieId != null) { withFrameNanos { }; runCatching { contextFocus.requestFocus() } }
    }
    setTmdbNameMovie?.let { m ->
        var prefill by remember(m.id) { mutableStateOf<MovieViewModel.TmdbNamePrefill?>(null) }
        LaunchedEffect(m.id) { prefill = vm.movieTmdbNamePrefill(m) }
        prefill?.let { p ->
            SetTmdbNameDialog(
                initialTitle = p.title, initialYear = p.year, hasOverride = p.hasOverride,
                onSave = { title, year -> setTmdbNameMovie = null; vm.setMovieTmdbName(m, title, year); toast.show(researchingTmdbMessage) },
                onClear = { setTmdbNameMovie = null; vm.clearMovieTmdbName(m); toast.show(researchingTmdbMessage) },
                onDismiss = { setTmdbNameMovie = null },
            )
        }
    }

    LaunchedEffect(trailerVideoKey) {
        if (trailerVideoKey == null && contextMovieId != null) { withFrameNanos { }; runCatching { contextFocus.requestFocus() } }
    }
    trailerVideoKey?.let { key -> TrailerPlayerScreen(videoKey = key, onExit = { trailerVideoKey = null }) }

    moveState?.let { ms ->
        MoveOrderOverlay(
            title = stringResource(R.string.content_reorder_movie), itemNames = ms.items.map { it.name }, activeIndex = ms.activeIndex,
            onMoveUp = vm::moveUp, onMoveDown = vm::moveDown, onCommit = vm::commitMove, onCancel = vm::cancelMove,
        )
    }

    InAppToast(toast)
}

@Composable
private fun MovieContextMenu(
    title: String,
    isFavorite: Boolean,
    watched: Boolean,
    canMove: Boolean,
    isHistory: Boolean,
    hasTmdbDetails: Boolean,
    trailerKey: String?,
    canRefetchTmdb: Boolean,
    onShowDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onMove: () -> Unit,
    onMoveToCategory: () -> Unit,
    onHide: () -> Unit,
    onRemoveFromHistory: () -> Unit,
    onDownload: () -> Unit,
    onPlayExternal: () -> Unit,
    onRefetch: () -> Unit,
    onSetTmdbName: () -> Unit,
    onPlayTrailer: (String) -> Unit,
    onDeleteSubtitles: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().modalScrim()
            .trapAllFocusExit().focusGroup()
            .longPressMenuGuard(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.dialogPanel(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            OwnTVButton(
                if (isFavorite) stringResource(R.string.content_remove_favourite) else stringResource(R.string.content_add_favourite),
                onClick = onToggleFavorite, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.FAVORITE,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            OwnTVButton(
                if (watched) stringResource(R.string.content_mark_unwatched) else stringResource(R.string.content_mark_watched),
                onClick = onToggleWatched, style = OwnTVButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            if (canMove) OwnTVButton(stringResource(R.string.content_move), onClick = onMove, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            if (canMove) OwnTVButton(stringResource(R.string.content_move_to_category), onClick = onMoveToCategory, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            if (isHistory) OwnTVButton(stringResource(R.string.content_remove_history), onClick = onRemoveFromHistory, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            OwnTVButton(stringResource(R.string.common_hide), onClick = onHide, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            OwnTVButton(stringResource(R.string.content_download), onClick = onDownload, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.DOWNLOADS, modifier = Modifier.fillMaxWidth())
            onDeleteSubtitles?.let {
                OwnTVButton(stringResource(R.string.content_delete_subtitles), onClick = it, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.SUBTITLE, modifier = Modifier.fillMaxWidth())
            }
            OwnTVButton(stringResource(R.string.content_play_external), onClick = onPlayExternal, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.PLAY, modifier = Modifier.fillMaxWidth())
            if (hasTmdbDetails) {
                Spacer(Modifier.height(4.dp))
                OwnTVButton(stringResource(R.string.content_tmdb_details), onClick = onShowDetails, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.MENU, modifier = Modifier.fillMaxWidth())
            }
            trailerKey?.let { key ->
                OwnTVButton(stringResource(R.string.content_play_trailer), onClick = { onPlayTrailer(key) }, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
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
private fun MovieDetailsPage(
    movie: MovieEntity,
    vm: MovieViewModel,
    onBack: () -> Unit,
    onFullscreen: () -> Unit,
    onPlayTrailer: (String) -> Unit,
) {
    val meta by vm.selectedMovieMeta.collectAsStateWithLifecycle()
    val providerMeta by vm.providerMeta.collectAsStateWithLifecycle()
    val metadataMode by vm.metadataMode.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val selectedProgress by vm.selectedProgress.collectAsStateWithLifecycle()
    
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    
    BackHandler { onBack() }
    
    LaunchedEffect(movie.id) {
        vm.onMovieFocused(movie)
        delay(100)
        runCatching { focus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        MovieDetailsPane(
            movie = movie,
            meta = meta?.takeIf { it.movieId == movie.id }?.cache,
            providerMeta = providerMeta?.takeIf { it.remoteId == movie.remoteId },
            tmdbWins = metadataMode.tmdbWins,
            resumePositionMs = selectedProgress?.takeIf { !vm.isMovieCompleted(it) }?.positionMs?.takeIf { it > 0 },
            onWatchNow = { vm.play(movie, selectedProgress?.positionMs ?: 0); onFullscreen() },
            onPlayTrailer = onPlayTrailer,
            onToggleFavorite = { vm.toggleFavorite(movie) },
            isFavorite = favoriteIds.contains(movie.id),
            fullPage = true,
            focusRequester = focus,
        )
    }
}

@Composable
private fun MovieDetailsPane(
    movie: MovieEntity?,
    meta: tv.own.owntv.core.database.entity.MetadataCacheEntity?,
    providerMeta: tv.own.owntv.core.database.entity.ProviderMetadataEntity?,
    tmdbWins: Boolean,
    resumePositionMs: Long? = null,
    downloadStrip: tv.own.owntv.ui.components.DownloadStripState? = null,
    onWatchNow: () -> Unit,
    onPlayTrailer: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    isFavorite: Boolean,
    fullPage: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val colors = OwnTVTheme.colors
    if (movie == null) {
        PreviewPane(hint = stringResource(R.string.content_focus_movie))
        return
    }
    val backdropUrl = providerMeta?.backdropUrl?.takeIf { it.isNotBlank() }
        ?: meta?.backdropPath?.let { tv.own.owntv.core.metadata.MetadataImages.backdrop(it) }
        ?: movie.backdropUrl?.takeIf { it.isNotBlank() }
        ?: movie.posterUrl?.takeIf { it.isNotBlank() }

    val providerPlot = providerMeta?.plot?.takeIf { it.isNotBlank() } ?: movie.plot?.takeIf { it.isNotBlank() }
    val plot = if (tmdbWins) meta?.overview ?: providerPlot else providerPlot ?: meta?.overview
    val trailerKey = extractYoutubeId(providerMeta?.trailer) ?: meta?.trailerKey

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = if (fullPage) 48.dp else Dimens.GapLarge),
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(if (fullPage) 21f / 9f else 16f / 9f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))) {
                AsyncImage(model = backdropUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
            }
            Spacer(Modifier.height(14.dp))
        }

        Column(modifier = Modifier.padding(horizontal = if (fullPage) 48.dp else Dimens.GapLarge)) {
            if (downloadStrip != null) {
                tv.own.owntv.ui.components.DownloadStatusStrip(downloadStrip)
                Spacer(Modifier.height(12.dp))
            }
            if (resumePositionMs != null) {
                Text(stringResource(R.string.content_resume_at, tv.own.owntv.ui.components.formatTimestamp(resumePositionMs)), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Spacer(Modifier.height(6.dp))
            }
            Text(movie.name, style = if (fullPage) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(if (fullPage) 16.dp else 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OwnTVButton(
                    label = stringResource(if (resumePositionMs != null) R.string.content_action_resume else R.string.content_action_play),
                    onClick = onWatchNow, icon = OwnTVIcon.PLAY, style = OwnTVButtonStyle.PRIMARY,
                    modifier = Modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                )
                if (!trailerKey.isNullOrBlank()) {
                    OwnTVButton(label = stringResource(R.string.content_play_trailer), onClick = { onPlayTrailer(trailerKey) }, icon = OwnTVIcon.PLAY, style = OwnTVButtonStyle.SECONDARY)
                }
                OwnTVButton(label = if (isFavorite) stringResource(R.string.content_favorited) else stringResource(R.string.content_favorite), onClick = onToggleFavorite, icon = OwnTVIcon.FAVORITE, style = OwnTVButtonStyle.SECONDARY)
            }
            Spacer(Modifier.height(20.dp))
            Text(metaLine(movie, meta, providerMeta, tmdbWins), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (providerMeta == null && fullPage) {
                Spacer(Modifier.height(8.dp))
                Text(text = stringResource(R.string.content_loading_details), style = MaterialTheme.typography.labelSmall, color = colors.primary.copy(alpha = 0.7f))
            }
            val genres = jsonList(meta?.genresJson).takeIf { it.isNotEmpty() } ?: providerMeta?.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            if (genres != null && genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(genres.joinToString(stringResource(R.string.content_genres_separator)), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(plot, style = if (fullPage) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = if (fullPage) 15 else 8, overflow = TextOverflow.Ellipsis)
            }
            if (providerMeta?.director != null) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.content_media_director), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(providerMeta.director, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            val cast = tv.own.owntv.core.metadata.MetadataCast.names(meta?.castJson).takeIf { it.isNotEmpty() } ?: providerMeta?.actors?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            if (cast != null && cast.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.content_media_cast), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(cast.take(24).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            if (!fullPage) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.content_long_press_options), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun metaLine(movie: MovieEntity, meta: tv.own.owntv.core.database.entity.MetadataCacheEntity? = null, providerMeta: tv.own.owntv.core.database.entity.ProviderMetadataEntity? = null, tmdbWins: Boolean = false): String {
    val parts = mutableListOf<String>()
    val year = if (tmdbWins) meta?.year ?: providerMeta?.year ?: movie.year else providerMeta?.year ?: movie.year ?: meta?.year
    val rating = if (tmdbWins) meta?.rating?.takeIf { it > 0 } ?: providerMeta?.rating?.takeIf { it > 0 } ?: movie.rating?.takeIf { it > 0 }
    else providerMeta?.rating?.takeIf { it > 0 } ?: movie.rating?.takeIf { it > 0 } ?: meta?.rating?.takeIf { it > 0 }
    val durationSecs = providerMeta?.durationSecs?.takeIf { it > 0 } ?: movie.durationSecs?.takeIf { it > 0 }
    year?.let { parts.add(localizedInteger(it, grouping = false)) }
    rating?.let { parts.add(stringResource(R.string.content_rating, it)) }
    durationSecs?.let { secs ->
        val h = secs / 3600
        val m = (secs % 3600) / 60
        parts.add(if (h > 0) stringResource(R.string.content_duration_hours, h, m) else stringResource(R.string.content_duration_minutes, m))
    }
    return parts.joinToString(stringResource(R.string.content_metadata_separator))
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

@Composable
private fun buildMovieDetails(movie: MovieEntity, meta: tv.own.owntv.core.database.entity.MetadataCacheEntity?, tmdbWins: Boolean): tv.own.owntv.features.shell.components.MediaDetailsUi {
    val providerPoster = movie.posterUrl?.takeIf { it.isNotBlank() }
    val tmdbPoster = tv.own.owntv.core.metadata.MetadataImages.poster(meta?.posterPath)
    val poster = if (tmdbWins) tmdbPoster ?: providerPoster else providerPoster ?: tmdbPoster
    val backdrop = tv.own.owntv.core.metadata.MetadataImages.backdrop(meta?.backdropPath) ?: movie.backdropUrl?.takeIf { it.isNotBlank() }
    val plot = if (tmdbWins) meta?.overview ?: movie.plot else movie.plot?.takeIf { it.isNotBlank() } ?: meta?.overview
    return tv.own.owntv.features.shell.components.MediaDetailsUi(
        title = movie.name, backdropUrl = backdrop, posterUrl = poster, metaLine = metaLine(movie, meta, null, tmdbWins),
        genres = jsonList(meta?.genresJson), plot = plot, cast = tv.own.owntv.core.metadata.MetadataCast.parse(meta?.castJson),
    )
}

private fun jsonList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

@Composable
private fun MovieListRow(movie: MovieEntity, isFavorite: Boolean, completed: Boolean = false, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    FocusableSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentAlignment = Alignment.CenterStart, surface = GlassSurface.CARDS) { focused ->
        LaunchedEffect(focused) { if (focused) onFocus() }
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(width = 44.dp, height = 62.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                if (!movie.posterUrl.isNullOrBlank()) AsyncImage(model = movie.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                else OwnTVIcon(OwnTVIcon.MOVIES, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(movie.name, style = MaterialTheme.typography.titleSmall, color = when { focused -> colors.primary; completed -> colors.onSurfaceVariant; else -> colors.onSurface }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = metaLine(movie)
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (completed) {
                Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(colors.primary), contentAlignment = Alignment.Center) {
                    OwnTVIcon(OwnTVIcon.CHECK, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
            if (isFavorite) OwnTVIcon(OwnTVIcon.FAVORITE, tint = colors.primary, modifier = Modifier.size(18.dp))
        }
    }
}
