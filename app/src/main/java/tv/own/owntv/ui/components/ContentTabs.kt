package tv.own.owntv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * One tab of the Live | Movies | Series strip shared by the Favorites and Search screens (navigation
 * Phases B/C). Selecting on focus is what makes Left/Right switch tabs; the brand accent marks the selected
 * one through the theme's primaryContainer.
 */
@Composable
fun ContentTabChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        selected = selected,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = RoundedCornerShape(20.dp),
        selectedContainerColor = colors.primaryContainer,
        surface = GlassSurface.CARDS,
    ) { focused ->
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = when {
                selected -> colors.onPrimaryContainer
                focused -> colors.primary
                else -> colors.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/** A channel tile for Live grids (Favorites, Search): the logo (lazy, via Coil) above the name. */
@Composable
fun ChannelLogoCard(
    title: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(118.dp),
        shape = RoundedCornerShape(Dimens.PosterCardCorner),
        surface = GlassSurface.CARDS,
    ) { focused ->
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.Center) {
                if (logoUrl != null) {
                    AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    OwnTVIcon(OwnTVIcon.LIVE_TV, tint = colors.onSurfaceVariant, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
