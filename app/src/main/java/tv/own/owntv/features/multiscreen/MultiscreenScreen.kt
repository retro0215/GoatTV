package tv.own.owntv.features.multiscreen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
    val mpvPlayer: tv.own.owntv.player.OwnTVPlayer,
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

    fun prepareMpv(channel: ChannelEntity, source: SourceEntity?) {
        val url = channel.playStreamUrl(source)
        if (mpvPlayer.currentMediaUrl != url) {
            mpvPlayer.stop()
            mpvPlayer.play(
                url = url,
                title = channel.name,
                logoUrl = channel.displayLogoUrl,
                isLive = true
            )
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

    fun releaseAll() {
        players.values.forEach { it.release() }
        players.clear()
        channelNames.clear()
    }
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
    val tileEngines by vm.tileEngines.collectAsStateWithLifecycle()
    val surroundMode by settings.surroundMode.collectAsStateWithLifecycle(tv.own.owntv.player.SurroundMode.AUTO)
    val hwDecoding by settings.hwDecoding.collectAsStateWithLifecycle(true)

    val msState = remember { MultiscreenState(context, streamingHttp, mpvPlayer) }
    var activeModal by remember { mutableStateOf(MultiscreenModal.NONE) }
    var actionMenuChannelId by remember { mutableStateOf<Long?>(null) }
    var fullscreenChannelId by remember { mutableStateOf<Long?>(null) }
    var moveModeIndex by remember { mutableStateOf<Int?>(null) }
    var originalChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }

    // Focus requesters for grid tiles and Add button. Keyed by stable ChannelEntity.id.
    val tileRequesters = remember { mutableStateMapOf<Long, FocusRequester>() }
    val addRequester = remember { FocusRequester() }
    
    // Recovery Handler: Ensure Back button ALWAYS works from Multiscreen.
    BackHandler {
        when {
            activeModal != MultiscreenModal.NONE -> activeModal = MultiscreenModal.NONE
            fullscreenChannelId != null -> fullscreenChannelId = null
            moveModeIndex != null -> {
                vm.setChannels(originalChannels)
                moveModeIndex = null
            }
            else -> onBack()
        }
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

    LaunchedEffect(focusedId, msState.players.size) {
        msState.applyAudioFocus(focusedId)
    }

    // Restore focus when modal closes, move mode ends, or fullscreen is exited.
    LaunchedEffect(activeModal, moveModeIndex, fullscreenChannelId) {
        if (activeModal == MultiscreenModal.NONE && moveModeIndex == null && fullscreenChannelId == null) {
            kotlinx.coroutines.delay(60.milliseconds)
            val target = actionMenuChannelId?.let { tileRequesters[it] } ?: addRequester
            runCatching { target.requestFocus() }
        }
    }

    // Ensure focus follows the moving tile immediately during Move mode.
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
                    if (moveModeIndex != null) {
                        val from = moveModeIndex!!
                        val isDirectional = event.key == Key.DirectionUp || event.key == Key.DirectionDown || 
                                           event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                        val isAction = event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.Back

                        if (isDirectional || isAction) {
                            if (event.type == KeyEventType.KeyDown) {
                                if (isDirectional) {
                                    val to = when (event.key) {
                                        Key.DirectionUp -> when {
                                            channels.size == 3 && from == 2 -> 1
                                            from >= 2 -> from - 2
                                            else -> from
                                        }
                                        Key.DirectionDown -> when {
                                            channels.size == 3 && from == 1 -> 2
                                            from <= 1 && channels.size > from + 2 -> from + 2
                                            else -> from
                                        }
                                        Key.DirectionLeft -> when {
                                            channels.size == 3 && from == 1 -> 0
                                            channels.size == 3 && from == 2 -> 0
                                            from % 2 == 1 -> from - 1
                                            else -> from
                                        }
                                        Key.DirectionRight -> when {
                                            channels.size == 3 && from == 0 -> 1
                                            from % 2 == 0 && channels.size > from + 1 -> from + 1
                                            else -> from
                                        }
                                        else -> from
                                    }
                                    if (to != from) {
                                        val movingId = channels[from].id
                                        vm.moveChannel(from, to)
                                        moveModeIndex = to
                                        actionMenuChannelId = movingId 
                                    }
                                }
                            } else if (event.type == KeyEventType.KeyUp) {
                                if (isAction) {
                                    if (event.key == Key.Back) {
                                        vm.setChannels(originalChannels)
                                    }
                                    moveModeIndex = null
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
            ) {
                if (fullscreenChannelId != null) {
                    val idx = channels.indexOfFirst { it.id == fullscreenChannelId }
                    if (idx >= 0) {
                        val channel = channels[idx]
                        val useExo = tileEngines[channel.id] ?: true
                        val requester = remember(channel.id) { tileRequesters.getOrPut(channel.id) { FocusRequester() } }
                        
                        MultiscreenTile(
                            channel = channel,
                            player = if (useExo) msState.getOrCreatePlayer(channel, sources[channel.sourceId], surroundMode, hwDecoding) else null,
                            mpvPlayer = if (!useExo) msState.mpvPlayer else null,
                            isFocused = true,
                            onFocused = {},
                            onClick = { activeModal = MultiscreenModal.FULLSCREEN_HUD },
                            onLongClick = { actionMenuChannelId = channel.id; activeModal = MultiscreenModal.ACTION_MENU },
                            modifier = Modifier.fillMaxSize(),
                            focusRequester = requester
                        )

                        if (activeModal == MultiscreenModal.FULLSCREEN_HUD) {
                            val engine = remember(channel.id, useExo) {
                                if (useExo) {
                                    MultiscreenExoEngine(msState.getOrCreatePlayer(channel, sources[channel.sourceId], surroundMode, hwDecoding), channel)
                                } else {
                                    tv.own.owntv.player.MpvPlaybackEngine(msState.mpvPlayer)
                                }
                            }
                            val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
                            
                            tv.own.owntv.player.PlayerHud(
                                player = engine,
                                onBack = { activeModal = MultiscreenModal.NONE },
                                onToggleFavorite = { vm.toggleFavorite(channel) },
                                favorite = favoriteIds.contains(channel.id),
                                onToggleCompatMode = { vm.toggleEngine(channel.id) },
                                compatMode = !useExo,
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
                        audioFocusIndex = audioFocusIndex,
                        moveModeIndex = moveModeIndex,
                        isModalOpen = activeModal != MultiscreenModal.NONE,
                        tileRequesters = tileRequesters,
                        addRequester = addRequester,
                        tileEngines = tileEngines,
                        onTileFocused = { if (activeModal == MultiscreenModal.NONE) vm.setAudioFocus(it); onChildFocused() },
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
                        onAddClick = { if (activeModal == MultiscreenModal.NONE) { actionMenuChannelId = null; activeModal = MultiscreenModal.CHANNEL_PICKER } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (activeModal == MultiscreenModal.ACTION_MENU && actionMenuChannelId != null) {
            val idx = channels.indexOfFirst { it.id == actionMenuChannelId }
            if (idx >= 0) {
                MultiscreenActionMenu(
                    channelName = channels[idx].name,
                    onRemove = { vm.removeChannel(channels[idx].id); activeModal = MultiscreenModal.NONE },
                    onMove = { originalChannels = channels; moveModeIndex = idx; activeModal = MultiscreenModal.NONE },
                    onFullscreen = { fullscreenChannelId = channels[idx].id; activeModal = MultiscreenModal.NONE },
                    onDismiss = { activeModal = MultiscreenModal.NONE }
                )
            } else {
                activeModal = MultiscreenModal.NONE
            }
        }

        if (activeModal == MultiscreenModal.CHANNEL_PICKER) {
            MultiscreenChannelPicker(
                onPick = { ch ->
                    if (vm.addChannel(ch)) {
                        activeModal = MultiscreenModal.NONE
                        actionMenuChannelId = ch.id 
                    }
                },
                onDismiss = { activeModal = MultiscreenModal.NONE },
                vm = vm,
                alreadyAddedIds = channels.map { it.id }.toSet()
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
                    text = stringResource(R.string.common_move_instructions),
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
    audioFocusIndex: Int,
    moveModeIndex: Int?,
    isModalOpen: Boolean,
    tileRequesters: SnapshotStateMap<Long, FocusRequester>,
    addRequester: FocusRequester,
    tileEngines: Map<Long, Boolean>,
    onTileFocused: (Int) -> Unit,
    onTileClick: (Int) -> Unit,
    onTileLongClick: (Int) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridCount = if (channels.size < 4) channels.size + 1 else 4
    
    @Composable
    fun TileWrapper(idx: Int, mod: Modifier = Modifier) {
        if (idx < channels.size) {
            val channel = channels[idx]
            key(channel.id) {
                val useExo = tileEngines[channel.id] ?: true
                val requester = remember(channel.id) { tileRequesters.getOrPut(channel.id) { FocusRequester() } }
                MultiscreenTile(
                    channel = channel,
                    player = if (useExo) msState.getOrCreatePlayer(channel, sources[channel.sourceId], surroundMode, hwDecoding) else null,
                    mpvPlayer = if (!useExo) msState.mpvPlayer else null,
                    isFocused = (audioFocusIndex == idx) || (moveModeIndex == idx),
                    isMoving = moveModeIndex == idx,
                    onFocused = { onTileFocused(idx) },
                    onClick = { onTileClick(idx) },
                    onLongClick = { onTileLongClick(idx) },
                    modifier = mod,
                    focusRequester = requester
                )
                
                if (!useExo) {
                    LaunchedEffect(channel.id) {
                        msState.prepareMpv(channel, sources[channel.sourceId])
                    }
                }
            }
        } else {
            AddTile(onClick = onAddClick, focusRequester = addRequester, modifier = mod)
        }
    }

    Box(modifier = modifier.then(if (isModalOpen) Modifier.focusProperties { canFocus = false } else Modifier)) {
        when (gridCount) {
            1 -> Box(Modifier.fillMaxSize()) { TileWrapper(0, Modifier.fillMaxSize()) }
            2 -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { TileWrapper(0, Modifier.fillMaxSize()) }
                Box(Modifier.weight(1f).fillMaxHeight()) { TileWrapper(1, Modifier.fillMaxSize()) }
            }
            3 -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(2f).fillMaxHeight()) { TileWrapper(0, Modifier.fillMaxSize()) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { TileWrapper(1, Modifier.fillMaxSize()) }
                    Box(Modifier.weight(1f).fillMaxWidth()) { TileWrapper(2, Modifier.fillMaxSize()) }
                }
            }
            4 -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { TileWrapper(0, Modifier.fillMaxSize()) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { TileWrapper(1, Modifier.fillMaxSize()) }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { TileWrapper(2, Modifier.fillMaxSize()) }
                    Box(Modifier.weight(1f).fillMaxHeight()) { 
                        if (channels.size == 4) TileWrapper(3, Modifier.fillMaxSize())
                        else AddTile(onClick = onAddClick, focusRequester = addRequester, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MultiscreenTile(
    channel: ChannelEntity,
    player: ExoPlayer?,
    mpvPlayer: tv.own.owntv.player.OwnTVPlayer?,
    isFocused: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMoving: Boolean = false,
    focusRequester: FocusRequester
) {
    var playbackError by remember(player, mpvPlayer) { mutableStateOf<String?>(null) }
    val decoderErrorMessage = stringResource(R.string.content_multiscreen_decoder_error)

    if (player != null) {
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    playbackError = when (e.errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> {
                            decoderErrorMessage
                        }
                        else -> e.localizedMessage ?: UNKNOWN_ERR
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) playbackError = null
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
            }
        }
    }

    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusRequester(focusRequester),
        shape = RoundedCornerShape(4.dp),
        focusedScale = if (isMoving) 1.05f else 1.02f,
        selected = isFocused,
        showFocusBorder = true,
        focusedContainerColor = if (isMoving) OwnTVTheme.colors.primaryContainer else OwnTVTheme.colors.card,
        surface = GlassSurface.CARDS,
    ) {
        if (player != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        this.player = player
                    }
                },
                update = { view ->
                    if (view.player !== player) {
                        view.player = player
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (mpvPlayer != null) {
            tv.own.owntv.player.MpvVideoSurface(player = mpvPlayer, modifier = Modifier.fillMaxSize())
        }

        if (isMoving) {
            Box(modifier = Modifier
                .fillMaxSize()
                .border(6.dp, OwnTVTheme.colors.primary, RoundedCornerShape(4.dp))
                .background(OwnTVTheme.colors.primary.copy(alpha = 0.4f))
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
                        onClick = { player?.prepare(); player?.play() },
                        compact = true
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val logo = channel.displayLogoUrl
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
            Text(
                text = channel.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.focusRequester(focusRequester),
        shape = RoundedCornerShape(4.dp),
        surface = GlassSurface.CARDS
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OwnTVIcon(OwnTVIcon.ADD, tint = colors.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.content_multiscreen_add), color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun MultiscreenActionMenu(
    channelName: String,
    onRemove: () -> Unit,
    onMove: () -> Unit,
    onFullscreen: () -> Unit,
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
    alreadyAddedIds: Set<Long>
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
            Text(stringResource(R.string.content_multiscreen_picker_title), style = MaterialTheme.typography.titleLarge, color = OwnTVTheme.colors.onSurface)
            
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
