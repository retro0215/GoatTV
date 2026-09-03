package tv.own.owntv.core.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tv.own.owntv.MainActivity
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ReminderEntity

object ReminderEventBus {
    private const val TAG = "REMINDER_EVENT_BUS"
    private val _reminderEvents = MutableSharedFlow<ReminderEntity>(extraBufferCapacity = 10)
    val reminderEvents: SharedFlow<ReminderEntity> = _reminderEvents.asSharedFlow()

    private val _autotuneEvents = MutableSharedFlow<ReminderEntity>(extraBufferCapacity = 10)
    val autotuneEvents: SharedFlow<ReminderEntity> = _autotuneEvents.asSharedFlow()

    fun emit(reminder: ReminderEntity) {
        Log.d(TAG, "REMINDER_EVENT_SENT: title=${reminder.programTitle}")
        _reminderEvents.tryEmit(reminder)
    }

    fun emitAutotune(reminder: ReminderEntity) {
        Log.d(TAG, "AUTOTUNE_EVENT_SENT: title=${reminder.programTitle}")
        _autotuneEvents.tryEmit(reminder)
    }
}

class ReminderNotifier(private val context: Context) {

    companion object {
        private const val TAG = "REMINDER_NOTIFIER"
        const val CHANNEL_ID = "epg_program_reminders"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
    }

    @SuppressLint("MissingPermission")
    fun notify(reminder: ReminderEntity) {
        Log.d(TAG, "REMINDER_NOTIFY: title=${reminder.programTitle}")
        ReminderEventBus.emit(reminder)

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Program Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Program reminders for live TV"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CHANNEL_ID, reminder.channelId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            reminder.channelId.toInt() + reminder.programStartMs.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = context.getString(R.string.content_reminder_title)
        val text = context.getString(R.string.content_reminder_message, reminder.channelName)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${reminder.programTitle}\n$text"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(reminder.id.toInt(), notification)
        }
    }
}
