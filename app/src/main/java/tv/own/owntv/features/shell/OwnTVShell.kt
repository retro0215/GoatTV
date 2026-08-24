package tv.own.owntv.features.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.launcher.LauncherDeepLink
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.launcher.LauncherLaunch
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.features.update.UpdateDialog
import tv.own.owntv.features.update.UpdateStatusToast
import tv.own.owntv.features.downloads.DownloadsScreen
import tv.own.owntv.features.epg.EpgScreen
import tv.own.owntv.features.home.HomeScreen
import tv.own.owntv.features.home.HomeViewModel
import tv.own.owntv.features.live.LiveKey
import tv.own.owntv.features.live.LiveScreen
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.multiscreen.MultiscreenScreen
import tv.own.owntv.features.multiscreen.MultiscreenViewModel
import tv.own.owntv.features.movies.MoviesScreen
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.search.SearchScreen
import tv.own.owntv.features.search.SearchViewModel
import tv.own.owntv.features.home.TrendingHomeItem
import tv.own.owntv.features.series.SeriesScreen
import tv.own.owntv.features.series.SeriesViewModel
import tv.own.owntv.player.MiniPlayer
import tv.own.owntv.player.MpvVideoSurface
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlayerHud
import tv.own.owntv.features.shell.components.AvatarPickerDialog
import tv.own.owntv.features.shell.components.CategoryRail
import tv.own.owntv.features.shell.components.ContentPane
import tv.own.owntv.features.shell.components.ExitDialog
import tv.own.owntv.features.shell.components.IncompleteRestoreDialog
import tv.own.owntv.features.shell.components.PlaylistPickerDialog
import tv.own.owntv.features.shell.components.PreviewPane
import tv.own.owntv.features.shell.components.RailCategory
import tv.own.owntv.features.shell.components.SettingsScreen
import tv.own.owntv.features.shell.components.Sidebar
import tv.own.owntv.features.shell.components.SolidAmbientBackdrop
import tv.own.owntv.features.shell.components.TopBar
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.LocalContentScrolled
import tv.own.owntv.ui.theme.LocalGlass
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.ThemeMode

/** Which layer currently holds focus (drives Back navigation). */
private enum class ShellLayer { SIDEBAR, RAIL, CONTENT }

/** Player presentation: hidden, fullscreen, docked mini-player, or audio-only now-playing bar. */
private enum class PlayerMode { NONE, FULLSCREEN, MINI, AUDIO }

/**
 * The MD3 shell: a fixed navigation panel (Layer 1) plus the active destination. Settings is a
 * single-pane sectioned screen; browse sections keep the Folder Rail → Content → Preview layout.
 */
@Composable
fun OwnTVShell(
    selectedSection: MainSection,
    visibleSections: Set<MainSection>,
    onSelectSection: (MainSection) -> Unit,
    themeMode: ThemeMode,
    uiZoomPercent: Int,
    onSetZoom: (Int) -> Unit,
    fontCustomization: tv.own.owntv.ui.theme.FontCustomization,
    onSetFontCustomization: (tv.own.owntv.ui.theme.FontCustomization) -> Unit,
    avatarId: Int,
    onSetAvatar: (Int) -> Unit,
    profileName: String,
    sourceSummary: String?,
    playlists: List<tv.own.owntv.core.database.entity.SourceEntity> = emptyList(),
    activePlaylistId: Long = -1L,
    onSelectPlaylist: (Long) -> Unit = {},
    weatherInfo: tv.own.owntv.core.weather.WeatherInfo? = null,
    weatherFahrenheit: Boolean = false,
    activeProfileId: Long?,
    pendingDeepLink: LauncherDeepLink?,
    onDeepLinkConsumed: () -> Unit,
    isOffline: Boolean = false,
    onExitApp: () -> Unit,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    val noSourceLabel = stringResource(R.string.shell_no_source)
    val subtitleLoadFailed = stringResource(R.string.content_subtitle_load_failed)
    val railSelection = remember { mutableStateMapOf<MainSection, Int>() }
    val selectedRail = railSelection[selectedSection] ?: 0
    val categories = railCategoriesFor(selectedSection)
    var contentScrolled by remember(selectedSection) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val sidebarFocus = remember { FocusRequester() }
    val homeFirstRowFocus = remember { FocusRequester() }
    var focusedLayer by remember { mutableStateOf(ShellLayer.SIDEBAR) }
    var showExit by remember { mutableStateOf(false) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var playerMode by remember { mutableStateOf(PlayerMode.NONE) }
    var openEpgAdd by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var restoreTrendingSearchFocus by remember { mutableStateOf(false) }
    var trendingSearchActive by remember { mutableStateOf(false) }

    val player = koinInject<OwnTVPlayer>()
    val settingsRepo = koinInject<tv.own.owntv.features.settings.data.SettingsRepository>()
    val miniSizePct by settingsRepo.miniPlayerSizePct.collectAsStateWithLifecycle(initialValue = tv.own.owntv.player.MiniPlayerSize.DEFAULT)
    val miniPosName by settingsRepo.miniPlayerPosition.collectAsStateWithLifecycle(initialValue = tv.own.owntv.player.MiniPlayerPosition.DEFAULT.name)
    val ambientGlowEnabled by settingsRepo.ambientGlowEnabled.collectAsStateWithLifecycle(initialValue = false)
    val ambientGlowPulse by settingsRepo.ambientGlowPulse.collectAsStateWithLifecycle(initialValue = true)
    val shellAnimationLevel by settingsRepo.animationLevel.collectAsStateWithLifecycle(initialValue = tv.own.owntv.ui.theme.AnimationLevel.FULL)
    val miniPos = tv.own.owntv.player.MiniPlayerPosition.fromName(miniPosName)
    val subtitleController = koinInject<tv.own.owntv.core.subtitles.SubtitleController>()
    val subtitleContext by subtitleController.current.collectAsStateWithLifecycle()
    var showSubtitleSearch by remember { mutableStateOf(false) }
    var showLocalSubPicker by remember { mutableStateOf(false) }
    val localSubToast = tv.own.owntv.ui.components.rememberInAppToast()
    val metadataBudget = koinInject<tv.own.owntv.core.metadata.MetadataBudget>()
    val budgetRefusedAt by metadataBudget.refusedAt.collectAsStateWithLifecycle()
    var budgetNoticeShown by remember { mutableStateOf(false) }
    val budgetNotice = stringResource(R.string.settings_metadata_limit_reached)

    LaunchedEffect(budgetRefusedAt) {
        if (budgetRefusedAt > 0L && !budgetNoticeShown) {
            budgetNoticeShown = true
            localSubToast.show(budgetNotice)
        }
    }

    val mpvEngine = remember(player) { tv.own.owntv.player.MpvPlaybackEngine(player) }
    val playbackSession = koinInject<tv.own.owntv.player.PlaybackSession>()
    val launcherIntegrationRepository = koinInject<LauncherIntegrationRepository>()
    val homeVm = org.koin.androidx.compose.koinViewModel<HomeViewModel>()
    val movieVm = org.koin.androidx.compose.koinViewModel<MovieViewModel>()
    val seriesVm = org.koin.androidx.compose.koinViewModel<SeriesViewModel>()
    val searchVm = org.koin.androidx.compose.koinViewModel<SearchViewModel>()
    val liveVm = org.koin.androidx.compose.koinViewModel<LiveViewModel>()
    val epgVm = org.koin.androidx.compose.koinViewModel<tv.own.owntv.features.epg.EpgViewModel>()

    LaunchedEffect(selectedSection) {
        if (selectedSection != MainSection.MULTISCREEN) {
            liveVm.previewEngine.setAudioSuspended(false)
        } else {
            playerMode = PlayerMode.NONE
            liveVm.previewEngine.setAudioSuspended(true)
        }
    }

    val liveCanZap by liveVm.canZap.collectAsStateWithLifecycle()
    val liveOnExo by liveVm.liveOnExo.collectAsStateWithLifecycle()
    val catchupActive by liveVm.catchupActive.collectAsStateWithLifecycle()
    val vodExoActive by player.exoActiveState.collectAsStateWithLifecycle()

    LaunchedEffect(liveOnExo, playerMode) {
        playbackSession.attach(
            if (playerMode == PlayerMode.NONE) null else if (liveOnExo) liveVm.previewEngine else mpvEngine,
        )
    }

    val autoFrameRate by settingsRepo.autoFrameRate.collectAsStateWithLifecycle(initialValue = false)
    val afrPrompted by settingsRepo.autoFrameRatePrompted.collectAsStateWithLifecycle(initialValue = true)
    val directTuneEnabled by settingsRepo.directTune.collectAsStateWithLifecycle(initialValue = true)
    
    val epgDaoForLogos = koinInject<tv.own.owntv.core.database.dao.EpgDao>()
    val logoScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        tv.own.owntv.core.epg.EpgLogoStore.start(logoScope, settingsRepo, epgDaoForLogos)
    }

    val canRewindLive by liveVm.canRewindLive.collectAsStateWithLifecycle()
    val timeshiftOffset by liveVm.timeshiftOffsetSec.collectAsStateWithLifecycle()
    var zapSource by remember { mutableStateOf<MainSection?>(null) }
    var showChannelList by remember { mutableStateOf(false) }
    var showHistoryList by remember { mutableStateOf(false) }
    val zapChannels by liveVm.zapChannels.collectAsStateWithLifecycle()
    val zapListTitle by liveVm.zapListTitle.collectAsStateWithLifecycle()
    val zapListKey by liveVm.zapListKey.collectAsStateWithLifecycle()
    
    val zapOverlayTitle = zapListTitle ?: when (zapListKey) {
        LiveKey.Favorites -> stringResource(R.string.content_category_favorites)
        LiveKey.History -> stringResource(R.string.content_category_history)
        LiveKey.Catchup -> stringResource(R.string.content_catchup)
        else -> stringResource(R.string.content_category_all_channels)
    }

    val showCategoryBrowser by liveVm.showCategoryBrowser.collectAsStateWithLifecycle()
    val browserCategories by liveVm.browserCategories.collectAsStateWithLifecycle()
    val previewChannel by liveVm.previewChannel.collectAsStateWithLifecycle()
    val liveFavoriteIds by liveVm.favoriteIds.collectAsStateWithLifecycle()
    val playingMovie by movieVm.playingMovie.collectAsStateWithLifecycle()
    val movieFavoriteIds by movieVm.favoriteIds.collectAsStateWithLifecycle()
    val playingSeries by seriesVm.playingSeries.collectAsStateWithLifecycle()
    val seriesFavoriteIds by seriesVm.favoriteIds.collectAsStateWithLifecycle()
    
    val overlayNowPlaying by produceState<Map<Long, String>>(emptyMap(), showChannelList, zapChannels) {
        if (!showChannelList || zapChannels.size <= 1) { value = emptyMap(); return@produceState }
        value = runCatching { liveVm.nowPlayingFor(zapChannels) }.getOrDefault(emptyMap())
    }
    val historyChannels by produceState(emptyList<ChannelEntity>(), showHistoryList, previewChannel?.id) {
        if (!showHistoryList) { value = emptyList(); return@produceState }
        value = runCatching { liveVm.historyChannels() }.getOrDefault(emptyList())
    }
    val historyNowPlaying by produceState<Map<Long, String>>(emptyMap(), historyChannels) {
        if (historyChannels.isEmpty()) { value = emptyMap(); return@produceState }
        value = runCatching { liveVm.nowPlayingFor(historyChannels) }.getOrDefault(emptyMap())
    }
    val continueTarget by homeVm.continueTarget.collectAsStateWithLifecycle()

    fun openFullscreen(source: MainSection = selectedSection) {
        restoreFocus = false
        zapSource = source
        homeVm.stopPreview()
        if (source != MainSection.LIVE_TV) liveVm.clearLiveOnExo()
        if (source != MainSection.MOVIES && source != MainSection.SERIES && source != MainSection.DOWNLOADS) {
            subtitleController.clear()
        }
        player.exitAudioOnly(); runCatching { liveVm.previewEngine.exitAudioOnly() }
        if (playerMode != PlayerMode.MINI) playerMode = PlayerMode.FULLSCREEN
    }

    val resumeVideo = {
        player.exitAudioOnly()
        runCatching { liveVm.previewEngine.exitAudioOnly() }
    }
    val expandPlayer = { resumeVideo(); restoreFocus = false; playerMode = PlayerMode.FULLSCREEN }
    val exitPlayer = {
        movieVm.saveProgressNow()
        seriesVm.saveEpisodeProgressNow()
        resumeVideo()
        playerMode = PlayerMode.NONE
        showChannelList = false
        showHistoryList = false
        liveVm.hideCategoryBrowser()
        liveVm.onFullscreenExited()
        player.stop()
        subtitleController.clear()
        if (selectedSection != MainSection.LIVE_TV) liveVm.clearLiveOnExo()
        restoreFocus = true
        runCatching { sidebarFocus.requestFocus() }
        Unit
    }
    val dockPlayer = {
        resumeVideo()
        playerMode = PlayerMode.MINI
        restoreFocus = true
        runCatching { sidebarFocus.requestFocus() }
        Unit
    }
    val toAudioMode = {
        (if (liveOnExo) liveVm.previewEngine else mpvEngine).enterAudioOnly()
        playerMode = PlayerMode.AUDIO
        restoreFocus = true
        runCatching { sidebarFocus.requestFocus() }
        Unit
    }

    LaunchedEffect(Unit) { tv.own.owntv.Perf.stamp("shell-composed"); runCatching { sidebarFocus.requestFocus() } }

    LaunchedEffect(pendingDeepLink, activeProfileId) {
        val deepLink = pendingDeepLink ?: return@LaunchedEffect
        val pid = activeProfileId ?: return@LaunchedEffect
        if (pid < 0) return@LaunchedEffect
        when (deepLink) {
            LauncherDeepLink.OpenLiveSection -> {
                onSelectSection(MainSection.LIVE_TV)
                onDeepLinkConsumed()
            }
            else -> when (val launch = launcherIntegrationRepository.resolveLaunch(pid, deepLink)) {
                is LauncherLaunch.Movie -> {
                    onSelectSection(MainSection.MOVIES)
                    movieVm.play(launch.movie, launch.startPositionMs)
                    openFullscreen(MainSection.MOVIES)
                    onDeepLinkConsumed()
                }
                is LauncherLaunch.Episode -> {
                    onSelectSection(MainSection.SERIES)
                    seriesVm.playEpisodeQueue(launch.show, launch.queue, launch.episode, launch.startPositionMs)
                    openFullscreen(MainSection.SERIES)
                    onDeepLinkConsumed()
                }
                is LauncherLaunch.Live -> {
                    onSelectSection(MainSection.LIVE_TV)
                    liveVm.ensurePlaying(launch.channel)
                    openFullscreen(MainSection.LIVE_TV)
                    onDeepLinkConsumed()
                }
                is LauncherLaunch.Series -> {
                    onSelectSection(MainSection.SERIES)
                    seriesVm.openSeries(launch.show)
                    onDeepLinkConsumed()
                }
                null -> {
                    onDeepLinkConsumed()
                }
            }
        }
    }

    LaunchedEffect(selectedSection, playerMode) {
        if (selectedSection != MainSection.LIVE_TV && playerMode == PlayerMode.NONE) player.stop()
        if (selectedSection != MainSection.HOME || playerMode != PlayerMode.NONE) homeVm.stopPreview()
    }

    LaunchedEffect(selectedSection, playerMode, activeProfileId, activePlaylistId) {
        if (selectedSection == MainSection.HOME && playerMode == PlayerMode.NONE && (activeProfileId?.let { it >= 0 } == true)) {
            homeVm.refresh()
        }
    }

    val isMultiscreen = selectedSection == MainSection.MULTISCREEN

    BackHandler {
        when {
            playerMode == PlayerMode.FULLSCREEN -> exitPlayer()
            showAvatarPicker -> showAvatarPicker = false
            showPlaylistPicker -> showPlaylistPicker = false
            showExit -> showExit = false
            focusedLayer == ShellLayer.SIDEBAR -> showExit = true
            isMultiscreen -> onSelectSection(MainSection.LIVE_TV)
            else -> runCatching { sidebarFocus.requestFocus() }
        }
    }

    val glass = LocalGlass.current
    val shellBase = if (glass.isGlassy(GlassSurface.PANELS) || glass.isGlassy(GlassSurface.SIDEBAR)) Color.Transparent else colors.background
    val shellTopBarHeight = if (playerMode == PlayerMode.AUDIO) Dimens.TopBarHeight else Dimens.TopBarCompactHeight

    CompositionLocalProvider(LocalContentScrolled provides contentScrolled) {
    Box(modifier = modifier.fillMaxSize().background(if (isMultiscreen) Color.Black else shellBase)) {
      
      if (isMultiscreen) {
          // CLEAN SEPARATION: Multiscreen mode hides EVERYTHING else.
          MultiscreenScreen(
              onBack = { onSelectSection(MainSection.LIVE_TV) },
              onChildFocused = { focusedLayer = ShellLayer.CONTENT },
              modifier = Modifier.fillMaxSize(),
          )
      } else {
        // Browse UI — hidden while the player is fullscreen.
        if (playerMode != PlayerMode.FULLSCREEN) {
          Column(modifier = Modifier.fillMaxSize()) {
            if (isOffline) OfflineBanner()
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
              Sidebar(
                selected = selectedSection,
                onSelect = { section ->
                    if (trendingSearchActive || section == MainSection.SEARCH) {
                        searchVm.setQuery("")
                        trendingSearchActive = false
                        restoreTrendingSearchFocus = false
                    }
                    onSelectSection(section)
                },
                visibleSections = visibleSections,
                avatarId = avatarId,
                onPickAvatar = { showAvatarPicker = true },
                profileName = profileName,
                sourceSummary = sourceSummary,
                onSwitchProfile = onSwitchProfile,
                selectedItemFocusRequester = sidebarFocus,
                onFocused = { focusedLayer = ShellLayer.SIDEBAR },
                topInset = shellTopBarHeight,
              )

              Column(
                modifier = Modifier.weight(1f).fillMaxSize().background(shellBase),
              ) {
                TopBar(
                    sectionLabel = stringResource(selectedSection.labelRes),
                    onSearchClick = {
                        searchVm.setQuery("")
                        trendingSearchActive = false
                        restoreTrendingSearchFocus = false
                        onSelectSection(MainSection.SEARCH)
                    },
                    playlistName = when {
                        playlists.size <= 1 -> sourceSummary ?: noSourceLabel
                        activePlaylistId <= 0L -> stringResource(R.string.content_all_playlists)
                        else -> playlists.firstOrNull { it.id == activePlaylistId }?.name ?: (sourceSummary ?: noSourceLabel)
                    },
                    weatherInfo = weatherInfo,
                    weatherFahrenheit = weatherFahrenheit,
                    searchVisible = focusedLayer == ShellLayer.SIDEBAR,
                    playlistInteractive = playlists.size > 1,
                    onPlaylistClick = { showPlaylistPicker = true },
                    playlistDownFocusRequester = homeFirstRowFocus.takeIf { selectedSection == MainSection.HOME },
                    continueLabel = continueTarget?.let { target ->
                        val action = when (target.action) {
                            tv.own.owntv.features.home.ContinueAction.RESUME -> stringResource(R.string.content_action_resume)
                            tv.own.owntv.features.home.ContinueAction.PLAY -> stringResource(R.string.content_action_play)
                            tv.own.owntv.features.home.ContinueAction.NEXT_UP -> stringResource(R.string.content_action_next_up)
                            tv.own.owntv.features.home.ContinueAction.LAST_CHANNEL -> stringResource(R.string.content_action_last_channel)
                        }
                        stringResource(R.string.content_continue_label, action, target.name)
                    },
                    continueIcon = when (continueTarget?.kind) {
                        tv.own.owntv.features.home.ContinueKind.LIVE -> OwnTVIcon.LIVE_TV
                        tv.own.owntv.features.home.ContinueKind.MOVIE -> OwnTVIcon.MOVIES
                        tv.own.owntv.features.home.ContinueKind.EPISODE -> OwnTVIcon.SERIES
                        null -> OwnTVIcon.PLAY
                    },
                    onContinueClick = {
                        continueTarget?.let { t ->
                            scope.launch {
                                when (t.kind) {
                                    tv.own.owntv.features.home.ContinueKind.LIVE ->
                                        if (liveVm.ensurePlayingByIdAsync(t.channelId)) openFullscreen(MainSection.LIVE_TV)
                                    tv.own.owntv.features.home.ContinueKind.MOVIE ->
                                        if (movieVm.playByIdAsync(t.movieId, t.positionMs) && !movieVm.externalPlayerOn.value) openFullscreen(MainSection.MOVIES)
                                    tv.own.owntv.features.home.ContinueKind.EPISODE ->
                                        if (seriesVm.playFromHomeAsync(t.seriesId, t.episodeId, t.positionMs) && !seriesVm.externalPlayerOn.value) openFullscreen(MainSection.SERIES)
                                }
                            }
                        }
                    },
                    audioBar = if (playerMode == PlayerMode.AUDIO) {
                        {
                            val isLiveStream = liveOnExo || player.isLiveContent
                            val zapFn: ((Int) -> Unit)? = when {
                                !isLiveStream -> null
                                zapSource == MainSection.LIVE_TV && liveCanZap -> liveVm::zap
                                else -> null
                            }
                            val audioEngine = if (liveOnExo) liveVm.previewEngine else mpvEngine
                            val vodNav by audioEngine.nav.collectAsStateWithLifecycle()
                            tv.own.owntv.player.AudioNowPlayingBar(
                                player = audioEngine,
                                isLive = isLiveStream,
                                canPrev = if (isLiveStream) zapFn != null else vodNav.hasPrev,
                                canNext = if (isLiveStream) zapFn != null else vodNav.hasNext,
                                onPrev = { if (isLiveStream) zapFn?.invoke(-1) else mpvEngine.previous() },
                                onNext = { if (isLiveStream) zapFn?.invoke(1) else mpvEngine.next() },
                                onExpand = expandPlayer,
                                onClose = exitPlayer,
                                focusable = true,
                            )
                        }
                    } else null,
                    leadingExtension = Dimens.SidebarWidthCollapsed,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 0.dp, end = 6.dp, bottom = 6.dp)) {
                    when {
                        selectedSection == MainSection.SETTINGS -> SettingsScreen(
                            themeMode = themeMode, uiZoomPercent = uiZoomPercent, onSetZoom = onSetZoom,
                            fontCustomization = fontCustomization, onSetFontCustomization = onSetFontCustomization,
                            onOpenPlaylist = {}, openEpgAdd = openEpgAdd, onEpgAddConsumed = { openEpgAdd = false },
                            modifier = Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }.focusGroup(),
                        )
                        selectedSection == MainSection.HOME -> HomeScreen(
                            vm = homeVm,
                            onPlayMovie = { id, pos -> scope.launch { if (movieVm.playByIdAsync(id, pos) && !movieVm.externalPlayerOn.value) openFullscreen(MainSection.MOVIES) } },
                            onPlayEpisode = { seriesId, epId, pos -> scope.launch { if (seriesVm.playFromHomeAsync(seriesId, epId, pos) && !seriesVm.externalPlayerOn.value) openFullscreen(MainSection.SERIES) } },
                            onPlayChannel = { id, zap -> scope.launch { if (liveVm.ensurePlayingByIdAsync(id, zap)) openFullscreen(MainSection.LIVE_TV) } },
                            onOpenGuide = { onSelectSection(MainSection.EPG) },
                            onActivateTrending = { selected, onUnavailable ->
                                scope.launch {
                                    when (val current = homeVm.revalidateTrendingItem(selected)) {
                                        is TrendingHomeItem.Movie -> {
                                            if (movieVm.playByIdAsync(current.movie.id)) { if (!movieVm.externalPlayerOn.value) openFullscreen(MainSection.MOVIES) } else onUnavailable()
                                        }
                                        is TrendingHomeItem.Series -> { seriesVm.openSeries(current.series); restoreFocus = true; onSelectSection(MainSection.SERIES) }
                                        null -> onUnavailable()
                                    }
                                }
                            },
                            onOpenTrendingSearch = { query -> searchVm.setQuery(query); trendingSearchActive = true; onSelectSection(MainSection.SEARCH) },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus, restoreTrendingSearchFocus = restoreTrendingSearchFocus,
                            onRestored = { restoreFocus = false; restoreTrendingSearchFocus = false },
                            previewEnabled = playerMode == PlayerMode.NONE, firstRowFocusRequester = homeFirstRowFocus,
                            onContentScrolled = { contentScrolled = it }, modifier = Modifier.fillMaxSize(),
                        )
                        selectedSection == MainSection.SEARCH -> SearchScreen(
                            vm = searchVm, onFullscreen = { openFullscreen() },
                            onOpenSeries = { series -> trendingSearchActive = false; seriesVm.openSeries(series); onSelectSection(MainSection.SERIES) },
                            onPlayChannel = { ch -> restoreFocus = false; liveVm.watchFromGuide(ch); zapSource = MainSection.LIVE_TV; homeVm.stopPreview(); if (playerMode != PlayerMode.MINI && !liveVm.externalPlayerOn.value) playerMode = PlayerMode.FULLSCREEN },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT }, returnToHomeOnBack = trendingSearchActive,
                            onReturnToHome = { trendingSearchActive = false; restoreTrendingSearchFocus = true; onSelectSection(MainSection.HOME) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        selectedSection == MainSection.LIVE_TV -> LiveScreen(
                            onFullscreen = { openFullscreen() }, onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            previewEnabled = playerMode == PlayerMode.NONE, restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false }, onContentScrolled = { contentScrolled = it },
                            onOpenMultiscreen = { liveVm.stopPreview(); onSelectSection(MainSection.MULTISCREEN) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        selectedSection == MainSection.MOVIES -> MoviesScreen(
                            onFullscreen = { openFullscreen() }, onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus, onRestored = { restoreFocus = false },
                            onContentScrolled = { contentScrolled = it }, modifier = Modifier.fillMaxSize(),
                        )
                        selectedSection == MainSection.SERIES -> SeriesScreen(
                            onFullscreen = { openFullscreen() }, onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus, onRestored = { restoreFocus = false }, modifier = Modifier.fillMaxSize(),
                        )
                        selectedSection == MainSection.DOWNLOADS -> DownloadsScreen(
                            onFullscreen = { openFullscreen() }, onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus, onRestored = { restoreFocus = false }, modifier = Modifier.fillMaxSize(),
                        )
                        selectedSection == MainSection.EPG -> EpgScreen(
                            onBack = { runCatching { sidebarFocus.requestFocus() } }, onFullscreen = { openFullscreen() },
                            onPlayChannel = { ch, _ -> restoreFocus = false; liveVm.watchFromGuide(ch); zapSource = MainSection.LIVE_TV; homeVm.stopPreview(); if (playerMode != PlayerMode.MINI && !liveVm.externalPlayerOn.value) playerMode = PlayerMode.FULLSCREEN },
                            onPlayCatchup = { ch, prog -> restoreFocus = false; liveVm.playCatchupProgramme(ch, prog); zapSource = MainSection.LIVE_TV; homeVm.stopPreview(); if (playerMode != PlayerMode.MINI) playerMode = PlayerMode.FULLSCREEN },
                            onAddEpg = { openEpgAdd = true; onSelectSection(MainSection.SETTINGS) }, restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false }, onContentScrolled = { contentScrolled = it },
                            modifier = Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }.focusGroup(),
                        )
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
              }
            }
          }
        }
        SolidAmbientBackdrop(
            glowEnabled = ambientGlowEnabled,
            pulseEnabled = ambientGlowPulse && shellAnimationLevel != tv.own.owntv.ui.theme.AnimationLevel.OFF,
            modifier = Modifier.fillMaxSize(),
        )
      }

      if (playerMode != PlayerMode.FULLSCREEN && !isMultiscreen) {
          tv.own.owntv.features.shell.components.SyncStatusPill(modifier = Modifier.align(Alignment.BottomCenter))
      }

      if (playerMode == PlayerMode.FULLSCREEN || playerMode == PlayerMode.MINI) {
        val isFull = playerMode == PlayerMode.FULLSCREEN
        Box(
            modifier = if (isFull) Modifier.fillMaxSize().background(Color.Black)
            else Modifier.align(miniPos.alignment).padding(24.dp).fillMaxWidth(tv.own.owntv.player.MiniPlayerSize.fraction(miniSizePct)).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black),
        ) {
            if (liveOnExo) {
                tv.own.owntv.player.ExoPreviewSurface(engine = liveVm.previewEngine, modifier = Modifier.fillMaxSize(), keepAwake = true, autoFrameRate = isFull && autoFrameRate)
            } else {
                MpvVideoSurface(player = player, modifier = Modifier.fillMaxSize(), autoFrameRate = isFull && autoFrameRate)
            }
            val audioOnlyMedia by if (liveOnExo) liveVm.previewEngine.audioOnlyMedia.collectAsStateWithLifecycle() else player.audioOnlyMedia.collectAsStateWithLifecycle()
            if (audioOnlyMedia) tv.own.owntv.player.AudioOnlyBadge(modifier = Modifier.fillMaxSize(), compact = !isFull)
            if (!liveOnExo) {
                tv.own.owntv.player.SubtitleOverlay(player = player, modifier = Modifier.fillMaxSize(), sizeScale = if (isFull) 1f else (tv.own.owntv.player.MiniPlayerSize.fraction(miniSizePct) * 1.5f).coerceIn(0.35f, 0.7f))
            }
            if (isFull && !autoFrameRate && !afrPrompted) {
                val activeFps by if (liveOnExo) liveVm.previewEngine.videoFps.collectAsStateWithLifecycle() else player.videoFps.collectAsStateWithLifecycle()
                tv.own.owntv.player.AutoFrameRatePrompt(fps = activeFps, afrEnabled = autoFrameRate, alreadyPrompted = afrPrompted, onEnable = { scope.launch { settingsRepo.setAutoFrameRate(true); settingsRepo.setAutoFrameRatePrompted() } }, onDismiss = { scope.launch { settingsRepo.setAutoFrameRatePrompted() } })
            }
            if (isFull) {
                val isLiveStream = liveOnExo || player.isLiveContent
                val zap: ((Int) -> Unit)? = when {
                    !isLiveStream -> null
                    zapSource == MainSection.LIVE_TV && liveCanZap -> liveVm::zap
                    else -> null
                }
                val isTunedLive = zapSource == MainSection.LIVE_TV && !catchupActive
                val favToggle: (() -> Unit)? = when {
                    zapSource == MainSection.LIVE_TV -> previewChannel?.let { ch -> { liveVm.toggleFavorite(ch) } }
                    zapSource == MainSection.MOVIES -> playingMovie?.let { m -> { movieVm.toggleFavorite(m) } }
                    zapSource == MainSection.SERIES -> playingSeries?.let { s -> { seriesVm.toggleFavorite(s) } }
                    else -> null
                }
                val favActive = when {
                    zapSource == MainSection.LIVE_TV -> previewChannel?.let { liveFavoriteIds.contains(it.id) } ?: false
                    zapSource == MainSection.MOVIES -> playingMovie?.let { movieFavoriteIds.contains(it.id) } ?: false
                    zapSource == MainSection.SERIES -> playingSeries?.let { seriesFavoriteIds.contains(it.id) } ?: false
                    else -> false
                }
                PlayerHud(
                    player = if (liveOnExo) liveVm.previewEngine else mpvEngine, onBack = exitPlayer, onPip = dockPlayer, onAudioMode = toAudioMode,
                    inert = showChannelList || showHistoryList || showCategoryBrowser || showSubtitleSearch || showLocalSubPicker,
                    onChannelUp = zap?.let { z -> { z(-1) } }, onChannelDown = zap?.let { z -> { z(1) } },
                    onOpenChannelList = if (isTunedLive && liveCanZap) { { showChannelList = true } } else null,
                    onOpenHistoryList = if (isTunedLive) { { showHistoryList = true } } else null,
                    onRewindLive = if (isTunedLive && canRewindLive) liveVm::rewindLive else null,
                    onForwardLive = if (isTunedLive) liveVm::forwardLive else null,
                    onGoToLive = if (isTunedLive) liveVm::goToLive else null,
                    onScrubLive = if (isTunedLive && canRewindLive) liveVm::scrubLive else null,
                    jumpBackOptions = if (isTunedLive && canRewindLive) liveVm::currentJumpOptions else null,
                    onJumpBack = if (isTunedLive && canRewindLive) liveVm::jumpBackTo else null,
                    jumpBackWindowSec = if (isTunedLive && canRewindLive) liveVm::currentCatchupWindowSec else null,
                    watchingWallMs = liveVm.watchingWallMs.collectAsStateWithLifecycle().value,
                    timeshiftOffsetSec = if (isTunedLive) timeshiftOffset else null,
                    onTuneToNumber = if (directTuneEnabled && isTunedLive && isLiveStream && timeshiftOffset == null && previewChannel != null) liveVm::tuneByNumber else null,
                    directTuneContextKey = previewChannel?.id ?: 0L, compatMode = if (isTunedLive) !liveOnExo else null,
                    onToggleCompatMode = if (isTunedLive && timeshiftOffset == null && previewChannel?.drmConfig == null) liveVm::toggleForceMpv else null,
                    vodOnExo = if (!isLiveStream && !isTunedLive) vodExoActive else null,
                    onToggleVodEngine = if (!isLiveStream && !isTunedLive) player::toggleVodEngine else null,
                    onSearchSubtitles = if (!isLiveStream && zapSource != MainSection.LIVE_TV && subtitleContext != null) { { showSubtitleSearch = true } } else null,
                    onSelectLocalSubtitle = if (!isLiveStream && zapSource != MainSection.LIVE_TV && subtitleContext != null) { { showLocalSubPicker = true } } else null,
                    favorite = favActive, onToggleFavorite = favToggle,
                    liveEpgCard = if (zapSource == MainSection.LIVE_TV) { {
                        val epg by liveVm.nowNext.collectAsStateWithLifecycle()
                        val archiveEpg by liveVm.archiveNowNext.collectAsStateWithLifecycle()
                        val watching by liveVm.watchingWallMs.collectAsStateWithLifecycle()
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (watching != null) tv.own.owntv.features.shell.components.LiveEpgCard(epg = archiveEpg, variant = tv.own.owntv.features.shell.components.EpgCardVariant.ARCHIVE, atMs = watching)
                            tv.own.owntv.features.shell.components.LiveEpgCard(epg = epg, modifier = if (watching == null) Modifier else Modifier.alpha(0.55f))
                        }
                    } } else null,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showSubtitleSearch) tv.own.owntv.features.subtitles.SubtitleSearchScreen(onDismiss = { showSubtitleSearch = false }, modifier = Modifier.fillMaxSize())
                if (showLocalSubPicker) tv.own.owntv.ui.components.StorageBrowser(title = stringResource(R.string.content_subtitle_select_file), mode = tv.own.owntv.ui.components.BrowseMode.FILE, fileExtensions = setOf("srt", "ass", "ssa", "vtt", "webvtt"), onPick = { file -> showLocalSubPicker = false; scope.launch { runCatching { subtitleController.applyLocal(file) }.onFailure { e -> localSubToast.show(e.message ?: subtitleLoadFailed) } } }, onDismiss = { showLocalSubPicker = false })
                tv.own.owntv.ui.components.InAppToast(localSubToast)
                if (showChannelList && zapSource == MainSection.LIVE_TV) {
                    if (showCategoryBrowser) tv.own.owntv.features.shell.components.CategoryBrowserOverlay(categories = browserCategories, currentCategoryId = previewChannel?.categoryId, onSelect = { catId -> liveVm.loadChannelsForCategory(catId) }, onDismiss = { liveVm.hideCategoryBrowser() }, modifier = Modifier.fillMaxSize())
                    else if (zapChannels.isNotEmpty()) tv.own.owntv.features.shell.components.ChannelListOverlay(channels = zapChannels, currentId = previewChannel?.id, nowPlaying = overlayNowPlaying, title = zapOverlayTitle, showNumbers = directTuneEnabled, onSelect = { liveVm.ensurePlaying(it); showChannelList = false }, onDismiss = { showChannelList = false }, onOpenCategories = { liveVm.showCategories() }, modifier = Modifier.fillMaxSize())
                }
                if (showHistoryList && zapSource == MainSection.LIVE_TV && historyChannels.isNotEmpty()) tv.own.owntv.features.shell.components.ChannelListOverlay(channels = historyChannels, currentId = previewChannel?.id, nowPlaying = historyNowPlaying, title = stringResource(R.string.content_history), showNumbers = directTuneEnabled, alignEnd = true, onSelect = { liveVm.ensurePlaying(it); showHistoryList = false }, onDismiss = { showHistoryList = false }, modifier = Modifier.fillMaxSize())
            }
        }
      }

      if (showExit) ExitDialog(onConfirm = onExitApp, onDismiss = { showExit = false })
      if (showAvatarPicker) AvatarPickerDialog(selectedId = avatarId, onSelect = onSetAvatar, onDismiss = { showAvatarPicker = false })
      if (showPlaylistPicker) PlaylistPickerDialog(playlists = playlists, activeId = activePlaylistId, onSelect = onSelectPlaylist, onDismiss = { showPlaylistPicker = false })
      val incompleteRestore by settingsRepo.restoreInProgress.collectAsStateWithLifecycle(initialValue = null)
      var restoreNoticeDismissed by remember { mutableStateOf(false) }
      incompleteRestore?.takeIf { !restoreNoticeDismissed }?.let { description ->
          IncompleteRestoreDialog(
              description = description,
              onDismiss = {
                  restoreNoticeDismissed = true
                  scope.launch { settingsRepo.clearRestoreMarker() }
              },
          )
      }
      val updateManager = koinInject<UpdateManager>()
      val updateState by updateManager.state.collectAsStateWithLifecycle()
      var showStartupToast by remember { mutableStateOf(false) }
      var showChangelog by remember { mutableStateOf(false) }
      val updateCheckOnStart by settingsRepo.updateCheckOnStart.collectAsStateWithLifecycle(initialValue = false)
      LaunchedEffect(updateCheckOnStart) { if (updateCheckOnStart && !showStartupToast) { kotlinx.coroutines.delay(5_000); showStartupToast = true; updateManager.check() } }
      LaunchedEffect(updateState) { if (showStartupToast && updateState is UpdateManager.State.Available) { showStartupToast = false; showChangelog = true } }
      if (showChangelog) UpdateDialog(onDismiss = { showChangelog = false; showStartupToast = false; updateManager.reset() }, checkOnOpen = false)
      else if (showStartupToast && selectedSection != MainSection.SETTINGS && playerMode == PlayerMode.NONE) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) { UpdateStatusToast(onDone = { showStartupToast = false; updateManager.reset() }, onViewChangelog = { showChangelog = true }) }
    }
    }
}

@Composable
private fun OfflineBanner() {
    val colors = OwnTVTheme.colors
    Row(modifier = Modifier.fillMaxWidth().background(colors.tertiaryContainer).padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.content_offline_banner), style = MaterialTheme.typography.labelLarge, color = colors.onTertiaryContainer)
    }
}

private val MainSection.emptyIcon: OwnTVIcon
    get() = when (this) {
        MainSection.SEARCH -> OwnTVIcon.SEARCH
        MainSection.HOME -> OwnTVIcon.HOME
        MainSection.LIVE_TV -> OwnTVIcon.LIVE_TV
        MainSection.MOVIES -> OwnTVIcon.MOVIES
        MainSection.SERIES -> OwnTVIcon.SERIES
        MainSection.DOWNLOADS -> OwnTVIcon.DOWNLOADS
        MainSection.EPG -> OwnTVIcon.EPG
        MainSection.MULTISCREEN -> OwnTVIcon.ZOOM
        MainSection.SETTINGS -> OwnTVIcon.SETTINGS
    }

private fun railCategoriesFor(section: MainSection): List<RailCategory> = when (section) {
    MainSection.SEARCH, MainSection.HOME, MainSection.EPG, MainSection.SETTINGS, MainSection.MULTISCREEN -> emptyList()
    MainSection.LIVE_TV -> listOf(
        RailCategory("Favorites", OwnTVIcon.FAVORITE, R.string.content_category_favorites),
        RailCategory("History", OwnTVIcon.HISTORY, R.string.content_category_history),
        RailCategory("All Channels", labelRes = R.string.content_category_all_channels, showGenreDot = false),
        RailCategory("United Kingdom", labelRes = R.string.content_category_united_kingdom),
        RailCategory("United States", labelRes = R.string.content_category_united_states),
        RailCategory("Germany", labelRes = R.string.content_category_germany),
        RailCategory("Sports", labelRes = R.string.content_category_sports),
    )
    MainSection.MOVIES -> listOf(
        RailCategory("Favorites", OwnTVIcon.FAVORITE, R.string.content_category_favorites),
        RailCategory("History", OwnTVIcon.HISTORY, R.string.content_category_history),
        RailCategory("All Movies", labelRes = R.string.content_category_all_movies, showGenreDot = false),
        RailCategory("Action", labelRes = R.string.content_category_action),
        RailCategory("Drama", labelRes = R.string.content_category_drama),
        RailCategory("Comedy", labelRes = R.string.content_category_comedy),
        RailCategory("Horror", labelRes = R.string.content_category_horror),
    )
    MainSection.SERIES -> listOf(
        RailCategory("Favorites", OwnTVIcon.FAVORITE, R.string.content_category_favorites),
        RailCategory("History", OwnTVIcon.HISTORY, R.string.content_category_history),
        RailCategory("All Series", labelRes = R.string.content_category_all_series, showGenreDot = false),
        RailCategory("Drama", labelRes = R.string.content_category_drama),
        RailCategory("Action", labelRes = R.string.content_category_action),
        RailCategory("Animation", labelRes = R.string.content_category_animation),
        RailCategory("Documentary", labelRes = R.string.content_category_documentary),
    )
    MainSection.DOWNLOADS -> listOf(
        RailCategory("All Downloads", labelRes = R.string.content_category_all_downloads, showGenreDot = false),
        RailCategory("Movies", labelRes = R.string.content_category_movies),
        RailCategory("Series", labelRes = R.string.content_category_series),
    )
}

@Composable
private fun placeholderCount(section: MainSection): String = when (section) {
    MainSection.SEARCH, MainSection.HOME, MainSection.EPG, MainSection.SETTINGS, MainSection.MULTISCREEN -> ""
    MainSection.LIVE_TV -> stringResource(R.string.content_zero_channels)
    MainSection.MOVIES -> stringResource(R.string.content_zero_movies)
    MainSection.SERIES -> stringResource(R.string.content_zero_series)
    MainSection.DOWNLOADS -> stringResource(R.string.content_zero_downloads)
}
