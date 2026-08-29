package tv.own.owntv.core.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProviderMetadataDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.ProviderMetadataEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient

/**
 * Loads rich metadata for movies on demand from the provider (Xtream get_vod_info).
 * Results are cached in the provider_metadata_cache table.
 */
class MovieRepository(
    private val movieDao: MovieDao,
    private val providerMetadataDao: ProviderMetadataDao,
    private val sourceDao: SourceDao,
    private val xtream: XtreamClient,
) {
    /**
     * Resolves rich provider metadata for a movie. Returns the cached row (fresh or freshly fetched),
     * or null when the provider doesn't support it or the network failed.
     */
    suspend fun getProviderMetadata(movie: MovieEntity): ProviderMetadataEntity? = withContext(Dispatchers.IO) {
        val key = getCacheKey(movie)
        val cached = providerMetadataDao.getMetadata(key)
        
        // Return cache if it's fresh enough (e.g. 7 days).
        if (cached != null && !isStale(cached)) {
            return@withContext cached
        }
        
        val source = sourceDao.getById(movie.sourceId) ?: return@withContext cached
        // Detailed VOD info is currently an Xtream-only path.
        if (source.type != SourceType.XTREAM || movie.remoteId == null) {
            return@withContext cached
        }
        
        try {
            val rich = xtream.getVodInfo(source, movie.remoteId)
            
            // Safety: don't cache useless results for 7 days, but do return them to the UI.
            // A result with no plot/director/actors is considered "thin".
            val isRich = rich != null && (!rich.plot.isNullOrBlank() || !rich.director.isNullOrBlank() || !rich.actors.isNullOrBlank())

            val entity = ProviderMetadataEntity(
                key = key,
                sourceId = movie.sourceId,
                remoteId = movie.remoteId,
                title = rich?.title,
                plot = rich?.plot,
                rating = rich?.rating,
                releaseDate = rich?.releaseDate,
                year = rich?.year,
                genre = rich?.genre,
                durationSecs = rich?.durationSecs,
                director = rich?.director,
                actors = rich?.actors,
                trailer = rich?.trailer,
                backdropUrl = rich?.backdropUrls?.firstOrNull(),
                posterUrl = rich?.posterUrl,
                tmdbId = rich?.tmdbId,
                updatedAt = System.currentTimeMillis()
            )
            
            if (isRich) {
                providerMetadataDao.upsertMetadata(entity)
            }
            entity
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch rich VOD info for movieId=${movie.id} remoteId=${movie.remoteId}", e)
            cached
        }
    }

    suspend fun getCachedMetadata(movie: MovieEntity): ProviderMetadataEntity? {
        return providerMetadataDao.getMetadata(getCacheKey(movie))
    }

    fun isStale(entity: ProviderMetadataEntity): Boolean =
        System.currentTimeMillis() - entity.updatedAt >= STALE_MS

    fun getCacheKey(movie: MovieEntity): String =
        "movie:${movie.sourceId}:${movie.remoteId ?: movie.name}"

    companion object {
        private const val TAG = "MovieRepository"
        internal const val STALE_MS = 7L * 24 * 3600 * 1000 // 7 days
    }
}
