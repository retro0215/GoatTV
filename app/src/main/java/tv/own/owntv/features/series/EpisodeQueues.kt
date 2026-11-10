package tv.own.owntv.features.series

import tv.own.owntv.core.database.entity.EpisodeEntity

/**
 * The playback queue around an episode — always within that episode's own series row, so a queue
 * started in one version of a show (4K, say) never advances into another version's episodes.
 */
object EpisodeQueues {

    /** [episode]'s season in its own series, in episode order: what Next and auto-advance walk. */
    fun seasonQueue(all: List<EpisodeEntity>, episode: EpisodeEntity): List<EpisodeEntity> =
        all.filter { it.seriesId == episode.seriesId && it.seasonNumber == episode.seasonNumber }
            .sortedBy { it.episodeNumber }

    /** The first episode of the season after [finished]'s, in the same series row; null when it was the last. */
    fun nextSeasonFirst(all: List<EpisodeEntity>, finished: EpisodeEntity): EpisodeEntity? =
        all.filter { it.seriesId == finished.seriesId && it.seasonNumber == finished.seasonNumber + 1 }
            .minByOrNull { it.episodeNumber }
}
