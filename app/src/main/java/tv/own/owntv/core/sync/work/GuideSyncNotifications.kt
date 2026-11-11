package tv.own.owntv.core.sync.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import tv.own.owntv.R
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore

/**
 * The low-priority "Updating TV Guide" notification that lets [EpgSyncWorker] run as a foreground job
 * (4.6.1). A regular job is stopped by Android after about ten minutes, and a large guide on a slow TV box
 * can take longer than that. Built like the downloads notification: WorkManager's own foreground service
 * (declared `dataSync` in the manifest, with FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC), silent,
 * ongoing, on its own low-importance channel named after the TV Guide.
 */
internal object GuideSyncNotifications {

    private const val CHANNEL_ID = "owntv_guide_updates"
    /** Distinct from the downloads notification (4201): both can run at once. */
    private const val NOTIFICATION_ID = 4202

    @Volatile
    private var lastChannelLocaleKey: String? = null
    private val channelLock = Any()

    fun foregroundInfo(context: Context, localeStore: LocaleStore): ForegroundInfo {
        val localized = AppLocale.wrap(context, localeStore.currentTag.value)
        ensureChannel(context, localized)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(localized.getString(R.string.content_epg_updating_notification))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()
        return foregroundInfo(notification, Build.VERSION.SDK_INT)
    }

    /**
     * Android 10+ is told the service type (Android 14+ requires it, and it must match the manifest's
     * `dataSync`); Android 7–9 take the untyped form.
     */
    internal fun foregroundInfo(notification: Notification, sdkInt: Int): ForegroundInfo =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    /** Notification channels exist from Android 8; before that the notification needs none. */
    private fun ensureChannel(context: Context, localized: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val name = localized.getString(R.string.content_epg_title)
        val key = "${localized.resources.configuration.locales[0]?.toLanguageTag().orEmpty()}:$name"
        if (key == lastChannelLocaleKey) return
        synchronized(channelLock) {
            if (key == lastChannelLocaleKey) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
            lastChannelLocaleKey = key
        }
    }
}
