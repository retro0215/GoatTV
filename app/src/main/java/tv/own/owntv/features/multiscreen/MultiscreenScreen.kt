package tv.own.owntv.features.multiscreen

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.res.stringResource
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import tv.own.owntv.R
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.theme.GlassSurface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.network.StreamingHttpClient
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.ui.theme.OwnTVTheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.player.ownTVRenderers
import tv.own.owntv.player.AudioOutputPolicy
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.database.entity.playStreamUrl
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.SearchBar
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.features.live.LiveKey
import tv.own.owntv.features.live.LiveRailItem
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorsFactory
import tv.own.owntv.ui.theme.Dimens
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.*

private const val UNKNOWN_ERR = "unknown"

@UnstableApi
private class MultiscreenExoEngine(
    private val player: ExoPlayer,
    private val channel: ChannelEntity
) : tv.own.owntv.player.PlaybackEngine {
    private val _isPlaying = MutableStateFlow(player.playWhenReady)
    override val isPlaying = _isPlaying.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    override val buffering = _buffering.asStateFlow()
    private val _currentMeta = MutableStateFlow(tv.own.owntv.player.MediaMeta(title = channel.name, logoUrl = channel.displayLogoUrl))
    override val currentMeta = _currentMeta.asStateFlow()
    override val isLiveContent = true
    override val engineChip = MutableStateFlow<String?>(null).asStateFlow()
    override val volume = MutableStateFlow(100).asStateFlow()
    override val zoomMode = MutableStateFlow(tv.own.owntv.player.ZoomMode.FIT).asStateFlow()

    override val error = MutableStateFlow<tv.own.owntv.player.PlaybackFailure?>(null).asStateFlow()
    override val errorInfo = MutableStateFlow<tv.own.owntv.player.ErrorInfo?>(null).asStateFlow()
    override val videoRes = MutableStateFlow<String?>(null).asStateFlow()
    override val streamChips = MutableStateFlow(emptyList<String>()).asStateFlow()
    override val audioCount = MutableStateFlow(0).asStateFlow()
    override val subCount = MutableStateFlow(0).asStateFlow()
    override val audioOnly = MutableStateFlow(false).asStateFlow()
    override val position = MutableStateFlow(0L).asStateFlow()
    override val duration = MutableStateFlow(0L).asStateFlow()
    override val speed = MutableStateFlow(1.0).asStateFlow()
    override val nav = MutableStateFlow(tv.own.owntv.player.NavState(false, false)).asStateFlow()
    override val nextUpTitle = MutableStateFlow<String?>(null).asStateFlow()
    override val audioDelayMs = MutableStateFlow(0).asStateFlow()
    override val subDelayMs = MutableStateFlow(0).asStateFlow()
    override val seekStepMs = MutableStateFlow(10000L).asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { _buffering.value = state == Player.STATE_BUFFERING }
        })
    }

    override fun togglePlayPause() { if (player.playWhenReady) player.pause() else player.play() }
    override fun retry() { player.prepare(); player.play() }
    override fun setZoomMode(mode: tv.own.owntv.player.ZoomMode) {}
    override fun adjustVolume(delta: Int) {}
    override fun setZoomModeByUser(mode: tv.own.owntv.player.ZoomMode) {}
    override fun adjustVolumeByUser(delta: Int) {}
    override fun toggleMute() { player.volume = if (player.volume > 0) 0f else 1f }
    override fun selectAudio(id: Int) {}
    override fun selectSubtitle(id: Int) {}
    override fun disableSubtitles() {}
    override fun addExternalSubtitle(path: String, title: String, lang: String?) {}
    override fun audioTracks() = emptyList<tv.own.owntv.player.TrackOption>()
    override fun textTracks() = emptyList<tv.own.owntv.player.TrackOption>()
    override suspend fun streamInfo() = emptyList<tv.own.owntv.player.StreamInfoRow>()
    override fun setBitrateTrackingEnabled(enabled: Boolean) {}
    override fun refreshStreamChips() {}
    override fun setSpeed(speed: Double) {}
    override fun adjustAudioDelay(deltaMs: Int) {}
    override fun adjustSubtitleDelay(deltaMs: Int) {}
    override fun resetSubtitleDelay() {}
    override fun previous() {}
    override fun next() {}
    override fun seekBy(deltaMs: Long) {}
    override fun cancelAutoNext() {}
    override fun enterAudioOnly() {}
    override fun exitAudioOnly() {}
}

enum class MultiscreenModal { NONE, ACTION_MENU, CHANNEL_PICKER, FULLSCREEN_HUD }

private class FocusRestoreGate {
    var lastModal: MultiscreenModal = MultiscreenModal.NONE
    var pending: Boolean = false
}

@UnstableApi
private fun buildMultiscreenLoadControl(): DefaultLoadControl {
    val isOnn = Build.MODEL.contains("onn.", ignoreCase = true)
    val minBufferMs = if (isOnn) 2_500 else 5_000
    val maxBufferMs = if (isOnn) 5_000 else 10_000
    val bufferForPlaybackMs = if (isOnn) 1_000 else 1_500
    val bufferForPlaybackAfterRebufferMs = if (isOnn) 1_500 else 2_000
    
    android.util.Log.d("MultiscreenDiag", "Building LoadControl: min=$minBufferMs, max=$maxBufferMs, constrained=$isOnn")
    
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}

@UnstableApi
private fun buildMultiscreenPlayer(
    context: Context,
    streamingHttp: StreamingHttpClient,
    ua: String,
    headers: Map<String, String>,
    surroundMode: tv.own.owntv.player.SurroundMode,
    hwDecoding: Boolean
): ExoPlayer {
    val renderers = ownTVRenderers(
        context,
        forceStereo = !AudioOutputPolicy.allowsMultichannel(surroundMode),
        softwareFirst = !hwDecoding,
    )
    val dataSourceFactory = OkHttpDataSource.Factory(streamingHttp.client)
        .setUserAgent(ua)
        .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })

    val cc1 = Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
        .setAccessibilityChannel(1)
        .build()

    val extractorsFactory = DefaultExtractorsFactory().setTsSubtitleFormats(listOf(cc1))

    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

    val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    return ExoPlayer.Builder(context)
        .setRenderersFactory(renderers)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(buildMultiscreenLoadControl())
        .setAudioAttributes(audioAttributes, false)
        .build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
}

@Stable
class MultiscreenState(
    private val context: Context,
    private val streamingHttp: StreamingHttpClient,
) {
    val players = mutableStateMapOf<Long, ExoPlayer>()
    private val channelNames = mutableMapOf<Long, String>()

    fun getOrCreatePlayer(
        channel: ChannelEntity,
        source: SourceEntity?,
        surroundMode: tv.own.owntv.player.SurroundMode,
        hwDecoding: Boolean
    ): ExoPlayer {
        channelNames[channel.id] = channel.name
        return players.getOrPut(channel.id) {
            val headers = StreamHeaders.decode(channel.httpHeaders)
            val ua = StreamHeaders.userAgentOf(headers)
                ?: source?.userAgent
                ?: HttpClient.DEFAULT_USER_AGENT

            buildMultiscreenPlayer(
                context = context,
                streamingHttp = streamingHttp,
                ua = ua,
                headers = headers,
                surroundMode = surroundMode,
                hwDecoding = hwDecoding
            ).apply {
                setMediaItem(MediaItem.fromUri(channel.playStreamUrl(source)))
                prepare()
            }
        }
    }

    fun applyAudioFocus(focusedId: Long?) {
        android.util.Log.d("Multiscreen", "Applying centralized audio focus: focusedId=$focusedId")
        players.forEach { (id, player) ->
            val focused = id == focusedId
            val name = channelNames[id] ?: "Unknown"
            
            val params = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !focused)
                .build()
            
            player.trackSelectionParameters = params
            player.volume = if (focused) 1f else 0f
            player.setAudioAttributes(player.audioAttributes, focused)
            
            android.util.Log.d("Multiscreen", "MULTISCREEN_AUDIO channelId=$id ($name) enabled=$focused volume=${player.volume} handleFocus=$focused")
        }
    }

    fun releaseUnused(currentIds: Set<Long>) {
        val unused = players.keys.filter { it !in currentIds }
        unused.forEach { id ->
            players[id]?.release()
            players.remove(id)
            channelNames.remove(id)
        }
    }

    /** Free one tile's player now (Replace), rather than after the grid recomposes without it. */
    fun release(channelId: Long) {
        players.remove(channelId)?.release()
        channelNames.remove(channelId)
    }

    fun releaseAll() {
        players.values.forEach { it.release() }
        players.clear()
        channelNames.clear()
    }
}

/** Mobile Multiview's tile padding: each tile sits this far inside its rect, so tiles show a gap. */
private val TILE_INSET = 4.dp

/** Mobile Multiview's controls timeout. */
private const val STRIP_HIDE_MS = 4_000L

// D-pad neighbours and Move Mode need the grid's shape, not its pixels; any 16:9 area gives the same.
private const val SHAPE_W = 1600f
private const val SHAPE_H = 900f

private fun shapeOf(count: Int) = MultiscreenLayout.rects(count, SHAPE_W, SHAPE_H)

private fun directionOf(key: Key): MultiscreenDirection? = when (key) {
    Key.DirectionLeft -> MultiscreenDirection.LEFT
    Key.DirectionRight -> MultiscreenDirection.RIGHT
    Key.DirectionUp -> MultiscreenDirection.UP
    Key.DirectionDown -> MultiscreenDirection.DOWN
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun MultiscreenScreen(
    onBack: () -> Unit,
    onChildFocused: () -> Unit,
    modifier: Modifier = Modifier,
    vm: MultiscreenViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val streamingHttp = koinInject<StreamingHttpClient>()
    val settings = koinInject<SettingsRepository>()
    val mpvPlayer = koinInject<tv.own.owntv.player.OwnTVPlayer>()
    val livePreviewEngine = koinInject<tv.own.owntv.player.LivePreviewEngine>()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val audioFocusIndex by vm.audioFocusIndex.collectAsStateWithLifecycle()
    val maxTiles = vm.maxTiles
    val surroundMode by settings.surroundMode.collectAsStateWithLifecycle(tv.own.owntv.player.SurroundMode.AUTO)
    val hwDecoding by settings.hwDecoding.collectAsStateWithLifecycle(true)

    val msState = remember { MultiscreenState(context, streamingHttp) }
    var activeModal by remember { mutableStateOf(MultiscreenModal.NONE) }
    var actionMenuChannelId by remember { mutableStateOf<Long?>(null) }
    var fullscreenChannelId by remember { mutableStateOf<Long?>(null) }
    var moveModeIndex by remember { mutableStateOf<Int?>(null) }
    // Move Mode's destination: the tile the moving one will trade places with on OK.
    var moveTargetIndex by remember { mutableStateOf<Int?>(null) }
    var originalChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    // Set while the picker is open to replace this tile's channel rather than add a tile.
    var replaceTargetId by remember { mutableStateOf<Long?>(null) }

    // Top control strip. Only composed while shown, so a hidden control can never hold focus.
    var stripVisible by remember { mutableStateOf(true) }
    var stripInteraction by remember { mutableLongStateOf(0L) }
    var stripHasFocus by remember { mutableStateOf(false) }
    var stripFocusPending by remember { mutableStateOf(false) }

    // True from the moment a modal closes until focus is restored to the intended tile. Closing the
    // picker drops focus, and a stray tile could take it for the ~60 ms before the restore — enough to
    // flip audio to the wrong tile. Plain holder, set during composition so it is in place before the
    // modal leaves the tree.
    val focusRestore = remember { FocusRestoreGate() }
    if (focusRestore.lastModal != activeModal) {
        if (activeModal == MultiscreenModal.NONE && moveModeIndex == null && fullscreenChannelId == null) {
            focusRestore.pending = true
        }
        focusRestore.lastModal = activeModal
    }

    // Focus requesters for grid tiles and Add button. Keyed by stable ChannelEntity.id.
    val tileRequesters = remember { mutableStateMapOf<Long, FocusRequester>() }
    val addRequester = remember { FocusRequester() }
    val stripRequester = remember { FocusRequester() }

    val canAddMore = channels.size < maxTiles
    val stripAvailable = channels.isNotEmpty() && fullscreenChannelId == null &&
        moveModeIndex == null && activeModal == MultiscreenModal.NONE
    val stripShown = stripAvailable && stripVisible

    fun pokeStrip() {
        stripVisible = true
        stripInteraction = System.nanoTime()
    }

    fun focusAudibleTile() {
        val id = channels.getOrNull(audioFocusIndex)?.id ?: channels.firstOrNull()?.id
        id?.let { tileRequesters[it] }?.let { runCatching { it.requestFocus() } }
    }

    // Recovery Handler: Ensure Back button ALWAYS works from Multiscreen.
    BackHandler {
        when {
            activeModal != MultiscreenModal.NONE -> activeModal = MultiscreenModal.NONE
            fullscreenChannelId != null -> fullscreenChannelId = null
            moveModeIndex != null -> {
                vm.setChannels(originalChannels)
                moveModeIndex = null
                moveTargetIndex = null
            }
            // Back from the control strip returns to the grid; only Back from the grid leaves.
            stripHasFocus -> focusAudibleTile()
            else -> onBack()
        }
    }

    // Replace mode belongs to one picker session only.
    LaunchedEffect(activeModal) {
        if (activeModal != MultiscreenModal.CHANNEL_PICKER) replaceTargetId = null
    }

    // Centralized audio focus logic.
    val focusedId = remember(channels, audioFocusIndex, fullscreenChannelId, activeModal, moveModeIndex, actionMenuChannelId) {
        if (activeModal != MultiscreenModal.NONE && actionMenuChannelId != null) {
            actionMenuChannelId
        } else if (moveModeIndex != null && actionMenuChannelId != null) {
            actionMenuChannelId
        } else {
            fullscreenChannelId ?: channels.getOrNull(audioFocusIndex)?.id
        }
    }

    // Keyed on the player set, not just its size: Replace swaps one player for another at the same
    // count, and the new one must be muted like any other unfocused tile.
    val playerIds = msState.players.keys.toSet()
    LaunchedEffect(focusedId, playerIds) {
        msState.applyAudioFocus(focusedId)
    }

    // Restore focus when modal closes, move mode ends, or fullscreen is exited.
    LaunchedEffect(activeModal, moveModeIndex, fullscreenChannelId) {
        if (activeModal == MultiscreenModal.NONE && moveModeIndex == null && fullscreenChannelId == null) {
            kotlinx.coroutines.delay(60.milliseconds)
            val restoreId = actionMenuChannelId?.takeIf { id -> channels.any { it.id == id } }
                ?: channels.getOrNull(audioFocusIndex)?.id
            val target = restoreId?.let { tileRequesters[it] } ?: addRequester
            runCatching { target.requestFocus() }
            // Audio goes straight to the restored tile; stray focus during the delay was ignored.
            focusRestore.pending = false
            channels.indexOfFirst { it.id == restoreId }.takeIf { it >= 0 }?.let(vm::setAudioFocus)
        } else {
            // A move or fullscreen took over before the restore ran; never leave tile audio gated.
            focusRestore.pending = false
        }
    }

    // Ensure focus stays on the moving tile during Move mode.
    LaunchedEffect(moveModeIndex, channels) {
        if (moveModeIndex != null) {
            channels.getOrNull(moveModeIndex!!)?.id?.let { id ->
                tileRequesters[id]?.let { requester ->
                    runCatching { requester.requestFocus() }
                }
            }
        }
    }

    // Capture the original channels when entering Move mode to support Cancel.
    LaunchedEffect(moveModeIndex) {
        if (moveModeIndex != null && originalChannels.isEmpty()) {
            originalChannels = channels
        } else if (moveModeIndex == null) {
            originalChannels = emptyList()
        }
    }

    // Auto-hide, standing down while the strip itself has focus so a focused control never vanishes.
    LaunchedEffect(stripVisible, stripInteraction, stripHasFocus) {
        if (stripVisible && !stripHasFocus) {
            kotlinx.coroutines.delay(STRIP_HIDE_MS.milliseconds)
            stripVisible = false
        }
    }
    LaunchedEffect(stripShown) {
        if (!stripShown) stripHasFocus = false
    }
    // UP from a top-row tile: the strip is composed first, then focused on the next frame.
    LaunchedEffect(stripFocusPending, stripShown) {
        if (stripFocusPending && stripShown) {
            kotlinx.coroutines.delay(16.milliseconds)
            runCatching { stripRequester.requestFocus() }
            stripFocusPending = false
        } else if (stripFocusPending && !stripAvailable) {
            stripFocusPending = false
        }
    }

    DisposableEffect(Unit) {
        livePreviewEngine.setAudioSuspended(true)
        mpvPlayer.stop()
        onDispose {
            livePreviewEngine.setAudioSuspended(false)
            msState.releaseAll()
        }
    }

    DisposableEffect(channels) {
        msState.releaseUnused(channels.map { it.id }.toSet())
        onDispose {}
    }

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
        if (channels.isEmpty() && activeModal != MultiscreenModal.CHANNEL_PICKER) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.content_multiscreen_empty), color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    OwnTVButton(
                        label = stringResource(R.string.content_multiscreen_add),
                        onClick = { activeModal = MultiscreenModal.CHANNEL_PICKER; actionMenuChannelId = null },
                        icon = OwnTVIcon.ADD,
                        modifier = Modifier.focusRequester(addRequester)
                    )
                }
            }
        } else {
            Box(modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val from = moveModeIndex
                    if (from != null) {
                        val direction = directionOf(event.key)
                        val isAction = event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.Back

                        if (direction != null || isAction) {
                            if (event.type == KeyEventType.KeyDown) {
                                if (direction != null) {
                                    // Arrows only pick the destination; nothing moves until OK.
                                    val current = moveTargetIndex ?: from
                                    MultiscreenLayout.neighbour(shapeOf(channels.size), current, direction)
                                        ?.let { moveTargetIndex = it }
                                }
                            } else if (event.type == KeyEventType.KeyUp) {
                                if (isAction) {
                                    if (event.key == Key.Back) {
                                        vm.setChannels(originalChannels)
                                    } else {
                                        val to = moveTargetIndex ?: from
                                        if (to != from && from in channels.indices) {
                                            actionMenuChannelId = channels[from].id
                                            vm.swapChannels(from, to)
                                        }
                                    }
                                    moveModeIndex = null
                                    moveTargetIndex = null
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    // Any key wakes the control strip, as a tap does on Mobile.
                    if (event.type == KeyEventType.KeyDown && stripAvailable) pokeStrip()
                    false
                }
            ) {
                if (fullscreenChannelId != null) {
                    val idx = channels.indexOfFirst { it.id == fullscreenChannelId }
                    if (idx >= 0) {
                        val channel = channels[idx]
                        val requester = remember(channel.id) { tileRequesters.getOrPut(channel.id) { FocusRequester() } }

                        MultiscreenTile(
                            channel = channel,
                            player = msState.getOrCreatePlayer(channel, sources[channel.sourceId], surroundMode, hwDecoding),
                            isAudible = true,
                            onFocused = {},
                            onClick = { activeModal = MultiscreenModal.FULLSCREEN_HUD },
                            onLongClick = { actionMenuChannelId = channel.id; activeModal = MultiscreenModal.ACTION_MENU },
                            modifier = Modifier.fillMaxSize(),
                            focusRequester = requester
                        )

                        if (activeModal == MultiscreenModal.FULLSCREEN_HUD) {
                    // Multiscreen is ExoPlayer only: the HUD drives this tile's own player.
                    val engine = remember(channel.id) {
                        MultiscreenExoEngine(msState.getOrCreatePlayer(channel, sources[channel.sourceId], surroundMode, hwDecoding), channel)
                    }
                    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()

                    tv.own.owntv.player.PlayerHud(
                        player = engine,
                        onBack = { activeModal = MultiscreenModal.NONE },
                        onToggleFavorite = { vm.toggleFavorite(channel) },
                        favorite = favoriteIds.contains(channel.id),
                        // No engine switch: Multiscreen tiles never move to mpv.
                    )
                }
                    }
                } else {
                    MultiscreenGrid(
                        channels = channels,
                        sources = sources,
                        msState = msState,
                        surroundMode = surroundMode,
                        hwDecoding = hwDecoding,
                        audibleId = focusedId,
                        moveModeIndex = moveModeIndex,
                        moveTargetIndex = moveTargetIndex,
                        isModalOpen = activeModal != MultiscreenModal.NONE,
                        tileRequesters = tileRequesters,
                        onTileFocused = {
                            if (activeModal == MultiscreenModal.NONE && !focusRestore.pending) vm.setAudioFocus(it)
                            onChildFocused()
                        },
                        onTileClick = {
                            if (activeModal == MultiscreenModal.NONE && moveModeIndex == null) {
                                fullscreenChannelId = channels[it].id
                                actionMenuChannelId = channels[it].id
                            }
                        },
                        onTileLongClick = {
                            if (activeModal == MultiscreenModal.NONE && moveModeIndex == null) {
                                actionMenuChannelId = channels[it].id
                                activeModal = MultiscreenModal.ACTION_MENU
                            }
                        },
                        onTopEdgeUp = {
                            pokeStrip()
                            if (canAddMore) stripFocusPending = true
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (stripShown) {
                MultiscreenControlStrip(
                    count = channels.size,
                    maxTiles = maxTiles,
                    canAdd = canAddMore,
                    addRequester = stripRequester,
                    gridRequester = (channels.getOrNull(audioFocusIndex) ?: channels.firstOrNull())
                        ?.id?.let { tileRequesters[it] },
                    onAdd = { actionMenuChannelId = null; activeModal = MultiscreenModal.CHANNEL_PICKER },
                    onFocusChanged = { stripHasFocus = it; if (it) pokeStrip() },
                )
            }
        }

        if (activeModal == MultiscreenModal.ACTION_MENU && actionMenuChannelId != null) {
            val idx = channels.indexOfFirst { it.id == actionMenuChannelId }
            if (idx >= 0) {
                MultiscreenActionMenu(
                    channelName = channels[idx].name,
                    onRemove = { vm.removeChannel(channels[idx].id); activeModal = MultiscreenModal.NONE },
                    onMove = { originalChannels = channels; moveModeIndex = idx; moveTargetIndex = idx; activeModal = MultiscreenModal.NONE },
                    onFullscreen = { fullscreenChannelId = channels[idx].id; activeModal = MultiscreenModal.NONE },
                    onAdd = if (canAddMore) {
                        { activeModal = MultiscreenModal.CHANNEL_PICKER }
                    } else null,
                    onReplace = { replaceTargetId = channels[idx].id; activeModal = MultiscreenModal.CHANNEL_PICKER },
                    onDismiss = { activeModal = MultiscreenModal.NONE }
                )
            } else {
                activeModal = MultiscreenModal.NONE
            }
        }

        if (activeModal == MultiscreenModal.CHANNEL_PICKER) {
            MultiscreenChannelPicker(
                onPick = { ch ->
                    val replaceId = replaceTargetId
                    if (replaceId != null) {
                        val index = channels.indexOfFirst { it.id == replaceId }
                        if (index >= 0 && ch.id != replaceId && channels.none { it.id == ch.id }) {
                            // Free the old tile's decoder before the new tile builds its player, so a
                            // decoder-limited box never needs both at once.
                            msState.release(replaceId)
                            vm.replaceChannel(index, ch)
                            if (fullscreenChannelId == replaceId) fullscreenChannelId = ch.id
                            actionMenuChannelId = ch.id
                            activeModal = MultiscreenModal.NONE
                        }
                    } else if (vm.addChannel(ch)) {
                        activeModal = MultiscreenModal.NONE
                        actionMenuChannelId = ch.id
                    }
                },
                onDismiss = { activeModal = MultiscreenModal.NONE },
                vm = vm,
                alreadyAddedIds = channels.map { it.id }.toSet(),
                replaceMode = replaceTargetId != null,
            )
        }

        if (moveModeIndex != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OwnTVTheme.colors.primary.copy(alpha = 0.9f))
                    .padding(8.dp)
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.content_multiscreen_swap_instructions),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MultiscreenGrid(
    channels: List<ChannelEntity>,
    sources: Map<Long, SourceEntity>,
    msState: MultiscreenState,
    surroundMode: tv.own.owntv.player.SurroundMode,
    hwDecoding: Boolean,
    audibleId: Long?,
    moveModeIndex: Int?,
    moveTargetIndex: Int?,
    isModalOpen: Boolean,
    tileRequesters: SnapshotStateMap<Long, FocusRequester>,
    onTileFocused: (Int) -> Unit,
    onTileClick: (Int) -> Unit,
    onTileLongClick: (Int) -> Unit,
    onTopEdgeUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Tiles are composed in the order their channels first appeared, never in display order, and
    // placed by offset: adding, removing, swapping or re-shaping the grid moves a tile's box without
    // disposing its PlayerView/SurfaceView (Mobile Multiview composes by player slot for the same reason).
    val hostOrder = remember { mutableListOf<Long>() }
    val ids = channels.map { it.id }
    hostOrder.retainAll(ids.toSet())
    ids.forEach { if (it !in hostOrder) hostOrder.add(it) }

    val shape = remember(channels.size) { shapeOf(channels.size) }

    BoxWithConstraints(modifier = modifier.then(if (isModalOpen) Modifier.focusProperties { canFocus = false } else Modifier)) {
        val rects = MultiscreenLayout.rects(channels.size, maxWidth.value, maxHeight.value)
        hostOrder.forEach { id ->
            // key(id) must be the loop's direct child: nested in a positional `if`, a tile whose loop
            // slot shifted (an earlier tile removed or replaced) lost its group and was rebuilt.
            key(id) {
                val idx = channels.indexOfFirst { it.id == id }
                val r = rects.getOrNull(idx)
                if (idx >= 0 && r != null) {
                    val channel = channels[idx]
                    val requester = remember(channel.id) { tileRequesters.getOrPut(channel.id) { FocusRequester() } }
                    fun neighbourRequester(direction: MultiscreenDirection): FocusRequester =
                        MultiscreenLayout.neighbour(shape, idx, direction)
                            ?.let { channels.getOrNull(it)?.id }
                            ?.let { tileRequesters[it] }
                            ?: FocusRequester.Cancel
                    val topRow = MultiscreenLayout.isTopRow(shape, idx)
                    MultiscreenTile(
                        channel = channel,
                        player = msState.getOrCreatePlayer(channel, sources[channel.sourceId], surroundMode, hwDecoding),
                        isAudible = audibleId == channel.id,
                            isMoving = moveModeIndex == idx,
                        isMoveTarget = moveModeIndex != null && moveTargetIndex == idx && moveModeIndex != idx,
                        onFocused = { onTileFocused(idx) },
                        onClick = { onTileClick(idx) },
                        onLongClick = { onTileLongClick(idx) },
                        modifier = Modifier
                            .offset(r.left.dp, r.top.dp)
                            .size(r.width.dp, r.height.dp)
                            .padding(TILE_INSET)
                            // Spatial D-pad from the same geometry the grid is drawn with.
                            .focusProperties {
                                left = neighbourRequester(MultiscreenDirection.LEFT)
                                right = neighbourRequester(MultiscreenDirection.RIGHT)
                                down = neighbourRequester(MultiscreenDirection.DOWN)
                                up = neighbourRequester(MultiscreenDirection.UP)
                            }
                            .onPreviewKeyEvent { event ->
                                if (topRow && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                                    onTopEdgeUp()
                                    true
                                } else {
                                    false
                                }
                            },
                        focusRequester = requester
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MultiscreenTile(
    channel: ChannelEntity,
    player: ExoPlayer,
    isAudible: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMoving: Boolean = false,
    isMoveTarget: Boolean = false,
    focusRequester: FocusRequester
) {
    var playbackError by remember(player) { mutableStateOf<String?>(null) }
    val decoderErrorMessage = stringResource(R.string.content_multiscreen_decoder_error)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                playbackError = when (e.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                        decoderErrorMessage
                    }
                    else -> e.localizedMessage ?: UNKNOWN_ERR
                }
                android.util.Log.e("MultiscreenDiag", "TILE_ERROR channelId=${channel.id} name=${channel.name} errorCode=${e.errorCode} message=${e.message}")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    playbackError = null
                    val format = player.videoFormat
                    android.util.Log.d("MultiscreenDiag", "TILE_READY channelId=${channel.id} name=${channel.name} res=${format?.width}x${format?.height} codec=${format?.sampleMimeType}")
                }
            }
        }

        val analyticsListener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                android.util.Log.d("MultiscreenDiag", "TILE_DECODER channelId=${channel.id} name=${channel.name} decoder=$decoderName")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                if (droppedFrames > 5) {
                    android.util.Log.w("MultiscreenDiag", "TILE_DROPPED channelId=${channel.id} name=${channel.name} dropped=$droppedFrames over ${elapsedMs}ms")
                }
            }
        }

        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
        onDispose {
            player.removeListener(listener)
            player.removeAnalyticsListener(analyticsListener)
        }
    }

    val colors = OwnTVTheme.colors
    var focused by remember { mutableStateOf(false) }
    val focusBorderWidth = tv.own.owntv.ui.theme.LocalFocusBorderWidth.current
    // Square corners and no focus scaling (Mobile Multiview): focus is the border alone.
    val (borderWidth, borderColor) = when {
        isMoveTarget -> 4.dp to colors.focusBorder
        isMoving -> 4.dp to colors.primary
        focused -> focusBorderWidth to colors.focusBorder
        else -> 1.dp to Color.White.copy(alpha = 0.08f)
    }
    // FIT everywhere: the whole broadcast picture, never cropped or stretched — score bugs, tickers
    // and lower thirds sit at the edges, and a crop to fill cut them off on the Shield. Black bars in
    // the 2- and 3-tile shapes are the accepted cost. FIT keeps the default SurfaceView inside its tile.
    val resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .clipToBounds()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    this.resizeMode = resizeMode
                    this.player = player
                }
            },
            update = { view ->
                if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
                if (view.player !== player) {
                    view.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isMoving) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(colors.primary.copy(alpha = 0.4f))
            )
        }

        if (playbackError != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OwnTVIcon(
                        icon = OwnTVIcon.CLOSE,
                        tint = Color.Red,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (playbackError.isNullOrBlank() || playbackError == UNKNOWN_ERR) stringResource(R.string.common_something_went_wrong) else playbackError!!, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OwnTVButton(
                        label = stringResource(R.string.common_retry),
                        onClick = { player.prepare(); player.play() },
                        compact = true
                    )
                }
            }
        }

        // Title chip, top-left (Mobile Multiview), capped so a long name never spans the tile. The
        // speaker for the audible tile rides in the chip: a separate top-right badge sat under the
        // control strip's Add button on the top-right tile.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.7f)
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isAudible) {
                    val audioLabel = stringResource(R.string.content_multiscreen_audio_tile)
                    OwnTVIcon(
                        icon = OwnTVIcon.VOLUME_HIGH,
                        tint = colors.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .semantics { contentDescription = audioLabel }
                    )
                }
                val logo = channel.displayLogoUrl
                if (!logo.isNullOrBlank()) {
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                Text(
                    text = channel.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(modifier = Modifier.matchParentSize().border(borderWidth, borderColor, RectangleShape))
    }
}

/**
 * The Multiscreen's top controls (Mobile Multiview's overlay, D-pad edition): Add channel and the
 * tile count. It floats over the video and never takes a cell. The caller composes it only while it
 * is shown, so a hidden strip holds no focusable control.
 */
@Composable
private fun BoxScope.MultiscreenControlStrip(
    count: Int,
    maxTiles: Int,
    canAdd: Boolean,
    addRequester: FocusRequester,
    gridRequester: FocusRequester?,
    onAdd: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 16.dp, end = 16.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.content_multiscreen_count, count, maxTiles),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        if (canAdd) {
            OwnTVButton(
                label = stringResource(R.string.content_multiscreen_add_channel),
                onClick = onAdd,
                icon = OwnTVIcon.ADD,
                compact = true,
                // Tonal pill that lifts to the accent container with the accent focus ring. On the
                // PRIMARY style the focus ring is the same accent as the fill, so focus barely showed.
                style = OwnTVButtonStyle.SECONDARY,
                modifier = Modifier
                    .focusRequester(addRequester)
                    .focusProperties {
                        down = gridRequester ?: FocusRequester.Default
                        up = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
            )
        }
    }
}

@Composable
private fun MultiscreenActionMenu(
    channelName: String,
    onRemove: () -> Unit,
    onMove: () -> Unit,
    onFullscreen: () -> Unit,
    // Null when the grid is already at this device's limit.
    onAdd: (() -> Unit)?,
    onReplace: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = OwnTVTheme.colors
    val initialFocus = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80.milliseconds)
        runCatching { initialFocus.requestFocus() }
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim()
            .trapAllFocusExit()
            .longPressMenuGuard()
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .dialogPanel()
                .focusGroup()
                .clickable(enabled = false) {},
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(channelName, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Spacer(Modifier.height(8.dp))
            
            OwnTVButton(
                label = stringResource(R.string.content_fullscreen),
                onClick = onFullscreen,
                icon = OwnTVIcon.FULLSCREEN,
                modifier = Modifier.fillMaxWidth().focusRequester(initialFocus)
            )
            OwnTVButton(label = stringResource(R.string.content_multiscreen_replace_channel), onClick = onReplace, icon = OwnTVIcon.LIVE_TV, modifier = Modifier.fillMaxWidth())
            if (onAdd != null) {
                OwnTVButton(label = stringResource(R.string.content_multiscreen_add_channel), onClick = onAdd, icon = OwnTVIcon.ADD, modifier = Modifier.fillMaxWidth())
            }
            OwnTVButton(label = stringResource(R.string.content_move), onClick = onMove, icon = OwnTVIcon.SWAP, modifier = Modifier.fillMaxWidth())
            OwnTVButton(label = stringResource(R.string.content_multiscreen_remove), onClick = onRemove, icon = OwnTVIcon.CLOSE, modifier = Modifier.fillMaxWidth(), style = OwnTVButtonStyle.SECONDARY)
            OwnTVButton(label = stringResource(R.string.common_cancel), onClick = onDismiss, icon = OwnTVIcon.BACK, modifier = Modifier.fillMaxWidth(), style = OwnTVButtonStyle.SECONDARY)
        }
    }
}

@Composable
private fun MultiscreenChannelPicker(
    onPick: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
    vm: MultiscreenViewModel,
    alreadyAddedIds: Set<Long>,
    // Picking replaces one tile's channel instead of adding a tile.
    replaceMode: Boolean = false,
) {
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    if (activeProfileId == null) return
    val categories by vm.pickerCategories.collectAsStateWithLifecycle()
    val selectedCategory by vm.pickerCategory.collectAsStateWithLifecycle()
    val searchQuery by vm.pickerSearch.collectAsStateWithLifecycle()
    val channels = vm.pickerChannels.collectAsLazyPagingItems()
    
    val searchRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100.milliseconds)
        runCatching { searchRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim()
            .trapAllFocusExit()
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .dialogPanel(width = 800.dp, scroll = false)
                .fillMaxHeight(0.85f)
                .focusGroup()
                .clickable(enabled = false) {},
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(if (replaceMode) R.string.content_multiscreen_replace_channel else R.string.content_multiscreen_picker_title), style = MaterialTheme.typography.titleLarge, color = OwnTVTheme.colors.onSurface)
            
            SearchBar(
                query = searchQuery,
                onQueryChange = vm::setPickerSearch,
                placeholder = stringResource(R.string.common_search_hint),
                surface = GlassSurface.DIALOGS,
                modifier = Modifier.fillMaxWidth().focusRequester(searchRequester)
            )

            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(
                    modifier = Modifier.width(200.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(categories) { item ->
                        val active = item.key == selectedCategory && searchQuery.isBlank()
                        FocusableSurface(
                            onClick = { vm.setPickerCategory(item.key); vm.setPickerSearch("") },
                            selected = active,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            focusedContainerColor = OwnTVTheme.colors.primaryContainer,
                            selectedContainerColor = OwnTVTheme.colors.primary,
                            surface = GlassSurface.DIALOGS
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (item.icon != null) {
                                    OwnTVIcon(item.icon, tint = if (active) OwnTVTheme.colors.onPrimary else OwnTVTheme.colors.onSurface, modifier = Modifier.size(16.dp))
                                }
                                Text(
                                    text = item.title ?: when (item.key) {
                                        LiveKey.All -> stringResource(R.string.content_category_all_channels)
                                        LiveKey.Favorites -> stringResource(R.string.content_category_favorites)
                                        LiveKey.History -> stringResource(R.string.content_category_history)
                                        else -> ""
                                    },
                                    color = if (active) OwnTVTheme.colors.onPrimary else OwnTVTheme.colors.onSurface,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (channels.itemCount == 0) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (searchQuery.isNotBlank()) stringResource(R.string.content_no_channels_found, searchQuery.trim())
                                       else stringResource(R.string.content_no_channels_here),
                                color = OwnTVTheme.colors.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                count = channels.itemCount,
                                key = channels.itemKey { it.id },
                                contentType = channels.itemContentType { "channel" }
                            ) { index ->
                                val ch = channels[index]
                                if (ch != null) {
                                    PickerChannelRow(
                                        channel = ch,
                                        alreadyAdded = alreadyAddedIds.contains(ch.id),
                                        onClick = { onPick(ch) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            OwnTVButton(label = stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth(), style = OwnTVButtonStyle.SECONDARY)
        }
    }
}

@Composable
private fun PickerChannelRow(
    channel: ChannelEntity,
    alreadyAdded: Boolean,
    onClick: () -> Unit
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        enabled = !alreadyAdded,
        modifier = Modifier.fillMaxWidth().alpha(if (alreadyAdded) 0.5f else 1f),
        shape = RoundedCornerShape(12.dp),
        surface = GlassSurface.DIALOGS,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.displayLogoUrl.isNullOrBlank()) {
                    AsyncImage(model = channel.displayLogoUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    OwnTVIcon(OwnTVIcon.LIVE_TV, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) colors.primary else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (alreadyAdded) {
                    Text(
                        stringResource(R.string.content_multiscreen_already_added),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary
                    )
                }
            }
        }
    }
}
