package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import tv.own.owntv.BuildConfig
import tv.own.owntv.core.database.entity.ReminderEntity
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    private const val TAG = "REMINDER_SCHEDULER"

    fun schedule(context: Context, reminder: ReminderEntity) {
        val now = System.currentTimeMillis()
        val warningTrigger = maxOf(now, reminder.programStartMs - 60_000L)
        val autotuneTrigger = maxOf(now, reminder.programStartMs)

        val warningDelayMs = maxOf(0L, warningTrigger - now)
        val autotuneDelayMs = maxOf(0L, autotuneTrigger - now)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "REMINDER_DEBUG_TIMING")
        }

        val warningData = Data.Builder()
            .putString("work_type", "warning")
            .putLong("reminder_id", reminder.id)
            .putLong("channel_id", reminder.channelId)
            .putLong("program_start_ms", reminder.programStartMs)
            .build()

        val warningReq = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(warningDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(warningData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "epg_warning_${reminder.channelId}_${reminder.programStartMs}",
            ExistingWorkPolicy.REPLACE,
            warningReq,
        )

        val autotuneData = Data.Builder()
            .putString("work_type", "autotune")
            .putLong("reminder_id", reminder.id)
            .putLong("channel_id", reminder.channelId)
            .putLong("program_start_ms", reminder.programStartMs)
            .build()

        val autotuneReq = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(autotuneDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(autotuneData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "epg_autotune_${reminder.channelId}_${reminder.programStartMs}",
            ExistingWorkPolicy.REPLACE,
            autotuneReq,
        )
        Log.d(TAG, "REMINDER_SCHEDULED: channelId=${reminder.channelId}, title=${reminder.programTitle}")
    }

    fun cancel(context: Context, channelId: Long, programStartMs: Long) {
        Log.d(TAG, "REMINDER_CANCELLED: channelId=$channelId, start=$programStartMs")
        WorkManager.getInstance(context).cancelUniqueWork("epg_warning_${channelId}_$programStartMs")
        WorkManager.getInstance(context).cancelUniqueWork("epg_autotune_${channelId}_$programStartMs")
    }
}
