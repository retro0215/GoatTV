package tv.own.owntv.features.tutorials

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.settings.GroupLabel
import tv.own.owntv.features.settings.Header
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Settings → Video Tutorials: the brand's remotely managed tutorial catalog, grouped by category.
 * Selecting a tutorial opens it in YouTube ([YouTubeLauncher]); there is no in-app player. The screen
 * never touches playback: it only reads the catalog, so Live TV / the mini player keep running until
 * the customer actually opens a video in another app.
 */
@Composable
fun VideoTutorialsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: VideoTutorialsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    var launchMessage by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { vm.onEnter() }
    BackHandler { onBack() }
    // Move focus into the list (or onto Retry) once there is something to focus, once per phase.
    LaunchedEffect(state.phase) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    LaunchedEffect(launchMessage) {
        if (launchMessage != null) { delay(LAUNCH_MESSAGE_MS); launchMessage = null }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusProperties { onEnter = { runCatching { firstFocus.requestFocus() } } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Header(title = stringResource(R.string.settings_video_tutorials), onBack = onBack)
            if (state.staging) Chip(stringResource(R.string.tutorials_staging_marker))
        }
        Spacer(Modifier.height(4.dp))
        launchMessage?.let { Notice(stringResource(it)) }

        when (state.phase) {
            VideoTutorialsUiState.Phase.LOADING -> StatusText(stringResource(R.string.tutorials_loading))
            VideoTutorialsUiState.Phase.EMPTY -> {
                StatusText(stringResource(R.string.tutorials_empty))
                // Keeps focus inside the screen (Back / Retry) when there is nothing else to focus.
                RetryButton(onClick = vm::retry, modifier = Modifier.focusRequester(firstFocus))
            }
            VideoTutorialsUiState.Phase.OFFLINE, VideoTutorialsUiState.Phase.ERROR -> {
                StatusText(
                    stringResource(
                        if (state.phase == VideoTutorialsUiState.Phase.OFFLINE) R.string.tutorials_offline else R.string.tutorials_error,
                    ),
                )
                RetryButton(onClick = vm::retry, modifier = Modifier.focusRequester(firstFocus))
            }
            VideoTutorialsUiState.Phase.CONTENT -> {
                state.savedNotice?.let { notice ->
                    Notice(
                        stringResource(
                            if (notice == VideoTutorialsUiState.SavedNotice.OFFLINE) R.string.tutorials_saved_offline
                            else R.string.tutorials_saved_error,
                        ),
                    )
                }
                var first = true
                state.groups.forEach { group ->
                    GroupLabel(categoryLabel(group.category))
                    group.tutorials.forEach { tutorial ->
                        val rowModifier = if (first) Modifier.focusRequester(firstFocus) else Modifier
                        first = false
                        TutorialRow(
                            tutorial = tutorial,
                            modifier = rowModifier,
                            onClick = {
                                launchMessage = when (YouTubeLauncher.launch(context, tutorial.youtubeVideoId)) {
                                    is YouTubeLaunchResult.Launched -> null
                                    YouTubeLaunchResult.InvalidVideo -> R.string.tutorials_invalid_video
                                    YouTubeLaunchResult.NoCompatibleApp -> R.string.tutorials_no_youtube_app
                                }
                            },
                        )
                    }
                }
                if (state.savedNotice != null) RetryButton(onClick = vm::retry)
            }
        }
    }
}

@Composable
private fun TutorialRow(tutorial: VideoTutorial, modifier: Modifier, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(width = 160.dp, height = 90.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                OwnTVIcon(OwnTVIcon.PLAY, tint = colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
                YouTubeVideoId.thumbnailUrl(tutorial.youtubeVideoId)?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tutorial.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                tutorial.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            tutorial.durationSeconds?.let { Chip(DateUtils.formatElapsedTime(it.toLong())) }
        }
    }
}

@Composable
private fun RetryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(14.dp),
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.Center,
    ) { _ ->
        Text(
            stringResource(R.string.common_retry),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = OwnTVTheme.colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun Notice(text: String) {
    val colors = OwnTVTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.secondaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun Chip(text: String) {
    val colors = OwnTVTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onPrimaryContainer,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Known admin categories are localized; an unknown slug is shown readably ("new-thing" → "New thing"). */
@Composable
private fun categoryLabel(category: String?): String = when (category) {
    null -> stringResource(R.string.tutorials_category_other)
    "getting-started" -> stringResource(R.string.tutorials_category_getting_started)
    "live-tv" -> stringResource(R.string.tutorials_category_live_tv)
    "movies-series" -> stringResource(R.string.tutorials_category_movies_series)
    "sports" -> stringResource(R.string.tutorials_category_sports)
    "multiscreen" -> stringResource(R.string.tutorials_category_multiscreen)
    "settings" -> stringResource(R.string.tutorials_category_settings)
    "troubleshooting" -> stringResource(R.string.tutorials_category_troubleshooting)
    else -> readableSlug(category)
}

internal fun readableSlug(slug: String): String =
    slug.split('-').filter { it.isNotEmpty() }.joinToString(" ").replaceFirstChar { it.titlecase() }

private const val LAUNCH_MESSAGE_MS = 5_000L
