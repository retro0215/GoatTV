package tv.own.owntv.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import tv.own.owntv.core.notifications.GoatBannerState
import tv.own.owntv.ui.theme.OwnTVTheme

@Composable
fun BrandBanner(onNavigate: (String) -> Unit) {
    val banner = GoatBannerState.currentBanner
    val focusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // Clear banner if app goes to background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                GoatBannerState.dismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Reset scroll when banner changes
    LaunchedEffect(banner?.tick) {
        scrollState.scrollTo(0)
    }

    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.fillMaxWidth().zIndex(999f)
    ) {
        if (banner != null) {
            LaunchedEffect(banner.tick) {
                focusRequester.requestFocus()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 42.dp) // TV safe margin + extra comfort
                    .wrapContentSize(Alignment.TopCenter)
            ) {
                Surface(
                    onClick = {
                        banner.link?.let { onNavigate(it) }
                        GoatBannerState.dismiss()
                    },
                    modifier = Modifier
                        .widthIn(min = 400.dp, max = 800.dp)
                        .heightIn(max = screenHeight * 0.4f)
                        .padding(horizontal = 24.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.Back -> {
                                        GoatBannerState.dismiss()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (scrollState.canScrollForward) {
                                            scope.launch { scrollState.animateScrollBy(150f) }
                                            true
                                        } else false
                                    }
                                    Key.DirectionUp -> {
                                        if (scrollState.canScrollBackward) {
                                            scope.launch { scrollState.animateScrollBy(-150f) }
                                            true
                                        } else false
                                    }
                                    else -> false
                                }
                            } else false
                        },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = OwnTVTheme.colors.surfaceContainerHigh.copy(alpha = 0.95f),
                        focusedContainerColor = OwnTVTheme.colors.primaryContainer
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(OwnTVTheme.colors.primary, 12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OwnTVIcon(
                            icon = OwnTVIcon.INFO,
                            tint = OwnTVTheme.colors.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = banner.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = OwnTVTheme.colors.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = normalizeHtml(banner.message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = OwnTVTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Safely normalizes basic HTML line breaks to \n.
 */
private fun normalizeHtml(text: String): String {
    return text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
}
