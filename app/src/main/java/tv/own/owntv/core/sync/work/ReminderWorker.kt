package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ReminderDao
import tv.own.owntv.core.notification.ReminderEventBus
import tv.own.owntv.core.notification.ReminderNotifier

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
    private val reminderDao: ReminderDao,
    private val reminderNotifier: ReminderNotifier,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "REMINDER_WORK"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "REMINDER_WORK_START")
        val workType = inputData.getString("work_type") ?: "warning"
        val reminderId = inputData.getLong("reminder_id", -1L)
        val channelId = inputData.getLong("channel_id", -1L)
        val programStartMs = inputData.getLong("program_start_ms", -1L)

        val reminder = reminderDao.getAll().find {
            (reminderId > 0 && it.id == reminderId) || (it.channelId == channelId && it.programStartMs == programStartMs)
        } ?: return@withContext Result.success()

        val now = System.currentTimeMillis()
        if (now > reminder.programStartMs + 30 * 60 * 1000L) {
            reminderDao.delete(reminder.channelId, reminder.programStartMs)
            return@withContext Result.success()
        }

        when (workType) {
            "warning" -> {
                Log.d(TAG, "REMINDER_WARNING")
                reminderNotifier.notify(reminder)
                // WARNING WORKER MUST NOT DELETE THE REMINDER!
            }
            "autotune" -> {
                Log.d(TAG, "REMINDER_AUTOTUNE")
                ReminderEventBus.emitAutotune(reminder)
                reminderDao.delete(reminder.channelId, reminder.programStartMs)
            }
        }

        Result.success()
    }
}
