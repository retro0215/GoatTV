package tv.own.owntv.core.notifications

import android.app.Application

/**
 * No-op implementation for brand flavors without push notifications.
 */
object PushNotifications {
    fun init(app: Application) {
        // No-op for this brand
    }
}
