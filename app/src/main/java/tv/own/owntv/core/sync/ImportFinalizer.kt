package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.channelcatalog.ChannelCatalogRepository

/** Per-type counts after a playlist import, for the success breakdown. */
data class SyncCounts(val channels: Int, val movies: Int, val series: Int, val epg: Int = 0)

class ImportFinalizer(
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val db: OwnTVDatabase,
    private val bulkInsertHelper: BulkInsertHelper,
    private val metadataDao: tv.own.owntv.core.database.dao.MetadataDao,
    private val channelCatalogRepository: ChannelCatalogRepository,
) {
    suspend fun finalize(source: SourceEntity, deferIndexes: Boolean = false): SyncCounts {
        val counts = contentCounts(source.id)
        runCatching {
            val cutoff = System.currentTimeMillis() - METADATA_TTL_MS
            val cache = metadataDao.evictCacheOlderThan(cutoff)
            val matches = metadataDao.evictMatchesOlderThan(cutoff)
            if (cache > 0 || matches > 0) Log.i(TAG, "Metadata eviction: cache=$cache matches=$matches")
        }
        if (!deferIndexes) {
            ensureContentIndexes()
        }
        // Best-effort asynchronous channel catalog ingestion
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching {
                channelCatalogRepository.syncSourceCatalog(source)
            }.onFailure { e ->
                Log.w(TAG, "Channel catalog ingestion failed (best-effort): ${e.message}", e)
            }
        }
        return counts
    }

    suspend fun contentCounts(sourceId: Long): SyncCounts = SyncCounts(
        channels = channelDao.countForSourceOnce(sourceId),
        movies = movieDao.countForSourceOnce(sourceId),
        series = seriesDao.countForSourceOnce(sourceId),
    )

    suspend fun ensureContentIndexes() = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "ensureContentIndexes start")
        runCatching {
            val w = db.openHelper.writableDatabase
            val indexesStartedAt = SystemClock.elapsedRealtime()
            listOf("channels", "movies", "series").forEach { table ->
                OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.getValue(table).forEach { w.execSQL(it) }
            }
            Log.d(TAG, "ensureContentIndexes create indexes ms=${SystemClock.elapsedRealtime() - indexesStartedAt}")

            val analyzeStartedAt = SystemClock.elapsedRealtime()
            bulkInsertHelper.analyzeTables("movies", "series", "channels", "categories")
            Log.d(TAG, "ensureContentIndexes analyze ms=${SystemClock.elapsedRealtime() - analyzeStartedAt}")
        }.onSuccess {
            Log.i(TAG, "ensureContentIndexes end ms=${SystemClock.elapsedRealtime() - startedAt}")
        }.onFailure {
            Log.w(TAG, "ensureContentIndexes failed ms=${SystemClock.elapsedRealtime() - startedAt}", it)
        }
    }

    companion object {
        private const val TAG = "ImportFinalizer"
        private const val METADATA_TTL_MS = 90L * 24 * 60 * 60 * 1000
    }
}
