package tv.own.owntv.core.launcher

import tv.own.owntv.core.isolation.IsolatedTestBuild

/**
 * App-facing launcher repository. It keeps the public methods in one place while the old TV Provider
 * publisher remains the implementation detail for now.
 *
 * The isolated test APK publishes nothing to the Android TV home screen (no Watch Next, no Recent
 * Live channel), so it never adds rows beside the customer app's; clearing its own rows stays allowed.
 */
class LauncherIntegrationRepository(
    private val planner: LauncherRecommendationPlanner,
    private val resolver: LauncherLaunchResolver,
    private val tvHomeRepository: tv.own.owntv.core.tv.TvHomeRepository,
    private val publishingAllowed: () -> Boolean = { !IsolatedTestBuild.active },
) {
    suspend fun buildSnapshot(profileId: Long): LauncherSnapshot = planner.buildSnapshot(profileId)

    suspend fun refreshProfile(profileId: Long, allowBrowsableRequest: Boolean = false) {
        if (publishingAllowed()) tvHomeRepository.refreshProfile(profileId, allowBrowsableRequest)
    }

    suspend fun clearProfile(profileId: Long) = tvHomeRepository.clearProfile(profileId)

    suspend fun publishMovieProgress(profileId: Long, movieId: Long, positionMs: Long, durationMs: Long) {
        if (publishingAllowed()) tvHomeRepository.publishMovieProgress(profileId, movieId, positionMs, durationMs)
    }

    suspend fun publishEpisodeProgress(profileId: Long, episodeId: Long, positionMs: Long, durationMs: Long) {
        if (publishingAllowed()) tvHomeRepository.publishEpisodeProgress(profileId, episodeId, positionMs, durationMs)
    }

    suspend fun refreshRecentLive(profileId: Long, allowBrowsableRequest: Boolean = false) {
        if (publishingAllowed()) tvHomeRepository.refreshRecentLive(profileId, allowBrowsableRequest)
    }

    suspend fun resolveLaunch(profileId: Long, deepLink: LauncherDeepLink): LauncherLaunch? =
        resolver.resolveLaunch(profileId, deepLink)
}
