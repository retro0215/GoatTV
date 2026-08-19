package tv.own.owntv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.AccentCyan
import tv.own.owntv.ui.theme.OwnTVTheme
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
/**
 * Theme-adaptive "OwnTV" wordmark. The provided logo asset has a near-white "Own" that vanishes on
 * AMOLED black, so the in-app lockup is drawn from brand tokens instead and stays legible on both
 * themes. The cyan play-mark and the "TV" accent are constant brand colors.
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Int = 36,
    textSize: Int = 26,
) {
    Image(
        painter = painterResource(id = R.drawable.owntv_wordmark),
        contentDescription = stringResource(id = R.string.brand_full_name),
        modifier = modifier
            .width((markSize * 7).dp)
            .height((markSize * 2.5).dp),
        contentScale = ContentScale.Fit
    )
}