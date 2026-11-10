package tv.own.owntv.features.versions

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.catalog.versions.AdvertisedPicture
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.formatTimestamp
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * The version picker (a film's Watch, a series' Versions button, an episode's Other versions): one row
 * per version, best first, Up/Down to move, OK to choose, Back to close without choosing. Focus starts
 * where [VersionChoice.initialIndex] says. A version that cannot be chosen here (the episode is not in
 * it) is still listed with the reason, and OK on it does nothing. Same modal pattern as ResumeDialog
 * (non-clickable scrim, focus trapped inside, BackHandler), so Back always reaches it.
 */
@Composable
fun VersionPicker(
    options: List<VersionOption>,
    initialIndex: Int,
    onChoose: (VersionOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val model = remember(options) { VersionPickerModel(options.size, initialIndex) { options[it].unavailable == null } }
    val requesters = remember(options) { List(options.size) { FocusRequester() } }
    LaunchedEffect(options) {
        delay(50)
        runCatching { requesters[model.focusedIndex].requestFocus() }
    }
    BackHandler { if (model.dismiss()) onDismiss() }
    val colors = OwnTVTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).trapAllFocusExit().focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.dialogPanel(width = 640.dp, padding = 28.dp)) {
            Text(stringResource(R.string.content_versions_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.content_versions_as_listed), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEachIndexed { index, option ->
                    VersionRow(
                        option = option,
                        number = index + 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requesters[index])
                            .onFocusChanged { if (it.isFocused) model.focus(index) },
                        onClick = { model.choose(index)?.let { onChoose(options[it]) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(option: VersionOption, number: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(onClick = onClick, modifier = modifier) { focused ->
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    versionTitle(option, number),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        option.unavailable != null -> colors.onSurfaceVariant
                        focused -> colors.primary
                        else -> colors.onSurface
                    },
                )
                val where = listOfNotNull(option.playlistName, option.categoryName).distinct().joinToString(" · ")
                if (where.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(where, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                versionNote(option)?.let { note ->
                    Spacer(Modifier.height(2.dp))
                    Text(note, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                option.isCurrent -> Text(stringResource(R.string.content_version_current), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                option.unavailable != null -> Unit
                option.completed -> Text(stringResource(R.string.content_version_watched), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                option.positionMs > 0 -> Text(
                    stringResource(R.string.content_resume_at, formatTimestamp(option.positionMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
            }
            if (option.isFavorite) {
                Spacer(Modifier.width(10.dp))
                OwnTVIcon(OwnTVIcon.FAVORITE, tint = colors.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** "4K · Dolby Vision · EN", "1080p", or "Version 2" when the provider labels nothing. */
@Composable
private fun versionTitle(option: VersionOption, number: Int): String {
    val parts = listOfNotNull(
        qualityLabel(option.qualityRank),
        option.picture?.let { pictureLabel(it) },
        option.language,
    )
    return if (parts.isEmpty()) stringResource(R.string.content_version_unlabelled, number) else parts.joinToString(" · ")
}

@Composable
private fun qualityLabel(rank: Int): String? = when (rank) {
    5 -> stringResource(R.string.content_version_quality_8k)
    4 -> stringResource(R.string.content_version_quality_4k)
    3 -> stringResource(R.string.content_version_quality_1080p)
    2 -> stringResource(R.string.content_version_quality_720p)
    1 -> stringResource(R.string.content_version_quality_sd)
    else -> null
}

@Composable
private fun pictureLabel(picture: AdvertisedPicture): String = when (picture) {
    AdvertisedPicture.DOLBY_VISION -> stringResource(R.string.content_version_picture_dolby_vision)
    AdvertisedPicture.HDR10_PLUS -> stringResource(R.string.content_version_picture_hdr10_plus)
    AdvertisedPicture.HDR10 -> stringResource(R.string.content_version_picture_hdr10)
    AdvertisedPicture.HDR -> stringResource(R.string.content_version_picture_hdr)
}

/** Why a version may not look as labelled on this TV, or null when nothing is in doubt. */
@Composable
private fun versionNote(option: VersionOption): String? = when {
    option.unavailable == VersionOption.Unavailable.MISSING -> stringResource(R.string.content_version_not_available)
    option.unavailable == VersionOption.Unavailable.UNMATCHED -> stringResource(R.string.content_version_not_matched)
    option.unavailable == VersionOption.Unavailable.NOT_LOADED -> stringResource(R.string.content_version_not_loaded)
    option.pictureMayFallBack && option.picture == AdvertisedPicture.DOLBY_VISION -> stringResource(R.string.content_version_may_lack_dolby_vision)
    option.pictureMayFallBack -> stringResource(R.string.content_version_may_lack_hdr)
    option.qualityRank >= VersionChoice.UHD_RANK && !option.confirmed -> stringResource(R.string.content_version_may_lack_4k)
    else -> null
}
