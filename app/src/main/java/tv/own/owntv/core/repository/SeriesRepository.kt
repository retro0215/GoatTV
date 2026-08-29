package tv.own.owntv.core.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.stalkerCredentials

/** Loads a series' seasons/episodes on demand (Xtream `get_series_info`; Stalker paged
 *  `get_ordered_list&movie_id=` season rows, plan D-2). Episodes carry their season number
 *  directly, so no separate Season rows are needed for browsing. */
class SeriesRepository(
    private val seriesDao: SeriesDao,
    private val sourceDao: SourceDao,
    private val xtream: XtreamClient,
    private val userData: tv.own.owntv.core.backup.UserDataResolver,
    private val stalkerClient: StalkerClient,
    private val stalkerAuth: StalkerAuthManager,
) {
    /**
     * Returns true if episodes are available (cached or freshly fetched). Xtream + Stalker.
     */
    suspend fun loadEpisodes(series: SeriesEntity): Boolean = loadEpisodesWithInfo(series) != null

    /**
     * Loads a series' seasons/episodes and its rich metadata (Xtream only).
     * Returns the rich info block if available.
     */
    suspend fun loadEpisodesWithInfo(series: SeriesEntity): tv.own.owntv.core.parser.XtProviderMetadata? = withContext(Dispatchers.IO) {
        val cachedCount = seriesDao.episodeCount(series.id)
        val source = sourceDao.getById(series.sourceId) ?: return@withContext null

        if (source.type != SourceType.XTREAM && source.type != SourceType.STALKER) return@withContext null

        val remoteId = series.remoteId
        if (remoteId.isNullOrBlank()) return@withContext null

        // Read the stamp from the database rather than the passed-in entity.
        val syncedAt = seriesDao.getSeriesById(series.id)?.episodesSyncedAt ?: series.episodesSyncedAt
        val shouldRefresh = shouldRefreshEpisodes(cachedCount, syncedAt, System.currentTimeMillis())

        val result = when (source.type) {
            SourceType.XTREAM -> fetchXtreamWithInfo(series, source, remoteId)
            else -> fetchStalkerEpisodes(series, source, remoteId) to null
        }
        val fetched = result.first
        val info = result.second

        if (!fetched.isNullOrEmpty() && shouldRefresh) {
            applyEpisodes(series.id, fetched)
        }
        info
    }

    /**
     * Writes a fetched list over the stored one, preserving the row id of every episode that
     * survives — see [planEpisodeMerge].
     */
    private suspend fun applyEpisodes(seriesId: Long, fetched: List<EpisodeEntity>) {
        val plan = planEpisodeMerge(seriesDao.episodesBySeriesOnce(seriesId), fetched)
        plan.updates.chunked(CHUNK).forEach { seriesDao.updateEpisodes(it) }
        plan.inserts.chunked(CHUNK).forEach { seriesDao.upsertEpisodes(it) }
        plan.deleteIds.chunked(CHUNK).forEach { seriesDao.deleteEpisodesByIds(it) }
        seriesDao.markEpisodesSynced(seriesId, System.currentTimeMillis())
        // Episode rows just appeared — restored/pending episode history and resume can attach now.
        if (plan.inserts.isNotEmpty() || plan.updates.isNotEmpty()) runCatching { userData.resolvePending() }
    }

    private suspend fun fetchXtreamWithInfo(
        series: SeriesEntity, source: SourceEntity, remoteId: String
    ): Pair<List<EpisodeEntity>?, tv.own.owntv.core.parser.XtProviderMetadata?> = try {
        val info = xtream.getSeriesInfo(source, remoteId)
        val episodes = info.episodes.map { e ->
            EpisodeEntity(
                seriesId = series.id,
                seasonId = null,
                seasonNumber = e.seasonNumber,
                episodeNumber = e.episodeNumber,
                name = e.title,
                streamUrl = xtream.seriesEpisodeUrl(source, e.id, e.containerExt),
                containerExt = e.containerExt,
                remoteId = e.id,
                plot = e.plot,
                durationSecs = e.durationSecs,
                rating = e.rating,
                releaseDate = e.releaseDate,
                stillUrl = e.stillUrl,
            )
        }
        episodes to info.info
    } catch (e: Exception) {
        android.util.Log.w(TAG, "xtream episode load failed seriesId=${series.id}", e)
        null to null
    }

    /**
     * Stalker (plan D-2): the show's seasons come from paged `get_ordered_list&movie_id=<remoteId>`.
     */
    private suspend fun fetchStalkerEpisodes(series: SeriesEntity, source: SourceEntity, remoteId: String): List<EpisodeEntity>? = try {
        val mac = source.mac?.let { StalkerClient.canonicalizeMac(it) } ?: return null
        val creds = source.stalkerCredentials(mac)
        val seasons = ArrayList<StalkerClient.SeasonItem>()
        var page = 1
        while (page <= MAX_SEASON_PAGES) {
            val p = stalkerAuth.withAuthRetry(creds) { session ->
                stalkerClient.getSeriesSeasons(
                    session.apiBase, mac, session.token, creds.userAgent,
                    categoryId = null, movieId = remoteId, page = page,
                )
            }
            seasons += p.items
            val maxPer = p.maxPageItems.takeIf { it > 0 } ?: p.items.size
            val pages = if (maxPer > 0) (p.totalItems + maxPer - 1) / maxPer else 1
            if (p.items.isEmpty() || page >= pages) break
            page++
        }
        val episodes = ArrayList<EpisodeEntity>()
        seasons.forEachIndexed { index, season ->
            val cmd = season.cmd ?: return@forEachIndexed
            val seasonNo = season.seasonNumber ?: (index + 1)
            season.episodes.forEach { epNo ->
                episodes.add(
                    EpisodeEntity(
                        seriesId = series.id,
                        seasonId = null,
                        seasonNumber = seasonNo,
                        episodeNumber = epNo,
                        name = "",
                        streamUrl = cmd,
                        containerExt = null,
                        remoteId = "${season.id}:$epNo",
                    )
                )
            }
        }
        episodes
    } catch (e: Exception) {
        android.util.Log.w(TAG, "stalker episode load failed seriesId=${series.id}", e)
        null
    }

    private companion object {
        const val TAG = "SeriesRepository"
        const val MAX_SEASON_PAGES = 20
        const val CHUNK = 500
    }
}
