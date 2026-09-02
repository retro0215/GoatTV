package tv.own.owntv.core.notifications

import com.pushwoosh.notification.PushMessage
import com.pushwoosh.notification.PushwooshNotificationFactory

/**
 * Custom Pushwoosh notification factory for AllAccess to handle channel migration.
 * DERIVED FROM: Goal to move to IMPORTANCE_HIGH (4) via a new channel ID.
 */
class AllAccessNotificationFactory : PushwooshNotificationFactory() {

    companion object {
        const val CHANNEL_ID = "allaccess_notifications_v2"
        const val CHANNEL_NAME = "AllAccess Notifications"
    }

    override fun addChannel(pushMessage: PushMessage): String {
        // Explicitly use the new channel ID to bypass Android's immutable importance restriction
        // on the old "pushwoosh_push_notification" channel.
        return CHANNEL_ID
    }

    override fun channelName(nameFromManifest: String?): String {
        return CHANNEL_NAME
    }
}
