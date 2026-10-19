package tv.own.owntv.core.sync.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.epg.EpgAutoRefreshPolicy
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * Enqueues an EPG sync for every guide source whose auto-refresh is due. One instance per process, shared by
 * the app's cold-start/resume check and the periodic [EpgAutoRefreshWorker], so both draw on the same
 * per-source attempt spacing. Only reads settings itself; the download is an ordinary [EpgSyncWorker] job
 * (unique per source, KEEP, network constraint, bounded retries), so a source already syncing is left alone.
 */
class EpgAutoRefreshCheck(
    private val settings: SettingsRepository,
    private val store: EpgSourceStore,
    private val epgDao: EpgDao,
    private val scheduler: EpgSyncScheduler,
) {
    private val spacing = EpgAutoRefreshPolicy.AttemptSpacing()

    /** [coldStart] = first check of an app launch (the only time STARTUP sources refresh). */
    suspend fun run(coldStart: Boolean, trigger: String) {
        val due = EpgAutoRefreshPolicy.dueSources(
            sources = store.getAll().map { EpgAutoRefreshPolicy.SourceState(it.id, it.lastSyncAt) },
            stored = settings.epgAutoRefresh.first(),
            nowMs = System.currentTimeMillis(),
            coldStart = coldStart,
            spacing = spacing,
            elapsedMs = SystemClock.elapsedRealtime(),
        )
        for (id in due) {
            Log.d(TAG, "epg auto-refresh due sourceId=$id trigger=$trigger — enqueuing")
            scheduler.enqueueSync(
                id,
                reason = "auto_refresh",
                baseProgrammes = epgDao.countForSources(listOf(id)),
                policy = ExistingWorkPolicy.KEEP,
            )
        }
    }

    private companion object {
        const val TAG = "EpgAutoRefresh"
    }
}

/**
 * Periodic background check (about hourly, see [EpgSyncScheduler.schedulePeriodicCheck]) so a guide refreshes
 * when due even if the app is never closed or restarted — including while Live TV is playing, since the
 * import runs off the player's threads and never touches playback. Never fails: a broken check simply waits
 * for the next period.
 */
class EpgAutoRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val check: EpgAutoRefreshCheck,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            check.run(coldStart = false, trigger = "periodic")
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w("EpgAutoRefresh", "periodic check failed", e)
        }
        return Result.success()
    }
}
