package tv.own.owntv.features.favorites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.ui.components.ChannelLogoCard
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.ContentTabChip
import tv.own.owntv.ui.components.EmptyState
import tv.own.owntv.ui.components.ErrorState
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.LoadingState
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.PosterCard
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/** One favorite on screen, whatever its kind — enough for a tile, the menu and the remove call. */
private data class FavItem(val id: Long, val title: String, val art: String?, val tab: FavoritesTab)

/**
 * The unified Favorites screen (navigation Phase B): Live | Movies | Series, from the existing favorites.
 *
 * D-pad: Left/Right across the tabs selects them; Down enters the grid; Up from the grid's top row returns
 * to the selected tab; a long-press on a tile opens a small menu with Remove from Favorites. Back is the
 * shell's (focus the sidebar, then the exit dialog) — this screen adds none of its own.
 */
@Composable
fun FavoritesScreen(
    vm: FavoritesViewModel,
    onPlayChannel: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onOpenSeries: (SeriesEntity) -> Unit,
    onChildFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    val tabFocus = remember { FavoritesTab.entries.associateWith { FocusRequester() } }
    val firstItemFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<FavItem?>(null) }
    // After a removal: (removed id, its index). Focus moves only once Room has dropped that item from the
    // list — until then the old tile is still on screen and focusing it would lose focus a frame later.
    var pendingRemoval by remember { mutableStateOf<Pair<Long, Int>?>(null) }

    val items: List<FavItem> = when (tab) {
        FavoritesTab.LIVE -> state.live.map { FavItem(it.id, it.name, it.displayLogoUrl, tab) }
        FavoritesTab.MOVIES -> state.movies.map { FavItem(it.id, it.name, it.posterUrl, tab) }
        FavoritesTab.SERIES -> state.series.map { FavItem(it.id, it.name, it.posterUrl, tab) }
    }

    val settledTarget: Int? = pendingRemoval
        ?.takeIf { (id, _) -> items.none { it.id == id } }
        ?.let { (_, index) -> FavoritesFocus.indexAfterRemoval(index, items.size) ?: -1 } // -1 = the tab
    LaunchedEffect(settledTarget) {
        val target = settledTarget ?: return@LaunchedEffect
        repeat(10) {
            androidx.compose.runtime.withFrameNanos { }
            val ok = runCatching { if (target < 0) tabFocus.getValue(tab).requestFocus() else restoreFocus.requestFocus() }.isSuccess
            if (ok) { pendingRemoval = null; return@LaunchedEffect }
            delay(30)
        }
        pendingRemoval = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel(fillColor = ContentPanelFill)
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .padding(horizontal = Dimens.ScreenPaddingH, vertical = Dimens.ScreenPaddingV),
    ) {
        Text(stringResource(R.string.content_category_favorites), style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
        Spacer(Modifier.height(14.dp))

        // Tabs. Entering the row (from the sidebar, or Up from the grid) lands on the selected tab.
        Row(
            modifier = Modifier
                .focusProperties { onEnter = { runCatching { tabFocus.getValue(tab).requestFocus() } } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FavoritesTab.entries.forEach { t ->
                ContentTabChip(
                    label = stringResource(t.labelRes),
                    selected = t == tab,
                    modifier = Modifier.focusRequester(tabFocus.getValue(t)),
                    onFocused = { vm.selectTab(t) },
                    onClick = { vm.selectTab(t) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState(stringResource(R.string.content_category_favorites), Modifier.align(Alignment.Center))
                state.error -> ErrorState(stringResource(R.string.favorites_error), Modifier.align(Alignment.Center))
                items.isEmpty() -> EmptyState(
                    icon = OwnTVIcon.STAR,
                    title = stringResource(tab.emptyTitleRes),
                    message = stringResource(R.string.favorites_empty_message),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (tab == FavoritesTab.LIVE) 170.dp else 130.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { onEnter = { runCatching { firstItemFocus.requestFocus() } } }
                        .focusGroup(),
                ) {
                    itemsIndexed(items, key = { _, it -> "${it.tab}:${it.id}" }) { index, item ->
                        val focusMod = when {
                            settledTarget != null && index == settledTarget -> Modifier.focusRequester(restoreFocus)
                            index == 0 -> Modifier.focusRequester(firstItemFocus)
                            else -> Modifier
                        }
                        val open = {
                            when (item.tab) {
                                FavoritesTab.LIVE -> state.live.firstOrNull { it.id == item.id }?.let { onPlayChannel(it, state.live) }
                                FavoritesTab.MOVIES -> state.movies.firstOrNull { it.id == item.id }?.let(onOpenMovie)
                                FavoritesTab.SERIES -> state.series.firstOrNull { it.id == item.id }?.let(onOpenSeries)
                            }
                            Unit
                        }
                        if (item.tab == FavoritesTab.LIVE) {
                            ChannelLogoCard(item.title, item.art, focusMod, onClick = open, onLongClick = { menuFor = item })
                        } else {
                            PosterCard(
                                posterUrl = item.art,
                                title = item.title,
                                isFavorite = true,
                                modifier = focusMod,
                                onClick = open,
                                onLongClick = { menuFor = item },
                            )
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { item ->
        FavoriteMenu(
            title = item.title,
            onOpen = {
                menuFor = null
                when (item.tab) {
                    FavoritesTab.LIVE -> state.live.firstOrNull { it.id == item.id }?.let { onPlayChannel(it, state.live) }
                    FavoritesTab.MOVIES -> state.movies.firstOrNull { it.id == item.id }?.let(onOpenMovie)
                    FavoritesTab.SERIES -> state.series.firstOrNull { it.id == item.id }?.let(onOpenSeries)
                }
            },
            onRemove = {
                pendingRemoval = item.id to items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                menuFor = null
                vm.remove(item.tab, item.id)
            },
            onDismiss = { menuFor = null },
        )
    }
}

private val FavoritesTab.labelRes: Int
    get() = when (this) {
        FavoritesTab.LIVE -> R.string.favorites_tab_live
        FavoritesTab.MOVIES -> R.string.common_nav_movies
        FavoritesTab.SERIES -> R.string.common_nav_series
    }

private val FavoritesTab.emptyTitleRes: Int
    get() = when (this) {
        FavoritesTab.LIVE -> R.string.favorites_empty_live
        FavoritesTab.MOVIES -> R.string.favorites_empty_movies
        FavoritesTab.SERIES -> R.string.favorites_empty_series
    }

/** The long-press menu, in the same modal pattern as the Live/Movies/Series context menus. */
@Composable
private fun FavoriteMenu(title: String, onOpen: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.dialogPanel(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            OwnTVButton(
                stringResource(R.string.content_remove_favourite),
                onClick = onRemove, style = OwnTVButtonStyle.SECONDARY, icon = OwnTVIcon.FAVORITE,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            OwnTVButton(stringResource(R.string.favorites_open), onClick = onOpen, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
        }
    }
}
