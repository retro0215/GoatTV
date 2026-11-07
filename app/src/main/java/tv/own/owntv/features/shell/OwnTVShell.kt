package tv.own.owntv.features.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.ignoreCanceledKeyReleases
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
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
import tv.own.owntv.features.rooms.SocialRoomsScreen
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
import tv.own.owntv.features.sports.SportsScreen
import tv.own.owntv.player.MiniPlayer
import tv.own.owntv.player.MpvVideoSurface
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlayerHud
import tv.own.owntv.player.dockedAt
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
    var fullscreenReturnSection by remember { mutableStateOf<MainSection?>(null) }
    var multiscreenReturnSection by remember { mutableStateOf<MainSection?>(null) }
    // Phase 4: the TV Guide's live preview — the channel the player was on, still playing (GuidePlayback).
    var guideSession by remember { mutableStateOf(GuidePlayback.NONE) }
    // 4.6.0: where the Guide's preview box is: the shell draws its live ExoPlayer view there (GuideVideoDock).
    val guideDock = remember { tv.own.owntv.player.VideoDockSlot() }
    var shellOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // Phase 4: the reminder banner, shown wherever the customer is (ReminderDelivery).
    var reminderBanner by remember { mutableStateOf<ReminderBannerState?>(null) }

    val exitMultiscreen = {
        val sec = multiscreenReturnSection ?: MainSection.LIVE_TV
        multiscreenReturnSection = null
        // Sports restores its browsing position (scroll, row, card) from its shared restoration state.
        if (sec == MainSection.SPORTS) restoreFocus = true
        onSelectSection(sec)
    }

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

    LaunchedEffect(liveOnExo, playerMode, guideSession) {
        // The Guide's live preview is audible, so it keeps audio focus and the media session too.
        playbackSession.attach(
            when (GuidePlayback.audioFocusOwner(playerMode != PlayerMode.NONE, guideSession, liveOnExo)) {
                GuidePlayback.FocusOwner.EXO -> liveVm.previewEngine
                GuidePlayback.FocusOwner.MPV -> mpvEngine
                GuidePlayback.FocusOwner.NONE -> null
            },
        )
    }
    // Any player shown again (Watch, a reminder, back from the Guide) ends the Guide's preview session.
    LaunchedEffect(playerMode) { if (playerMode != PlayerMode.NONE) guideSession = GuidePlayback.NONE }

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
    val exitPlayer = exit@{
        movieVm.saveProgressNow()
        seriesVm.saveEpisodeProgressNow()
        resumeVideo()
        // A live channel tuned from the Guide: Back returns to the Guide with the channel still playing in
        // its preview (Phase 4), rather than stopping it as every other exit does.
        // Full screen only: closing the mini player or the audio bar means stop.
        val keepInGuide = if (playerMode != PlayerMode.FULLSCREEN) GuidePlayback.NONE else GuidePlayback.onPlayerExit(
            section = selectedSection,
            isTunedLive = zapSource == MainSection.LIVE_TV && !catchupActive,
            isLiveStream = (liveOnExo || player.isLiveContent) && timeshiftOffset == null,
            returnSection = fullscreenReturnSection,
        )
        playerMode = PlayerMode.NONE
        showChannelList = false
        showHistoryList = false
        liveVm.hideCategoryBrowser()
        if (keepInGuide.live) {
            guideSession = keepInGuide
            // The Guide focuses its grid on the tuned channel once it has loaded; the sidebar until then.
            restoreFocus = true
            runCatching { sidebarFocus.requestFocus() }
            return@exit
        }
        liveVm.onFullscreenExited()
        player.stop()
        subtitleController.clear()
        if (selectedSection != MainSection.LIVE_TV) liveVm.clearLiveOnExo()
        val returnSec = fullscreenReturnSection
        if (returnSec != null) {
            fullscreenReturnSection = null
            onSelectSection(returnSec)
        }
        restoreFocus = true
        runCatching { sidebarFocus.requestFocus() }
        Unit
    }
    // Phase 4: the player's TV Guide button. The stream keeps playing; its picture moves to the Guide's
    // preview — no release, no re-tune. 4.6.0: on ExoPlayer the same video view shrinks into the preview
    // box, so the decoder keeps its surface (GuideVideoDock).
    val openGuideFromPlayer = {
        showChannelList = false
        showHistoryList = false
        liveVm.hideCategoryBrowser()
        resumeVideo()
        val session = GuidePlayback.openFromPlayer(selectedSection)
        playerMode = PlayerMode.NONE
        guideSession = session
        // The Guide opens with focus in the grid, on the playing channel's row.
        previewChannel?.let { epgVm.noteChannelTuned(it) }
        restoreFocus = true
        onSelectSection(MainSection.EPG)
        Unit
    }
    // Back in a Guide opened from the player: the same channel full screen again, over the section it sat on.
    // On ExoPlayer the docked video view grows back to full screen in the same frame (GuideVideoDock).
    val returnToPlayerFromGuide = {
        val back = guideSession.returnSection ?: MainSection.LIVE_TV
        guideSession = GuidePlayback.NONE
        restoreFocus = false
        playerMode = PlayerMode.FULLSCREEN
        onSelectSection(back)
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
        // Leaving the Guide while it plays live ends that session: the stream stops as the player's exit
        // would, except on Live TV, whose preview pane re-takes the ExoPlayer stream (muted).
        when (GuidePlayback.onSectionChanged(guideSession, selectedSection, playerMode != PlayerMode.NONE)) {
            null -> Unit
            GuidePlayback.Teardown.KEEP_FOR_LIVE_PANE -> {
                guideSession = GuidePlayback.NONE
                liveVm.onFullscreenExited()
                player.stop() // mpv has no pane preview to hand over to
            }
            GuidePlayback.Teardown.STOP -> {
                guideSession = GuidePlayback.NONE
                liveVm.onFullscreenExited()
                liveVm.clearLiveOnExo()
            }
        }
        // mpv may be the Guide's live picture, which keeps playing there.
        if (GuidePlayback.mayStopMpvOffLive(selectedSection, playerMode != PlayerMode.NONE, guideSession)) player.stop()
        if (selectedSection != MainSection.HOME || playerMode != PlayerMode.NONE) homeVm.stopPreview()
        // Settings never shows the live preview, yet a preview left running when the user came here would
        // keep its decoder and provider connection behind the settings pages. Stopping frees both; the
        // instance stays for the next preview. Live TV, Sports and Multiscreen draw this engine, and the mini player keeps playerMode
        // non-NONE, so none of them is touched.
        if (selectedSection == MainSection.SETTINGS && playerMode == PlayerMode.NONE) liveVm.stopPreview()
    }

    LaunchedEffect(selectedSection, playerMode, activeProfileId, activePlaylistId) {
        if (selectedSection == MainSection.HOME && playerMode == PlayerMode.NONE && (activeProfileId?.let { it >= 0 } == true)) {
            homeVm.refresh()
        }
    }

    val isMultiscreen = selectedSection == MainSection.MULTISCREEN

    // 4.6.0: the Guide's live ExoPlayer preview is the shell's own video view, placed over its preview box.
    val guideDocked = GuideVideoDock.docked(
        guideOnScreen = selectedSection == MainSection.EPG,
        previewSurface = GuidePlayback.previewSurface(guideSession, playerMode != PlayerMode.NONE, liveOnExo),
    )
    // Phase 4C: the view that drives the live decoder, named before any new view creates its surface (a
    // SideEffect runs after composition, ahead of layout and surfaceCreated). The shell's player view, in
    // full screen and docked in the Guide; elsewhere none, and the last surface offered wins as before.
    val surfaceOwner = GuideVideoDock.owner(fullscreen = playerMode == PlayerMode.FULLSCREEN, docked = guideDocked)
    androidx.compose.runtime.SideEffect { liveVm.previewEngine.setSurfaceOwner(surfaceOwner) }

    BackHandler {
        when {
            playerMode == PlayerMode.FULLSCREEN -> exitPlayer()
            showAvatarPicker -> showAvatarPicker = false
            showPlaylistPicker -> showPlaylistPicker = false
            showExit -> showExit = false
            // A Guide opened from the player: Back returns to that channel full screen.
            selectedSection == MainSection.EPG && GuidePlayback.back(guideSession) == GuidePlayback.Back.RETURN_TO_PLAYER -> returnToPlayerFromGuide()
            focusedLayer == ShellLayer.SIDEBAR -> showExit = true
            isMultiscreen -> exitMultiscreen()
            else -> runCatching { sidebarFocus.requestFocus() }
        }
    }

    // ---- Programme reminders (Phase 4): one listener for the whole app, while it is in the foreground ----
    // Until 4.6 this lived inside the full-screen player, and reminder events are not replayed, so one that
    // fired on Home, in the Guide or in Settings was lost. The rules are in ReminderDelivery.
    val programReminders = koinInject<tv.own.owntv.core.sync.work.ProgramReminders>()
    val reminderRelinker = koinInject<tv.own.owntv.core.sync.work.ReminderRelinker>()
    val handledReminders = remember { ReminderDelivery.Handled() }
    val reminderToast = tv.own.owntv.ui.components.rememberInAppToast()
    val reminderUnavailable = stringResource(R.string.content_channel_unavailable)
    // Where focus was when a banner opened outside the player, so Dismiss can put it back.
    val shellContentFocus = remember { FocusRequester() }
    // Whether focus is inside the shell's content (browse UI or Multiscreen), for the banner's Dismiss.
    var shellHasFocus by remember { mutableStateOf(false) }
    val isMultiscreenNow by androidx.compose.runtime.rememberUpdatedState(isMultiscreen)
    val playerShownNow by androidx.compose.runtime.rememberUpdatedState(playerMode != PlayerMode.NONE)
    // Tunes a reminder's channel full screen. From Multiscreen only after Watch: the layout is left first.
    suspend fun tuneReminderChannel(channelId: Long): Boolean {
        if (isMultiscreenNow) {
            multiscreenReturnSection = null
            onSelectSection(MainSection.LIVE_TV)
        }
        val tuned = liveVm.ensurePlayingByIdAsync(channelId)
        if (tuned && !liveVm.externalPlayerOn.value) openFullscreen(MainSection.LIVE_TV)
        return tuned
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            programReminders.repairLegacyOwners()
            reminderRelinker.restoreAfterRestart()
        }
    }
    val shellLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(shellLifecycle) {
        shellLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            launch {
                tv.own.owntv.core.notification.ReminderEventBus.reminderEvents.collect { reminder ->
                    // Another profile's reminder: no banner (its Watch could only be refused).
                    if (!ReminderDelivery.showWarning(programReminders.belongsToActiveProfile(reminder))) return@collect
                    if (handledReminders.contains(reminder.channelId, reminder.programStartMs)) return@collect
                    if (reminderBanner == null && !playerShownNow) saveShellFocus(shellContentFocus)
                    reminderBanner = ReminderBannerState(reminder, starting = false)
                }
            }
            launch {
                tv.own.owntv.core.notification.ReminderEventBus.autotuneEvents.collect { reminder ->
                    // Only the active profile's reminder, on its own playlist's channel; an orphaned,
                    // ambiguous or other-profile reminder tunes nothing (tuneChannelId is null).
                    val channelId = programReminders.tuneChannelId(reminder)
                    val place = ReminderDelivery.place(isMultiscreenNow, playerShownNow)
                    val already = handledReminders.contains(reminder.channelId, reminder.programStartMs)
                    when (val start = ReminderDelivery.onStart(place, channelId, already)) {
                        is ReminderDelivery.Start.Tune -> {
                            handledReminders.claim(reminder.channelId, reminder.programStartMs)
                            if (reminderBanner?.matches(reminder) == true) reminderBanner = null
                            tuneReminderChannel(start.channelId)
                        }
                        is ReminderDelivery.Start.Confirm -> {
                            if (reminderBanner == null) saveShellFocus(shellContentFocus)
                            reminderBanner = ReminderBannerState(reminder, starting = true)
                        }
                        ReminderDelivery.Start.Ignore -> Unit
                    }
                }
            }
        }
    }
    val dismissReminderBanner = {
        // The banner is its own window: the control under it (a programme dialog's button, the grid, the
        // player) keeps its focus and gets the remote back as the banner closes (ReminderDelivery).
        val restore = ReminderDelivery.restoreFocusOnDismiss(playerShownNow, shellHasFocus)
        reminderBanner = null
        if (restore) scope.launch {
            kotlinx.coroutines.android.awaitFrame()
            if (!restoreShellFocus(shellContentFocus)) runCatching { sidebarFocus.requestFocus() }
        }
        Unit
    }
    val watchReminder: (tv.own.owntv.core.database.entity.ReminderEntity) -> Unit = { reminder ->
        scope.launch {
            reminderBanner = null
            val channelId = programReminders.tuneChannelId(reminder)
            if (channelId != null && handledReminders.claim(reminder.channelId, reminder.programStartMs) && tuneReminderChannel(channelId)) {
                // Watched now: drop the row and its pending tune (same as 4.5.0).
                programReminders.cancel(reminder.id)
            } else {
                reminderToast.show(reminderUnavailable)
            }
        }
    }

    val sidebarLabel = remember { tv.own.owntv.features.shell.components.SidebarLabelState() }
    val glass = LocalGlass.current
    val shellBase = if (glass.isGlassy(GlassSurface.PANELS) || glass.isGlassy(GlassSurface.SIDEBAR)) Color.Transparent else colors.background
    val shellTopBarHeight = if (playerMode == PlayerMode.AUDIO) Dimens.TopBarHeight else Dimens.TopBarCompactHeight

    CompositionLocalProvider(LocalContentScrolled provides contentScrolled) {
    Box(
        modifier = modifier.fillMaxSize().background(if (isMultiscreen) Color.Black else shellBase)
            // 4.6.0: the docked video view is placed relative to this box (GuideVideoDock).
            .onGloballyPositioned { shellOrigin = it.positionInRoot() }
            // 4.6.0: when the reminder warning's window takes focus mid-press, the release Android sends here
            // is canceled: no button, Guide OK or Multiscreen move acts on it (CanceledKeyRelease).
            .ignoreCanceledKeyReleases(),
    ) {
      
      if (isMultiscreen) {
          // CLEAN SEPARATION: Multiscreen mode hides EVERYTHING else.
          MultiscreenScreen(
              onBack = exitMultiscreen,
              onChildFocused = { focusedLayer = ShellLayer.CONTENT },
              modifier = Modifier.fillMaxSize().onFocusChanged { shellHasFocus = it.hasFocus }.focusRequester(shellContentFocus).focusGroup(),
          )
      } else {
        // Browse UI — hidden while the player is fullscreen.
        if (playerMode != PlayerMode.FULLSCREEN) {
          Column(modifier = Modifier.fillMaxSize().onFocusChanged { shellHasFocus = it.hasFocus }.focusRequester(shellContentFocus).focusGroup()) {
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
                focusLabel = sidebarLabel,
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
                            onOpenMovie = { movie -> movieVm.openMovieDetails(movie); onSelectSection(MainSection.MOVIES) },
                            onOpenSeries = { series -> seriesVm.openSeries(series); onSelectSection(MainSection.SERIES) },
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
                            onOpenRoom = { roomId -> onSelectSection(MainSection.SOCIAL_ROOMS) },
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
                            onOpenMultiscreen = { liveVm.stopPreview(); multiscreenReturnSection = MainSection.LIVE_TV; onSelectSection(MainSection.MULTISCREEN) },
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
                        selectedSection == MainSection.SPORTS -> SportsScreen(
                            onOpenChannel = { channel, sectionChannels ->
                                liveVm.watchFullscreen(channel, sectionChannels)
                                zapSource = MainSection.LIVE_TV
                                homeVm.stopPreview()
                                fullscreenReturnSection = MainSection.SPORTS
                                if (playerMode != PlayerMode.MINI && !liveVm.externalPlayerOn.value) {
                                    playerMode = PlayerMode.FULLSCREEN
                                }
                                onSelectSection(MainSection.LIVE_TV)
                            },
                            onOpenMultiscreen = { liveVm.previewEngine.stop(); multiscreenReturnSection = MainSection.SPORTS; onSelectSection(MainSection.MULTISCREEN) },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus, onRestored = { restoreFocus = false },
                            modifier = Modifier.fillMaxSize(),
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
                            // 4.6.0: first OK on a programme plays its channel here, the Guide staying open. The
                            // one shared tune path, on the shell's one player: no second view, no fullscreen.
                            onPreviewChannel = { ch ->
                                when (GuidePlayback.preview(guideSession, playerMode != PlayerMode.NONE, liveVm.externalPlayerOn.value, previewChannel?.id, ch.id)) {
                                    GuidePlayback.Preview.UNAVAILABLE -> tv.own.owntv.features.epg.GuideOkStage.Preview.UNAVAILABLE
                                    // Never re-tuned: the Guide opens the programme instead.
                                    GuidePlayback.Preview.ALREADY_PLAYING -> tv.own.owntv.features.epg.GuideOkStage.Preview.ALREADY_PLAYING
                                    GuidePlayback.Preview.TUNE -> {
                                        liveVm.previewFromGuide(ch); zapSource = MainSection.LIVE_TV; homeVm.stopPreview()
                                        guideSession = GuidePlayback.afterPreview(guideSession)
                                        tv.own.owntv.features.epg.GuideOkStage.Preview.TUNED
                                    }
                                }
                            },
                            okStageKey = activeProfileId to activePlaylistId,
                            watchingChannelId = previewChannel?.id,
                            // Phase 4: the channel the player was on keeps playing here; moving never tunes.
                            previewChannel = previewChannel.takeIf { guideSession.live },
                            previewVideo = when (GuidePlayback.previewSurface(guideSession, playerMode != PlayerMode.NONE, liveOnExo)) {
                                // 4.6.0: an empty box; the shell's own video view is drawn over it (GuideVideoDock).
                                GuidePlayback.Surface.EXO -> { m -> tv.own.owntv.player.VideoDockTarget(guideDock, m) }
                                GuidePlayback.Surface.MPV -> { m -> MpvVideoSurface(player = player, modifier = m, autoFrameRate = false) }
                                GuidePlayback.Surface.PLACEHOLDER -> null
                            },
                            onRestored = { restoreFocus = false }, onContentScrolled = { contentScrolled = it },
                            modifier = Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }.focusGroup(),
                        )
                        selectedSection == MainSection.SOCIAL_ROOMS -> SocialRoomsScreen(
                            onBack = { runCatching { sidebarFocus.requestFocus() } },
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
          // 4.6.0: the focused rail icon's name, drawn over the content (the rail itself never widens).
          if (focusedLayer == ShellLayer.SIDEBAR) {
              tv.own.owntv.features.shell.components.SidebarFocusLabel(sidebarLabel, Modifier.fillMaxSize())
          }
      }

      // One slot for the video layer — full screen, mini, or docked in the Guide's preview — so its video view
      // (and the decoder's surface) is never rebuilt when the picture moves between them (GuideVideoDock).
      if (GuideVideoDock.layerShown(fullscreen = playerMode == PlayerMode.FULLSCREEN, mini = playerMode == PlayerMode.MINI, docked = guideDocked)) {
        val dockRect = guideDock.bounds
        val placement = GuideVideoDock.placement(
            fullscreen = playerMode == PlayerMode.FULLSCREEN, mini = playerMode == PlayerMode.MINI,
            docked = guideDocked, previewKnown = dockRect != null,
        )
        val isFull = placement == GuideVideoDock.Placement.FULL_SCREEN
        val controlsShown = GuideVideoDock.controlsShown(fullscreen = playerMode == PlayerMode.FULLSCREEN)
        Box(
            modifier = when {
                placement == GuideVideoDock.Placement.GUIDE_PREVIEW && dockRect != null ->
                    Modifier.dockedAt(dockRect, shellOrigin).background(Color.Black)
                isFull -> Modifier.fillMaxSize().background(Color.Black)
                else -> Modifier.align(miniPos.alignment).padding(24.dp).fillMaxWidth(tv.own.owntv.player.MiniPlayerSize.fraction(miniSizePct)).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            },
        ) {
            if (liveOnExo) {
                tv.own.owntv.player.ExoPreviewSurface(engine = liveVm.previewEngine, modifier = Modifier.fillMaxSize(), keepAwake = true, autoFrameRate = playerMode == PlayerMode.FULLSCREEN && autoFrameRate, role = tv.own.owntv.player.SurfaceOwnership.Role.PLAYER)
            } else {
                MpvVideoSurface(player = player, modifier = Modifier.fillMaxSize(), autoFrameRate = isFull && autoFrameRate)
            }
            val audioOnlyMedia by if (liveOnExo) liveVm.previewEngine.audioOnlyMedia.collectAsStateWithLifecycle() else player.audioOnlyMedia.collectAsStateWithLifecycle()
            if (audioOnlyMedia) tv.own.owntv.player.AudioOnlyBadge(modifier = Modifier.fillMaxSize(), compact = !isFull)
            if (!liveOnExo) {
                tv.own.owntv.player.SubtitleOverlay(player = player, modifier = Modifier.fillMaxSize(), sizeScale = if (isFull) 1f else (tv.own.owntv.player.MiniPlayerSize.fraction(miniSizePct) * 1.5f).coerceIn(0.35f, 0.7f))
            }
            if (controlsShown && !autoFrameRate && !afrPrompted) {
                val activeFps by if (liveOnExo) liveVm.previewEngine.videoFps.collectAsStateWithLifecycle() else player.videoFps.collectAsStateWithLifecycle()
                tv.own.owntv.player.AutoFrameRatePrompt(fps = activeFps, afrEnabled = autoFrameRate, alreadyPrompted = afrPrompted, onEnable = { scope.launch { settingsRepo.setAutoFrameRate(true); settingsRepo.setAutoFrameRatePrompted() } }, onDismiss = { scope.launch { settingsRepo.setAutoFrameRatePrompted() } })
            }
            if (controlsShown) {
                // The reminder listener and banner live at the shell's root now (Phase 4), so they work
                // in every section, not only while this player is mounted.
                val activeReminderBanner = reminderBanner

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
                        inert = showChannelList || showHistoryList || showCategoryBrowser || showSubtitleSearch || showLocalSubPicker || activeReminderBanner != null,
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
                        // Phase 4B: one guide only — the full TV Guide (the bottom-strip guide is gone). Offered on
                        // any tuned live channel, ExoPlayer or mpv: it no longer reads mpv's isLiveContent, a
                        // plain field Compose never re-reads, which hid the button on mpv channels.
                        onOpenFullGuide = if (GuidePlayback.canOpenGuide(isTunedLive, hasChannel = previewChannel != null) && timeshiftOffset == null) openGuideFromPlayer else null,
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
            } else {
                MiniPlayer(
                    player = if (liveOnExo) liveVm.previewEngine else mpvEngine,
                    onExpand = expandPlayer,
                    onClose = exitPlayer,
                    onCycleSize = {
                        scope.launch {
                            settingsRepo.setMiniPlayerSizePct(
                                tv.own.owntv.player.MiniPlayerSize.next(miniSizePct)
                            )
                        }
                    },
                    onCyclePosition = {
                        scope.launch {
                            settingsRepo.setMiniPlayerPosition(miniPos.next().name)
                        }
                    },
                    onAudioMode = toAudioMode,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
      }

      // Over everything: the player, any section, Multiscreen (Phase 4), and any open dialog (4.6.0: its own window).
      reminderBanner?.let { banner ->
          ReminderBanner(
              reminder = banner.reminder,
              starting = banner.starting,
              onWatch = { watchReminder(banner.reminder) },
              onDismiss = dismissReminderBanner,
          )
      }
      tv.own.owntv.ui.components.InAppToast(reminderToast)

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
        MainSection.SPORTS -> OwnTVIcon.STAR
        MainSection.DOWNLOADS -> OwnTVIcon.DOWNLOADS
        MainSection.EPG -> OwnTVIcon.EPG
        MainSection.MULTISCREEN -> OwnTVIcon.ZOOM
        MainSection.SOCIAL_ROOMS -> OwnTVIcon.PERSON
        MainSection.SETTINGS -> OwnTVIcon.SETTINGS
    }

private fun railCategoriesFor(section: MainSection): List<RailCategory> = when (section) {
    MainSection.SEARCH, MainSection.HOME, MainSection.EPG, MainSection.SETTINGS, MainSection.MULTISCREEN, MainSection.SPORTS, MainSection.SOCIAL_ROOMS -> emptyList()
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
    MainSection.SEARCH, MainSection.HOME, MainSection.EPG, MainSection.SETTINGS, MainSection.MULTISCREEN, MainSection.SPORTS, MainSection.SOCIAL_ROOMS -> ""
    MainSection.LIVE_TV -> stringResource(R.string.content_zero_channels)
    MainSection.MOVIES -> stringResource(R.string.content_zero_movies)
    MainSection.SERIES -> stringResource(R.string.content_zero_series)
    MainSection.DOWNLOADS -> stringResource(R.string.content_zero_downloads)
}

/** A reminder banner on screen: the 60-second warning, or (Multiscreen only) "starting now". */
private data class ReminderBannerState(val reminder: tv.own.owntv.core.database.entity.ReminderEntity, val starting: Boolean) {
    fun matches(other: tv.own.owntv.core.database.entity.ReminderEntity): Boolean =
        reminder.channelId == other.channelId && reminder.programStartMs == other.programStartMs
}

/** Remembers which control inside [group] has focus, so a banner can hand it back when it closes. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun saveShellFocus(group: FocusRequester) {
    runCatching { group.saveFocusedChild() }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun restoreShellFocus(group: FocusRequester): Boolean =
    runCatching { group.restoreFocusedChild() }.getOrDefault(false)

/**
 * The reminder banner: the programme, its channel, Watch and Dismiss. Takes focus while shown; Back (on
 * release) dismisses. Dismiss never deletes the reminder or cancels its start. Multiscreen shows it
 * again at the start ([starting]) instead of tuning, so leaving the layout always needs Watch.
 *
 * 4.6.0: its own window, above every other one. A programme dialog (or any popup) is a window of its own
 * too, and a banner drawn in the app's window sat under it, out of the remote's reach. Closing the banner's
 * window hands the remote straight back to the window below, on the control that had it.
 */
@Composable
private fun ReminderBanner(
    reminder: tv.own.owntv.core.database.entity.ReminderEntity,
    starting: Boolean,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {}, // Back is the banner's own (press consumed, release dismisses), below.
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The banner draws its own scrim; no second dim from the window.
        val view = androidx.compose.ui.platform.LocalView.current
        val window = remember(view) { generateSequence(view.parent) { it.parent }.filterIsInstance<androidx.compose.ui.window.DialogWindowProvider>().firstOrNull()?.window }
        androidx.compose.runtime.SideEffect { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
        ReminderBannerContent(reminder, starting, onWatch, onDismiss)
    }
}

@Composable
private fun ReminderBannerContent(
    reminder: tv.own.owntv.core.database.entity.ReminderEntity,
    starting: Boolean,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val watchFocus = remember { FocusRequester() }
    val dismissFocus = remember { FocusRequester() }
    val bannerBackKeys = remember(reminder) { tv.own.owntv.features.shell.components.GuideBackKeys() }
    LaunchedEffect(reminder, starting) {
        kotlinx.coroutines.android.awaitFrame()
        runCatching { watchFocus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            // An OK still held from the screen below (its press went there) must not press Watch here.
            .longPressMenuGuard()
            // Nor may a release canceled by a window opening above the banner press Watch or dismiss it.
            .ignoreCanceledKeyReleases()
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                    // Press consumed, release dismisses: dismissing on the press let the release fall
                    // through to the screen's Back (which left Live TV, or the Multiscreen layout).
                    if (e.type == KeyEventType.KeyDown) {
                        bannerBackKeys.onPress()
                    } else if (e.type == KeyEventType.KeyUp &&
                        bannerBackKeys.onRelease(reminderDialogOpen = true) != tv.own.owntv.features.shell.components.GuideBackKeys.Step.NONE
                    ) {
                        onDismiss()
                    }
                    true
                } else if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.DirectionUp, Key.DirectionDown -> true // block vertical D-pad in the banner
                        else -> false // Left, Right and OK reach the buttons
                    }
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E1E))
                .border(1.dp, OwnTVTheme.colors.primary.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (starting) R.string.content_reminder_started_title else R.string.content_reminder_title),
                style = MaterialTheme.typography.labelLarge,
                color = OwnTVTheme.colors.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                reminder.programTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(if (starting) R.string.content_reminder_started_message else R.string.content_reminder_message, reminder.channelName),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReminderWarningButton(
                    label = stringResource(R.string.content_reminder_watch),
                    onClick = onWatch,
                    focusRequester = watchFocus,
                    modifier = Modifier.weight(1f),
                )
                ReminderWarningButton(
                    label = stringResource(R.string.content_reminder_dismiss),
                    onClick = onDismiss,
                    focusRequester = dismissFocus,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ReminderWarningButton(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val bg = if (isFocused) colors.primary else Color(0xFF2A2A2A)
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.2f)
    val borderWidth = if (isFocused) 4.dp else 1.dp
    val textColor = if (isFocused) colors.onPrimary else Color.White

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.hasFocus }
            // ONE focus target that handles OK (Phase 4B). A separate focusable modifier ahead of clickable made
            // two: the banner's focus request landed on the plain one, so the first OK did nothing and only
            // a second press (after focus moved on to the clickable node) acted.
            .clickable { onClick() }
            .graphicsLayer {
                if (isFocused) {
                    scaleX = 1.05f
                    scaleY = 1.05f
                }
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
