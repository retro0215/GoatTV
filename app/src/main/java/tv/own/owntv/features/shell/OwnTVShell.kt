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
    weatherInfo: tv.own.owntv.core.weather.WeatherInfo? = null, // Phase 7
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
    // Each destination reports whether content has passed the 8 dp threshold. Keying this state to
    // the section prevents a scrolled screen from leaving the next destination's chrome condensed.
    var contentScrolled by remember(selectedSection) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val sidebarFocus = remember { FocusRequester() }
    val homeFirstRowFocus = remember { FocusRequester() }
    var focusedLayer by remember { mutableStateOf(ShellLayer.SIDEBAR) }
    var showExit by remember { mutableStateOf(false) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var playerMode by remember { mutableStateOf(PlayerMode.NONE) }
    // Deep-link: the Guide's "Add EPG" button switches to Settings and opens EPG Sources → add.
    var openEpgAdd by remember { mutableStateOf(false) }
    // One-shot: set when leaving the player so the returning browse screen re-focuses the item you played.
    var restoreFocus by remember { mutableStateOf(false) }
    var restoreTrendingSearchFocus by remember { mutableStateOf(false) }
    var trendingSearchActive by remember { mutableStateOf(false) }
    val player = koinInject<OwnTVPlayer>()
    // Docked mini-player size (% of screen width) + position, configurable in Settings and from the
    // mini-player's own controls. Read straight from settings so both entry points stay in sync.
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
    // Local subtitle-file picker (plan §7) — the same TV-safe in-app browser local M3U import uses.
    var showLocalSubPicker by remember { mutableStateOf(false) }
    val localSubToast = tv.own.owntv.ui.components.rememberInAppToast()
    // Metadata allowance: tell the user ONCE per app start that their daily share of the shared
    // metadata service is gone, rather than letting posters and plots quietly stop appearing. Lives in
    // the shell because it can happen on any screen, and the shell is the one toast that is always
    // composed. `remember` (not rememberSaveable) is the once-per-launch scope we want.
    val metadataBudget = koinInject<tv.own.owntv.core.metadata.MetadataBudget>()
    val budgetRefusedAt by metadataBudget.refusedAt.collectAsStateWithLifecycle()
    var budgetNoticeShown by remember { mutableStateOf(false) }
    val budgetNotice = androidx.compose.ui.res.stringResource(tv.own.owntv.R.string.settings_metadata_limit_reached)
    LaunchedEffect(budgetRefusedAt) {
        if (budgetRefusedAt > 0L && !budgetNoticeShown) {
            budgetNoticeShown = true
            localSubToast.show(budgetNotice)
        }
    }
    val mpvEngine = remember(player) { tv.own.owntv.player.MpvPlaybackEngine(player) }
    // Audio focus + MediaSession (F27). This is the only place that knows which engine currently owns
    // the speaker, so it hands that engine over and takes it back when the player closes.
    val playbackSession = koinInject<tv.own.owntv.player.PlaybackSession>()
    val launcherIntegrationRepository = koinInject<LauncherIntegrationRepository>()
    val homeVm = org.koin.androidx.compose.koinViewModel<HomeViewModel>()
    val movieVm = org.koin.androidx.compose.koinViewModel<MovieViewModel>()
    val seriesVm = org.koin.androidx.compose.koinViewModel<SeriesViewModel>()
    val searchVm = org.koin.androidx.compose.koinViewModel<SearchViewModel>()
    // Same activity-scoped instances the Live/Guide screens use — lets the fullscreen HUD zap channels
    // up/down (CH+/CH-). Guide tunes start through LiveViewModel too (they set zapSource = LIVE_TV), so
    // there is exactly ONE zap path: liveVm's. The Guide keeps its own EpgViewModel only for the grid.
    val liveVm = org.koin.androidx.compose.koinViewModel<LiveViewModel>()
    val epgVm = org.koin.androidx.compose.koinViewModel<tv.own.owntv.features.epg.EpgViewModel>()
    val liveCanZap by liveVm.canZap.collectAsStateWithLifecycle()
    // Full-screen is running on the ExoPlayer engine (a promoted Live preview) rather than mpv.
    val liveOnExo by liveVm.liveOnExo.collectAsStateWithLifecycle()
    // A catch-up archive programme is playing (Guide "Watch from start" or the Live TV catch-up picker)
    // rather than the live stream — the HUD swaps live-only controls for the VOD ones.
    val catchupActive by liveVm.catchupActive.collectAsStateWithLifecycle()
    val vodExoActive by player.exoActiveState.collectAsStateWithLifecycle()
    // Publish the active engine to the system (audio focus + MediaSession), and detach when the player
    // is closed — an inactive session must not keep answering the TV's transport keys or the Assistant.
    LaunchedEffect(liveOnExo, playerMode) {
        playbackSession.attach(
            if (playerMode == PlayerMode.NONE) null else if (liveOnExo) liveVm.previewEngine else mpvEngine,
        )
    }
    // Auto frame rate: only ever applied to the FULL-SCREEN surface (never the mini-player or the
    // in-pane Live preview) — see FrameRateController.
    val autoFrameRate by settingsRepo.autoFrameRate.collectAsStateWithLifecycle(initialValue = false)
    // ...and the one-time suggestion to turn it on, for the 25-fps-on-60-Hz judder the direct render path
    // cannot fix by itself (F13). `true` until the flag is read, so it can never flash on first frame.
    val afrPrompted by settingsRepo.autoFrameRatePrompted.collectAsStateWithLifecycle(initialValue = true)
    // Direct tune (type a channel number on the remote). Settings → Video Player → Live TV; default on.
    val directTuneEnabled by settingsRepo.directTune.collectAsStateWithLifecycle(initialValue = true)
    // "Prefer EPG logos": start following the setting once, here rather than in Application.onCreate —
    // the store queries nothing at all while the toggle is off, so cold start stays free of EPG reads.
    val epgDaoForLogos = koinInject<tv.own.owntv.core.database.dao.EpgDao>()
    val logoScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        tv.own.owntv.core.epg.EpgLogoStore.start(logoScope, settingsRepo, epgDaoForLogos)
    }
    // Live rewind / timeshift: whether the live channel supports catch-up, and how far behind live we are.
    val canRewindLive by liveVm.canRewindLive.collectAsStateWithLifecycle()
    val timeshiftOffset by liveVm.timeshiftOffsetSec.collectAsStateWithLifecycle()
    // Which section armed the current fullscreen stream — picks whose channel list CH+/CH- step through.
    var zapSource by remember { mutableStateOf<MainSection?>(null) }
    // In-player channel-list overlay (Left while controls hidden, live only).
    var showChannelList by remember { mutableStateOf(false) }
    // In-player watch-history list (Right while controls hidden, live only).
    var showHistoryList by remember { mutableStateOf(false) }
    val zapChannels by liveVm.zapChannels.collectAsStateWithLifecycle()
    val zapListTitle by liveVm.zapListTitle.collectAsStateWithLifecycle()
    val zapListKey by liveVm.zapListKey.collectAsStateWithLifecycle()
    // Favorites and History have no provider name to show — their labels are UI strings, so the overlay
    // used to head both of them "All channels".
    val zapOverlayTitle = zapListTitle ?: when (zapListKey) {
        LiveKey.Favorites -> stringResource(R.string.content_category_favorites)
        LiveKey.History -> stringResource(R.string.content_category_history)
        LiveKey.Catchup -> stringResource(R.string.content_catchup)
        else -> stringResource(R.string.content_category_all_channels)
    }
    val showCategoryBrowser by liveVm.showCategoryBrowser.collectAsStateWithLifecycle()
    val browserCategories by liveVm.browserCategories.collectAsStateWithLifecycle()
    val previewChannel by liveVm.previewChannel.collectAsStateWithLifecycle()
    // Favorite state for the player HUD's in-stream favorite toggle (live channel / movie / series).
    val liveFavoriteIds by liveVm.favoriteIds.collectAsStateWithLifecycle()
    val playingMovie by movieVm.playingMovie.collectAsStateWithLifecycle()
    val movieFavoriteIds by movieVm.favoriteIds.collectAsStateWithLifecycle()
    val playingSeries by seriesVm.playingSeries.collectAsStateWithLifecycle()
    val seriesFavoriteIds by seriesVm.favoriteIds.collectAsStateWithLifecycle()
    // Current programme per channel for the in-player channel list overlay (small subtitle under each row).
    // Only resolved while the overlay is actually open. Keyed on the channel set so a zap-list change re-resolves.
    val overlayNowPlaying by produceState<Map<Long, String>>(emptyMap(), showChannelList, zapChannels) {
        if (!showChannelList || zapChannels.size <= 1) { value = emptyMap(); return@produceState }
        value = runCatching { liveVm.nowPlayingFor(zapChannels) }.getOrDefault(emptyMap())
    }
    // Recently-watched channels for the right-hand history overlay — re-read each time it opens (and
    // after a zap, since tuning writes a new history row) so the newest channel is always on top.
    val historyChannels by produceState(emptyList<ChannelEntity>(), showHistoryList, previewChannel?.id) {
        if (!showHistoryList) { value = emptyList(); return@produceState }
        value = runCatching { liveVm.historyChannels() }.getOrDefault(emptyList())
    }
    val historyNowPlaying by produceState<Map<Long, String>>(emptyMap(), historyChannels) {
        if (historyChannels.isEmpty()) { value = emptyMap(); return@produceState }
        value = runCatching { liveVm.nowPlayingFor(historyChannels) }.getOrDefault(emptyMap())
    }
    // Batch 7 — the single most-recent resumable item, surfaced as a shared top-bar "Continue" chip.
    val continueTarget by homeVm.continueTarget.collectAsStateWithLifecycle()

    // Per-profile startup action runs once when the authenticated shell first appears. With one unlocked
    // profile that is immediately; profile/PIN gates keep the shell out of composition until authorized.
    val resumeSettings = koinInject<tv.own.owntv.features.settings.data.SettingsRepository>()
    val startupChannelUnavailable = androidx.compose.ui.res.stringResource(tv.own.owntv.R.string.settings_startup_channel_unavailable)
    LaunchedEffect(Unit) {
        if (playerMode != PlayerMode.NONE) return@LaunchedEffect
        val pid = resumeSettings.activeProfileId.first()
        when (resumeSettings.startupMode(pid).first()) {
            tv.own.owntv.features.settings.data.StartupMode.LAST_CHANNEL -> {
                val ch = liveVm.lastWatchedLiveChannel()
                if (ch != null && playerMode == PlayerMode.NONE) {
                    zapSource = MainSection.LIVE_TV
                    liveVm.watchFullscreen(ch, listOf(ch))
                    playerMode = PlayerMode.FULLSCREEN
                }
            }
            // Open straight to Live TV on the Favorites folder, with focus landing inside the channel list
            // (restoreFocus drives LiveScreen to focus the first/last channel, not the nav panel).
            tv.own.owntv.features.settings.data.StartupMode.FAVORITES -> {
                onSelectSection(MainSection.LIVE_TV)
                liveVm.select(tv.own.owntv.features.live.LiveKey.Favorites)
                restoreFocus = true
            }
            tv.own.owntv.features.settings.data.StartupMode.SPECIFIC_CHANNEL -> {
                val ref = resumeSettings.startupChannel(pid).first()
                var launch: LauncherLaunch? = null
                if (ref != null) {
                    if (!ref.remoteId.isNullOrBlank()) {
                        launch = launcherIntegrationRepository.resolveLaunch(
                            pid,
                            LauncherDeepLink.Live(sourceId = ref.sourceId, remoteId = ref.remoteId),
                        )
                    }
                    if (launch == null) {
                        launch = launcherIntegrationRepository.resolveLaunch(
                            pid,
                            LauncherDeepLink.Live(sourceId = ref.sourceId, name = ref.name),
                        )
                    }
                    if (launch == null && ref.itemId > 0L) {
                        launch = launcherIntegrationRepository.resolveLaunch(
                            pid,
                            LauncherDeepLink.Live(sourceId = ref.sourceId, itemId = ref.itemId),
                        )
                    }
                }
                val channel = (launch as? LauncherLaunch.Live)?.channel
                if (channel != null && liveVm.isVisibleToActiveProfile(channel) && playerMode == PlayerMode.NONE) {
                    zapSource = MainSection.LIVE_TV
                    liveVm.watchFullscreen(channel, listOf(channel))
                    playerMode = PlayerMode.FULLSCREEN
                } else {
                    onSelectSection(MainSection.HOME)
                    localSubToast.show(startupChannelUnavailable)
                }
            }
            tv.own.owntv.features.settings.data.StartupMode.HOME -> Unit
        }
    }

    // Movies/Series/Live load on first open via their reactive Paging flows — their indexed first page is
    // cheap, so they need NO preloading (a Live-TV-only user pays nothing for them). The TV Guide is the ONE
    // exception: load() pulls every guide channel + a programme window, which is heavy enough that doing it on
    // open felt slow. So warm EPG in the background shortly after the shell renders — opening the Guide is then
    // instant, matching how it behaved before. (EpgScreen also calls load() on mount, so this is a pure pre-warm
    // and is skipped if the user is already on EPG.)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_200)
        if (selectedSection != MainSection.EPG) { tv.own.owntv.Perf.stamp("epg-preload"); epgVm.load() }
    }

    // Opening content from a browse screen goes fullscreen — UNLESS the player is already docked as a
    // mini-player, in which case it stays docked and just swaps to the newly-selected stream (the VM
    // already started it), so picking a channel updates the PiP window in place (#6).
    fun openFullscreen(source: MainSection = selectedSection) {
        restoreFocus = false
        zapSource = source
        homeVm.stopPreview()
        // Only Live TV promotes a channel to the ExoPlayer engine. Movies/Series/Search/EPG/Downloads all
        // play on mpv — clear any stale live-on-ExoPlayer flag so the shell renders mpv, not the old channel.
        if (source != MainSection.LIVE_TV) liveVm.clearLiveOnExo()
        // Only Movies/Series/Downloads carry an external-subtitle item context (set by their play
        // paths). Anything else (Live/EPG/Search-channel) clears it so ADD SUBTITLES never shows stale.
        if (source != MainSection.MOVIES && source != MainSection.SERIES && source != MainSection.DOWNLOADS) {
            subtitleController.clear()
        }
        // A new stream is opening — make sure any Audio Mode video-off state is cleared, else mpv keeps
        // `vid=no` and the new item would play with no picture.
        player.exitAudioOnly(); runCatching { liveVm.previewEngine.exitAudioOnly() }
        if (playerMode != PlayerMode.MINI) playerMode = PlayerMode.FULLSCREEN
    }
    // Restore video output on both engines (no-op unless we were in Audio Mode). mpv `vid=auto` /
    // ExoPlayer surface is re-attached by the surface remount right after.
    val resumeVideo = {
        player.exitAudioOnly()
        runCatching { liveVm.previewEngine.exitAudioOnly() }
    }
    // The mini-player's own expand button always maximizes.
    val expandPlayer = { resumeVideo(); restoreFocus = false; playerMode = PlayerMode.FULLSCREEN }
    val exitPlayer = {
        // Flush the resume position BEFORE the stream is torn down — stop() drops the loaded item's
        // identity, after which neither view model can tell the position was theirs. Both calls are
        // no-ops unless the player is on that section's item.
        movieVm.saveProgressNow()
        seriesVm.saveEpisodeProgressNow()
        resumeVideo() // restore mpv `vid=auto` before stop so the next played item isn't left video-less
        playerMode = PlayerMode.NONE
        showChannelList = false
        showHistoryList = false
        liveVm.hideCategoryBrowser()
        liveVm.onFullscreenExited() // no longer full-screen on ExoPlayer → let the preview re-take the engine
        player.stop()
        subtitleController.clear() // leaving the player drops the OpenSubtitles item context
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
    // Switch the current stream to audio-only and surface the now-playing bar in the top bar. Stop the
    // video decoder FIRST (plan §5 ordering rule), then drop the video surface by leaving FULLSCREEN/MINI.
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

    // Stop a leftover live preview when you leave the Live section (but never while fullscreen/mini plays).
    LaunchedEffect(selectedSection, playerMode) {
        if (selectedSection != MainSection.LIVE_TV && playerMode == PlayerMode.NONE) player.stop()
        if (selectedSection != MainSection.HOME || playerMode != PlayerMode.NONE) homeVm.stopPreview()
    }

    LaunchedEffect(selectedSection, playerMode, activeProfileId, activePlaylistId) {
        if (selectedSection == MainSection.HOME && playerMode == PlayerMode.NONE && (activeProfileId?.let { it >= 0 } == true)) {
            homeVm.refresh()
        }
    }

    BackHandler {
        when {
            playerMode == PlayerMode.FULLSCREEN -> exitPlayer()
            showAvatarPicker -> showAvatarPicker = false
            showPlaylistPicker -> showPlaylistPicker = false
            showExit -> showExit = false
            focusedLayer == ShellLayer.SIDEBAR -> showExit = true
            else -> runCatching { sidebarFocus.requestFocus() }
        }
    }

    // Glass effect: when a background image is active, the shell's own base paints must be transparent
    // so the full-bleed image (rendered in MainActivity behind this shell) shows through the gaps
    // between/around panels. Solid otherwise — the usual near-black base.
    val glass = LocalGlass.current
    val shellBase = if (glass.isGlassy(GlassSurface.PANELS) || glass.isGlassy(GlassSurface.SIDEBAR)) Color.Transparent else colors.background
    // Keep the navigation plate aligned with the content below the same dynamic top bar: compact during
    // normal browsing, and restored to the taller reservation while Audio Mode shows its player controls.
    val shellTopBarHeight = if (playerMode == PlayerMode.AUDIO) Dimens.TopBarHeight else Dimens.TopBarCompactHeight

    CompositionLocalProvider(LocalContentScrolled provides contentScrolled) {
    Box(modifier = modifier.fillMaxSize().background(shellBase)) {
      // Browse UI — hidden while the player is fullscreen (stays visible behind the docked mini-player).
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    // Phase 6 — unified panel surface: panels and content area share #102520 so the
                    // rounded borders define regions on one continuous dark-green surface.
                    // Glass effect: transparent here (shellBase) when a background image is active, so
                    // the image shows through the gaps between the content panels.
                    .background(shellBase),
            ) {
                // Phase 5 — top bar above the content (active section + Search pill + clock + playlist).
                // Shown on EVERY section now, including Settings ("top bar same for all").
                TopBar(
                    sectionLabel = stringResource(selectedSection.labelRes),
                    onSearchClick = {
                        searchVm.setQuery("")
                        trendingSearchActive = false
                        restoreTrendingSearchFocus = false
                        onSelectSection(MainSection.SEARCH)
                    },
                    // The chip reflects the active filter: "All playlists" when none is chosen (id <= 0),
                    // the chosen playlist's name otherwise. With a single playlist there's nothing to switch,
                    // so just show its name.
                    playlistName = when {
                        playlists.size <= 1 -> sourceSummary ?: noSourceLabel
                        activePlaylistId <= 0L -> stringResource(R.string.content_all_playlists)
                        else -> playlists.firstOrNull { it.id == activePlaylistId }?.name ?: (sourceSummary ?: noSourceLabel)
                    },
                    weatherInfo = weatherInfo,
                    weatherFahrenheit = weatherFahrenheit,
                    // The Search pill only exists while focus sits on the nav panel — inside a
                    // section it fades out and turns unfocusable, so focus can never jump to it.
                    searchVisible = focusedLayer == ShellLayer.SIDEBAR,
                    // The playlist chip becomes a quick-switcher only when there's more than one to pick.
                    playlistInteractive = playlists.size > 1,
                    onPlaylistClick = { showPlaylistPicker = true },
                    playlistDownFocusRequester = homeFirstRowFocus.takeIf {
                        selectedSection == MainSection.HOME
                    },
                    // Batch 7 — shared "Continue" chip: one-press resume of the most-recent item.
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
                    // Audio Mode: the now-playing bar, left of the weather chip. Present only while
                    // PlayerMode.AUDIO; focusable only while the nav panel holds focus (same rule as Search).
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
                                // Always reachable while Audio Mode is active (from the Search/Continue
                                // pills on the left or the playlist chip on the right) — not gated on the
                                // nav panel like the other chips, because its own D-pad trap keeps focus
                                // inside once entered and Back is the only way out.
                                focusable = true,
                            )
                        }
                    } else null,
                    leadingExtension = Dimens.SidebarWidthCollapsed,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 0.dp, end = 6.dp, bottom = 6.dp)) {
                    when {
                        selectedSection == MainSection.SETTINGS -> SettingsScreen(
                            themeMode = themeMode,
                            uiZoomPercent = uiZoomPercent,
                            onSetZoom = onSetZoom,
                            fontCustomization = fontCustomization,
                            onSetFontCustomization = onSetFontCustomization,
                            onOpenPlaylist = { /* Phase 6: open setup/playlist */ },
                            openEpgAdd = openEpgAdd,
                            onEpgAddConsumed = { openEpgAdd = false },
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }
                                .focusGroup(),
                        )

                        selectedSection == MainSection.HOME -> HomeScreen(
                            vm = homeVm,
                            // Skip the fullscreen player when the global external-player toggle is on
                            // (mounting it spins up mpv even though playback went to the external app).
                            onPlayMovie = { id, pos -> scope.launch { if (movieVm.playByIdAsync(id, pos) && !movieVm.externalPlayerOn.value) openFullscreen(MainSection.MOVIES) } },
                            onPlayEpisode = { seriesId, epId, pos -> scope.launch { if (seriesVm.playFromHomeAsync(seriesId, epId, pos) && !seriesVm.externalPlayerOn.value) openFullscreen(MainSection.SERIES) } },
                            onPlayChannel = { id, zap -> scope.launch { if (liveVm.ensurePlayingByIdAsync(id, zap)) openFullscreen(MainSection.LIVE_TV) } },
                            onOpenGuide = { onSelectSection(MainSection.EPG) },
                            onActivateTrending = { selected, onUnavailable ->
                                scope.launch {
                                    when (val current = homeVm.revalidateTrendingItem(selected)) {
                                        is TrendingHomeItem.Movie -> {
                                            val played = movieVm.playByIdAsync(current.movie.id)
                                            if (!played) onUnavailable()
                                            else if (!movieVm.externalPlayerOn.value) openFullscreen(MainSection.MOVIES)
                                        }
                                        is TrendingHomeItem.Series -> {
                                            seriesVm.openSeries(current.series)
                                            restoreFocus = true
                                            onSelectSection(MainSection.SERIES)
                                        }
                                        null -> onUnavailable()
                                    }
                                }
                            },
                            onOpenTrendingSearch = { query ->
                                searchVm.setQuery(query)
                                trendingSearchActive = true
                                restoreTrendingSearchFocus = false
                                onSelectSection(MainSection.SEARCH)
                            },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus,
                            restoreTrendingSearchFocus = restoreTrendingSearchFocus,
                            onRestored = {
                                restoreFocus = false
                                restoreTrendingSearchFocus = false
                            },
                            previewEnabled = playerMode == PlayerMode.NONE,
                            firstRowFocusRequester = homeFirstRowFocus,
                            onContentScrolled = { contentScrolled = it },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.SEARCH -> SearchScreen(
                            vm = searchVm,
                            onFullscreen = { openFullscreen() },
                            // Open the actual series (its episode list), then switch to the Series section —
                            // the screen shares this SeriesViewModel, so it shows the opened show.
                            onOpenSeries = { series ->
                                trendingSearchActive = false
                                seriesVm.openSeries(series)
                                onSelectSection(MainSection.SERIES)
                            },
                            // A channel found in Search tunes through the same LiveViewModel path as one
                            // opened from Live TV or the Guide (F05) — Prefer HLS, the ExoPlayer→mpv
                            // ladder, compatibility-mode pins, the external-player toggle, and CH+/- zap.
                            onPlayChannel = { ch ->
                                restoreFocus = false
                                liveVm.watchFromGuide(ch)
                                zapSource = MainSection.LIVE_TV
                                homeVm.stopPreview()
                                if (playerMode != PlayerMode.MINI && !liveVm.externalPlayerOn.value) playerMode = PlayerMode.FULLSCREEN
                            },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            returnToHomeOnBack = trendingSearchActive,
                            onReturnToHome = {
                                trendingSearchActive = false
                                restoreTrendingSearchFocus = true
                                restoreFocus = false
                                onSelectSection(MainSection.HOME)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.LIVE_TV -> LiveScreen(
                            onFullscreen = { openFullscreen() },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            previewEnabled = playerMode == PlayerMode.NONE,
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            onContentScrolled = { contentScrolled = it },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.MOVIES -> MoviesScreen(
                            onFullscreen = { openFullscreen() },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            onContentScrolled = { contentScrolled = it },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.SERIES -> SeriesScreen(
                            onFullscreen = { openFullscreen() },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.DOWNLOADS -> DownloadsScreen(
                            onFullscreen = { openFullscreen() },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.EPG -> EpgScreen(
                            onBack = { runCatching { sidebarFocus.requestFocus() } },
                            onFullscreen = { openFullscreen() },
                            onPlayChannel = { ch, _ ->
                                restoreFocus = false
                                liveVm.watchFromGuide(ch)
                                zapSource = MainSection.LIVE_TV
                                homeVm.stopPreview()
                                // Live TV set to play externally → the channel went to another app;
                                // don't mount the fullscreen player over it.
                                if (playerMode != PlayerMode.MINI && !liveVm.externalPlayerOn.value) playerMode = PlayerMode.FULLSCREEN
                            },
                            onPlayCatchup = { ch, prog ->
                                restoreFocus = false
                                liveVm.playCatchupProgramme(ch, prog)
                                zapSource = MainSection.LIVE_TV
                                homeVm.stopPreview()
                                if (playerMode != PlayerMode.MINI) playerMode = PlayerMode.FULLSCREEN
                            },
                            onAddEpg = { openEpgAdd = true; onSelectSection(MainSection.SETTINGS) },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            onContentScrolled = { contentScrolled = it },
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }
                                .focusGroup(),
                        )

                        else -> Row(modifier = Modifier.fillMaxSize()) {
                            CategoryRail(
                                categories = categories,
                                selectedIndex = selectedRail,
                                onSelect = { railSelection[selectedSection] = it },
                                onFocused = { focusedLayer = ShellLayer.RAIL },
                            )

                            ContentPane(
                                sectionTitle = stringResource(selectedSection.labelRes),
                                categoryName = categories.getOrNull(selectedRail)?.let { category -> category.labelRes?.let { stringResource(it) } ?: category.fullName }
                                    ?: stringResource(R.string.content_category_all_channels),
                                countLabel = placeholderCount(selectedSection),
                                emptyIcon = selectedSection.emptyIcon,
                                emptyMessage = stringResource(R.string.content_empty_section, stringResource(selectedSection.labelRes)),
                                onAddSource = { onSelectSection(MainSection.SETTINGS) },
                                modifier = Modifier
                                    .weight(1.4f)
                                    .onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }
                                    .focusGroup(),
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .padding(Dimens.GapLarge),
                            ) {
                                PreviewPane(hint = stringResource(R.string.content_preview_select_channel))
                            }
                        }
                    }
                }
            }
          }
        }
        // The solid-mode wizard aura is deliberately drawn after the opaque browse surfaces so it
        // remains visible, exactly like the approved concept. It never intercepts input; fullscreen
        // video and glass mode skip it entirely. Global Animations Off also freezes the slow pulse.
        SolidAmbientBackdrop(
            glowEnabled = ambientGlowEnabled,
            pulseEnabled = ambientGlowPulse && shellAnimationLevel != tv.own.owntv.ui.theme.AnimationLevel.OFF,
            modifier = Modifier.fillMaxSize(),
        )
      }

      // Unobtrusive background-sync pill (bottom middle): visible while any catalog sync runs —
      // backgrounded first import, remainder worker, auto refresh — but never over fullscreen video.
      if (playerMode != PlayerMode.FULLSCREEN) {
          tv.own.owntv.features.shell.components.SyncStatusPill(modifier = Modifier.align(Alignment.BottomCenter))
      }

      // Player surface — hoisted so it persists across fullscreen <-> mini (same call site = the
      // SurfaceView isn't recreated when docking/expanding, so playback never blips). NOT composed in
      // AUDIO mode: there's no video surface — audio plays and the top-bar now-playing bar drives it.
      if (playerMode == PlayerMode.FULLSCREEN || playerMode == PlayerMode.MINI) {
        val isFull = playerMode == PlayerMode.FULLSCREEN
        Box(
            modifier = if (isFull) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                // Dynamic docked size/position: a screen-width fraction at the chosen corner/edge, so it
                // scales with the panel + UI zoom (unlike the old fixed 340×191 dp box).
                Modifier.align(miniPos.alignment).padding(24.dp)
                    .fillMaxWidth(tv.own.owntv.player.MiniPlayerSize.fraction(miniSizePct)).aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp)).background(Color.Black)
            },
        ) {
            // "Promote Preview": a Live channel playing on ExoPlayer renders the ExoPlayer surface — in BOTH
            // full-screen AND the docked mini-player (same call site = the surface persists across dock/
            // expand, so playback never blips). Everything else (mpv) renders mpv's surface.
            if (liveOnExo) {
                tv.own.owntv.player.ExoPreviewSurface(
                    engine = liveVm.previewEngine, modifier = Modifier.fillMaxSize(),
                    keepAwake = true, autoFrameRate = isFull && autoFrameRate,
                )
            } else {
                MpvVideoSurface(player = player, modifier = Modifier.fillMaxSize(), autoFrameRate = isFull && autoFrameRate)
            }
            // The item has no video track of its own (a radio channel, a music-only "movie"). Playing it is
            // correct — but a black screen with sound reads as a broken player, so name what is happening.
            // Read from whichever engine is on screen; only ever composed when there is no video to lose.
            val audioOnlyMedia by if (liveOnExo) {
                liveVm.previewEngine.audioOnlyMedia.collectAsStateWithLifecycle()
            } else {
                player.audioOnlyMedia.collectAsStateWithLifecycle()
            }
            if (audioOnlyMedia) {
                tv.own.owntv.player.AudioOnlyBadge(modifier = Modifier.fillMaxSize(), compact = !isFull)
            }
            // Direct render mode: mpv can't draw subtitles on the decoder-owned surface — the app does.
            // Also drawn docked (F19b): the mini-player is a real watching mode for a subtitled film, and
            // dropping the only line of dialogue there made subtitles look broken. Scaled to the box.
            if (!liveOnExo) {
                tv.own.owntv.player.SubtitleOverlay(
                    player = player, modifier = Modifier.fillMaxSize(),
                    // Tied to the chosen mini size, but nudged up and floored: a strictly proportional
                    // line would be unreadable in the smallest box.
                    sizeScale = if (isFull) 1f else {
                        (tv.own.owntv.player.MiniPlayerSize.fraction(miniSizePct) * 1.5f).coerceIn(0.35f, 0.7f)
                    },
                )
            }
            if (isFull && !autoFrameRate && !afrPrompted) {
                // Frame rate of whichever engine is on screen. On the mpv side this is what the direct
                // path judders on; on Exo it now survives "Measured stream stats" being off (F14).
                val activeFps by if (liveOnExo) {
                    liveVm.previewEngine.videoFps.collectAsStateWithLifecycle()
                } else {
                    player.videoFps.collectAsStateWithLifecycle()
                }
                tv.own.owntv.player.AutoFrameRatePrompt(
                    fps = activeFps,
                    afrEnabled = autoFrameRate,
                    alreadyPrompted = afrPrompted,
                    // Mark it answered on BOTH paths. Enabling only set the setting, so a user who later
                    // turned Auto frame rate back off was offered the "once ever" suggestion all over again.
                    onEnable = {
                        scope.launch {
                            settingsRepo.setAutoFrameRate(true)
                            settingsRepo.setAutoFrameRatePrompted()
                        }
                    },
                    onDismiss = { scope.launch { settingsRepo.setAutoFrameRatePrompted() } },
                )
            }
            if (isFull) {
                // CH+/CH- zap through the channel list of whichever section opened the current stream
                // (Live TV or the Guide); never for VOD. When live plays on ExoPlayer (liveOnExo=true) the
                // mpv `player` is stopped so player.isLiveContent is false — the ExoPlayer engine is the one
                // playing live, so we must check liveOnExo too (otherwise zap breaks for the common case).
                val isLiveStream = liveOnExo || player.isLiveContent
                val zap: ((Int) -> Unit)? = when {
                    !isLiveStream -> null
                    zapSource == MainSection.LIVE_TV && liveCanZap -> liveVm::zap
                    else -> null
                }
                // Live rewind controls apply to a Live-TV channel (live OR its timeshift archive).
                val isLiveChannel = zapSource == MainSection.LIVE_TV
                // ...but NOT to a catch-up archive programme. That's VOD-style playback of a past
                // programme, so it gets the VOD engine toggle (reloads the same archive URL at the same
                // position on the other engine) rather than Live TV's compatibility toggle, which would
                // re-tune the live stream and jump the user to the current programme.
                val isTunedLive = isLiveChannel && !catchupActive
                // Favorite toggle for whatever is playing: the live channel, the movie, or the series
                // (episodes favorite their parent series). Picked by the section that armed the stream.
                val favToggle: (() -> Unit)? = when {
                    isLiveChannel -> previewChannel?.let { ch -> { liveVm.toggleFavorite(ch) } }
                    zapSource == MainSection.MOVIES -> playingMovie?.let { m -> { movieVm.toggleFavorite(m) } }
                    zapSource == MainSection.SERIES -> playingSeries?.let { s -> { seriesVm.toggleFavorite(s) } }
                    else -> null
                }
                val favActive = when {
                    isLiveChannel -> previewChannel?.let { liveFavoriteIds.contains(it.id) } ?: false
                    zapSource == MainSection.MOVIES -> playingMovie?.let { movieFavoriteIds.contains(it.id) } ?: false
                    zapSource == MainSection.SERIES -> playingSeries?.let { seriesFavoriteIds.contains(it.id) } ?: false
                    else -> false
                }
                PlayerHud(
                    player = if (liveOnExo) liveVm.previewEngine else mpvEngine, // HUD drives the active engine
                    onBack = exitPlayer,
                    onPip = dockPlayer, // PiP/dock works for live on either engine now
                    onAudioMode = toAudioMode,
                    // The channel-list overlay draws ABOVE the HUD; while it's open the HUD goes inert so
                    // its hide/error focus grabs can't yank D-pad focus off the overlay.
                    inert = showChannelList || showHistoryList || showCategoryBrowser || showSubtitleSearch || showLocalSubPicker,
                    onChannelUp = zap?.let { z -> { z(-1) } },
                    onChannelDown = zap?.let { z -> { z(1) } },
                    onOpenChannelList = if (isTunedLive && liveCanZap) { { showChannelList = true } } else null,
                    onOpenHistoryList = if (isTunedLive) { { showHistoryList = true } } else null,
                    onRewindLive = if (isTunedLive && canRewindLive) liveVm::rewindLive else null,
                    onForwardLive = if (isTunedLive) liveVm::forwardLive else null,
                    onGoToLive = if (isTunedLive) liveVm::goToLive else null,
                    onScrubLive = if (isTunedLive && canRewindLive) liveVm::scrubLive else null,
                    jumpBackOptions = if (isTunedLive && canRewindLive) liveVm::currentJumpOptions else null,
                    onJumpBack = if (isTunedLive && canRewindLive) liveVm::jumpBackTo else null,
                    jumpBackWindowSec = if (isTunedLive && canRewindLive) liveVm::currentCatchupWindowSec else null,
                    // Non-null only while an archive is on screen, so movies, episodes and live TV get
                    // the single real clock and catch-up gets the pair.
                    watchingWallMs = liveVm.watchingWallMs.collectAsStateWithLifecycle().value,
                    timeshiftOffsetSec = if (isTunedLive) timeshiftOffset else null,
                    onTuneToNumber = if (directTuneEnabled && isTunedLive && isLiveStream && timeshiftOffset == null && previewChannel != null) liveVm::tuneByNumber else null,
                    directTuneContextKey = previewChannel?.id ?: 0L,
                    // Show the ACTUAL running engine (mpv when pinned OR auto-fallen-back), not just the pin —
                    // otherwise an auto-fallback to mpv still read "EXO". true = on mpv (pill shows MPV, teal).
                    compatMode = if (isTunedLive) !liveOnExo else null,
                    // Hidden while rewound into the archive (same `timeshiftOffset == null` rule direct
                    // tune follows above): switching engine restarts the channel at the live edge, which
                    // threw the user out of the rewind with the HUD still counting "behind live".
                    // Also hidden for a protected channel (#115): only ExoPlayer can license it, so the
                    // toggle's other position is not a compatibility choice but a guaranteed failure.
                    onToggleCompatMode = if (isTunedLive && timeshiftOffset == null && previewChannel?.drmConfig == null) liveVm::toggleForceMpv else null,
                    // VOD engine toggle (movies/series only — live and catch-up channels keep their own
                    // engine handling above): flip the current item between mpv and ExoPlayer.
                    vodOnExo = if (!isLiveStream && !isTunedLive) vodExoActive else null,
                    onToggleVodEngine = if (!isLiveStream && !isTunedLive) player::toggleVodEngine else null,
                    // ADD SUBTITLES entry: movies/episodes only, and only when the play path set an
                    // item context (subtitle plan §4). Opens the OpenSubtitles search overlay below.
                    onSearchSubtitles = if (!isLiveStream && !isLiveChannel && subtitleContext != null) {
                        { showSubtitleSearch = true }
                    } else null,
                    // Local subtitle file (plan §7): same movie/episode gating, no account needed.
                    onSelectLocalSubtitle = if (!isLiveStream && !isLiveChannel && subtitleContext != null) {
                        { showLocalSubPicker = true }
                    } else null,
                    // In-stream favorite toggle for the current channel/movie/series.
                    favorite = favActive,
                    onToggleFavorite = favToggle,
                    // Guide card for the playing channel (nowNext follows previewChannel = what's playing).
                    liveEpgCard = if (isLiveChannel) {
                        {
                            val epg by liveVm.nowNext.collectAsStateWithLifecycle()
                            val archiveEpg by liveVm.archiveNowNext.collectAsStateWithLifecycle()
                            val watching by liveVm.watchingWallMs.collectAsStateWithLifecycle()
                            // Stacked: what was on air at the replayed moment, then what is on air now.
                            // The live row is dimmed while an archive plays — it is context, not the
                            // thing being watched — and returns to full strength back at the live edge.
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.End,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
                            ) {
                                if (watching != null) {
                                    tv.own.owntv.features.shell.components.LiveEpgCard(
                                        epg = archiveEpg,
                                        variant = tv.own.owntv.features.shell.components.EpgCardVariant.ARCHIVE,
                                        atMs = watching,
                                    )
                                }
                                tv.own.owntv.features.shell.components.LiveEpgCard(
                                    epg = epg,
                                    modifier = if (watching == null) Modifier else Modifier.alpha(0.55f),
                                )
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxSize(),
                )
                // OpenSubtitles search overlay (movies/episodes) — drawn above the HUD; the HUD is inert
                // while it's open so the D-pad stays on the overlay.
                if (showSubtitleSearch) {
                    tv.own.owntv.features.subtitles.SubtitleSearchScreen(
                        onDismiss = { showSubtitleSearch = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Local subtitle-file picker (plan §7.3) — hosted in a Dialog window, so D-pad focus
                // can't fall through to the HUD behind; picking imports a managed UTF-8 copy and
                // attaches it live to whichever engine is playing.
                if (showLocalSubPicker) {
                    tv.own.owntv.ui.components.StorageBrowser(
                        title = stringResource(R.string.content_subtitle_select_file),
                        mode = tv.own.owntv.ui.components.BrowseMode.FILE,
                        fileExtensions = setOf("srt", "ass", "ssa", "vtt", "webvtt"),
                        onPick = { file ->
                            showLocalSubPicker = false
                            scope.launch {
                                runCatching { subtitleController.applyLocal(file) }
                                    .onFailure { e ->
                                        localSubToast.show(e.message ?: subtitleLoadFailed)
                                    }
                            }
                        },
                        onDismiss = { showLocalSubPicker = false },
                    )
                }
                tv.own.owntv.ui.components.InAppToast(localSubToast)
                // Left — the playing channel's own provider category.
                if (showChannelList && isLiveChannel) {
                    if (showCategoryBrowser) {
                        // Second Left — every Live TV category.
                        tv.own.owntv.features.shell.components.CategoryBrowserOverlay(
                            categories = browserCategories,
                            currentCategoryId = previewChannel?.categoryId,
                            onSelect = { catId -> liveVm.loadChannelsForCategory(catId) },
                            onDismiss = { liveVm.hideCategoryBrowser() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (zapChannels.isNotEmpty()) {
                        // First Left — the channels of the current category. A browsed-to category may
                        // hold a single channel, so this renders for any non-empty list.
                        tv.own.owntv.features.shell.components.ChannelListOverlay(
                            channels = zapChannels,
                            currentId = previewChannel?.id,
                            nowPlaying = overlayNowPlaying,
                            title = zapOverlayTitle,
                            showNumbers = directTuneEnabled,
                            onSelect = { liveVm.ensurePlaying(it); showChannelList = false },
                            onDismiss = { showChannelList = false },
                            onOpenCategories = { liveVm.showCategories() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // Right — recently watched, to hop straight back to the previous channel.
                if (showHistoryList && isLiveChannel && historyChannels.isNotEmpty()) {
                    tv.own.owntv.features.shell.components.ChannelListOverlay(
                        channels = historyChannels,
                        currentId = previewChannel?.id,
                        nowPlaying = historyNowPlaying,
                        title = stringResource(R.string.content_history),
                        showNumbers = directTuneEnabled,
                        alignEnd = true,
                        onSelect = { liveVm.ensurePlaying(it); showHistoryList = false },
                        onDismiss = { showHistoryList = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                MiniPlayer(
                    player = if (liveOnExo) liveVm.previewEngine else mpvEngine,
                    onExpand = expandPlayer,
                    onClose = exitPlayer,
                    onCycleSize = { scope.launch { settingsRepo.setMiniPlayerSizePct(tv.own.owntv.player.MiniPlayerSize.next(miniSizePct)) } },
                    onCyclePosition = { scope.launch { settingsRepo.setMiniPlayerPosition(miniPos.next().name) } },
                    onAudioMode = toAudioMode,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
      }

        if (showExit) {
            ExitDialog(onConfirm = onExitApp, onDismiss = { showExit = false })
        }
        if (showAvatarPicker) {
            AvatarPickerDialog(
                selectedId = avatarId,
                onSelect = onSetAvatar,
                onDismiss = { showAvatarPicker = false },
            )
        }
        if (showPlaylistPicker) {
            PlaylistPickerDialog(
                playlists = playlists,
                activeId = activePlaylistId,
                onSelect = onSelectPlaylist,
                onDismiss = { showPlaylistPicker = false },
            )
        }

        // Automatic update check (GitHub Releases) shortly after launch, once per session: a small
        // top-right status card shows "Checking… / up to date" (auto-hides) or stays with
        // Update now / Later when a release is newer. Hidden while in Settings (its manual
        // "Check for updates" dialog drives the same state machine) and during playback.
        // Interrupted restore (B2): the marker outlives the process, so if it's still set at launch
        // the last restore didn't complete. Acknowledging clears it.
        val restoreSettings = koinInject<tv.own.owntv.features.settings.data.SettingsRepository>()
        val incompleteRestore by restoreSettings.restoreInProgress.collectAsStateWithLifecycle(initialValue = null)
        var restoreNoticeDismissed by remember { mutableStateOf(false) }
        incompleteRestore?.takeIf { !restoreNoticeDismissed }?.let { description ->
            IncompleteRestoreDialog(
                description = description,
                onDismiss = {
                    restoreNoticeDismissed = true
                    scope.launch { restoreSettings.clearRestoreMarker() }
                },
            )
        }

        val updateManager = koinInject<UpdateManager>()
        val updateState by updateManager.state.collectAsStateWithLifecycle()
        var showStartupToast by remember { mutableStateOf(false) }
        var showChangelog by remember { mutableStateOf(false) }
        val settingsRepo = koinInject<tv.own.owntv.features.settings.data.SettingsRepository>()
        val updateCheckOnStart by settingsRepo.updateCheckOnStart.collectAsStateWithLifecycle(initialValue = false)
        LaunchedEffect(updateCheckOnStart) {
            if (updateCheckOnStart && !showStartupToast) {
                kotlinx.coroutines.delay(5_000)
                showStartupToast = true
                updateManager.check()
            }
        }
        LaunchedEffect(updateState) {
            if (showStartupToast && updateState is UpdateManager.State.Available) {
                showStartupToast = false
                showChangelog = true
            }
        }
        if (showChangelog) {
            // Full "What's New" changelog (same dialog the manual Settings check uses), shown when
            // the startup card's "What's New" is pressed. No re-check — the release is already loaded.
            UpdateDialog(onDismiss = { showChangelog = false; showStartupToast = false; updateManager.reset() }, checkOnOpen = false)
        } else if (showStartupToast && selectedSection != MainSection.SETTINGS && playerMode == PlayerMode.NONE) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                UpdateStatusToast(
                    onDone = { showStartupToast = false; updateManager.reset() },
                    onViewChangelog = { showChangelog = true },
                )
            }
        }
    }
    }
}

/** A thin bar shown above the browse UI when the device loses internet. */
@Composable
private fun OfflineBanner() {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tertiaryContainer)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.content_offline_banner),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onTertiaryContainer,
        )
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
        MainSection.SETTINGS -> OwnTVIcon.SETTINGS
    }

private fun railCategoriesFor(section: MainSection): List<RailCategory> = when (section) {
    MainSection.SEARCH -> emptyList()
    MainSection.HOME -> emptyList()
    MainSection.EPG -> emptyList()
    MainSection.LIVE_TV -> listOf(
        RailCategory("Favorites", OwnTVIcon.FAVORITE, tv.own.owntv.R.string.content_category_favorites),
        RailCategory("History", OwnTVIcon.HISTORY, tv.own.owntv.R.string.content_category_history),
        RailCategory("All Channels", labelRes = tv.own.owntv.R.string.content_category_all_channels, showGenreDot = false),
        RailCategory("United Kingdom", labelRes = tv.own.owntv.R.string.content_category_united_kingdom),
        RailCategory("United States", labelRes = tv.own.owntv.R.string.content_category_united_states),
        RailCategory("Germany", labelRes = tv.own.owntv.R.string.content_category_germany),
        RailCategory("Sports", labelRes = tv.own.owntv.R.string.content_category_sports),
    )
    MainSection.MOVIES -> listOf(
        RailCategory("Favorites", OwnTVIcon.FAVORITE, tv.own.owntv.R.string.content_category_favorites),
        RailCategory("History", OwnTVIcon.HISTORY, tv.own.owntv.R.string.content_category_history),
        RailCategory("All Movies", labelRes = tv.own.owntv.R.string.content_category_all_movies, showGenreDot = false),
        RailCategory("Action", labelRes = tv.own.owntv.R.string.content_category_action),
        RailCategory("Drama", labelRes = tv.own.owntv.R.string.content_category_drama),
        RailCategory("Comedy", labelRes = tv.own.owntv.R.string.content_category_comedy),
        RailCategory("Horror", labelRes = tv.own.owntv.R.string.content_category_horror),
    )
    MainSection.SERIES -> listOf(
        RailCategory("Favorites", OwnTVIcon.FAVORITE, tv.own.owntv.R.string.content_category_favorites),
        RailCategory("History", OwnTVIcon.HISTORY, tv.own.owntv.R.string.content_category_history),
        RailCategory("All Series", labelRes = tv.own.owntv.R.string.content_category_all_series, showGenreDot = false),
        RailCategory("Drama", labelRes = tv.own.owntv.R.string.content_category_drama),
        RailCategory("Action", labelRes = tv.own.owntv.R.string.content_category_action),
        RailCategory("Animation", labelRes = tv.own.owntv.R.string.content_category_animation),
        RailCategory("Documentary", labelRes = tv.own.owntv.R.string.content_category_documentary),
    )
    MainSection.DOWNLOADS -> listOf(
        RailCategory("All Downloads", labelRes = tv.own.owntv.R.string.content_category_all_downloads, showGenreDot = false),
        RailCategory("Movies", labelRes = tv.own.owntv.R.string.content_category_movies),
        RailCategory("Series", labelRes = tv.own.owntv.R.string.content_category_series),
    )
    MainSection.SETTINGS -> emptyList()
}

@Composable
private fun placeholderCount(section: MainSection): String = when (section) {
    MainSection.SEARCH, MainSection.HOME, MainSection.EPG, MainSection.SETTINGS -> ""
    MainSection.LIVE_TV -> stringResource(R.string.content_zero_channels)
    MainSection.MOVIES -> stringResource(R.string.content_zero_movies)
    MainSection.SERIES -> stringResource(R.string.content_zero_series)
    MainSection.DOWNLOADS -> stringResource(R.string.content_zero_downloads)
}
