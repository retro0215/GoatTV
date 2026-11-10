package tv.own.owntv.core.sync.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import tv.own.owntv.core.catalog.versions.CatalogTitleBackfill

/**
 * Runs [CatalogTitleBackfill] in the background — enqueued once per app start (unique work, KEEP).
 * Nearly always a quick no-op: it only has work for rows written before schema v31 or after a parse-rule
 * change. A failure is logged and left for the next start; the derived fields are an index, not data.
 */
class CatalogTitleBackfillWorker(
    context: Context,
    params: WorkerParameters,
    private val backfill: CatalogTitleBackfill,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val report = backfill.run()
            Log.i(TAG, "done scanned=${report.scanned} written=${report.written} fullPass=${report.fullPass} ms=${SystemClock.elapsedRealtime() - startedAt}")
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "backfill failed; it runs again on the next start", e)
            Result.success()
        }
    }

    companion object {
        const val TAG = "CatalogTitleBackfill"
        fun workName(): String = "catalog-title-backfill"
    }
}
