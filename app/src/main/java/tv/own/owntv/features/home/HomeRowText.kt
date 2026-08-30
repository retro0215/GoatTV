package tv.own.owntv.features.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

@Composable
fun HomeRow.displayTitle(): String = stringResource(
    when (this) {
        HomeRow.TRENDING -> R.string.home_row_now_trending
        HomeRow.HERO -> R.string.home_row_keep_watching
        HomeRow.TOP_RATED_MOVIES -> R.string.home_row_top_rated_movies
        HomeRow.TOP_RATED_SERIES -> R.string.home_row_top_rated_series
        HomeRow.RECENT_MOVIES -> R.string.home_row_recent_movies
        HomeRow.RECENT_SERIES -> R.string.home_row_recent_series
        HomeRow.RECENT_CHANNELS -> R.string.home_row_recent_channels
        HomeRow.FAVORITE_CHANNELS -> R.string.home_row_favorite_channels
        HomeRow.CONTINUE_MOVIES -> R.string.home_row_continue_movies
        HomeRow.CONTINUE_SERIES -> R.string.home_row_continue_series
    },
)

@Composable
fun HomeRow.settingsDescription(): String = stringResource(
    when (this) {
        HomeRow.TRENDING -> R.string.home_row_trending_description
        HomeRow.HERO -> R.string.home_row_hero_description
        HomeRow.TOP_RATED_MOVIES -> R.string.home_row_top_rated_movies_description
        HomeRow.TOP_RATED_SERIES -> R.string.home_row_top_rated_series_description
        HomeRow.RECENT_MOVIES -> R.string.home_row_recent_movies_description
        HomeRow.RECENT_SERIES -> R.string.home_row_recent_series_description
        HomeRow.RECENT_CHANNELS -> R.string.home_row_recent_description
        HomeRow.FAVORITE_CHANNELS -> R.string.home_row_favorite_description
        HomeRow.CONTINUE_MOVIES -> R.string.home_row_continue_movies_description
        HomeRow.CONTINUE_SERIES -> R.string.home_row_continue_series_description
    },
)

@Composable
fun HomeLiveRowMode.displayLabel(): String = stringResource(
    when (this) {
        HomeLiveRowMode.CARDS -> R.string.home_row_cards
        HomeLiveRowMode.ON_NOW -> R.string.home_row_on_now
    },
)
