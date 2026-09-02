package tv.own.owntv.core.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.pushwoosh.Pushwoosh
import com.pushwoosh.internal.event.EventBus
import com.pushwoosh.notification.handlers.message.user.NotificationCreatedEvent
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import tv.own.owntv.BuildConfig

/**
 * Brand-specific push notification logic for AllAccess.
 */
object PushNotifications {
    private const val TAG = "HomePreview"

    fun init(app: Application) {
        if (!BuildConfig.PUSHWOOSH_ENABLED) return

        try {
            // Ensure the new migration channel exists with HIGH importance (4).
            // Android channels are immutable after creation, so we MUST use a new ID.
            createMigrationChannel(app)

            // FirebaseApp is initialized by the google-services plugin's ContentProvider,            // but we call getInstance() to ensure it's ready.
            FirebaseApp.getInstance()

            // Registration flow is safe to call; it handles its own internal task safety.
            startRegistrationFlow()

            // Register foreground notification listener for in-app banners.
            registerForegroundListener()
        } catch (e: Exception) {
            // Safe production-only log as requested.
            Log.e(TAG, "PushNotifications: initialization failed - ${e.javaClass.simpleName}")
        }
    }

    private fun registerForegroundListener() {
        EventBus.subscribe(NotificationCreatedEvent::class.java) { event ->
            val message = event.message ?: return@subscribe
            val title = message.header ?: "AllAccess"
            val body = message.message ?: ""
            val customData = message.customData
            val link = try {
                if (!customData.isNullOrBlank()) {
                    org.json.JSONObject(customData).optString("link").takeIf { it.isNotBlank() }
                } else null
            } catch (e: Exception) {
                null
            }

            // Trigger in-app banner on the main thread for Compose visibility
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                AllAccessBannerState.show(title, body, link)
            }
        }
    }

    private fun createMigrationChannel(app: Application) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = app.getSystemService(NotificationManager::class.java)
            if (notificationManager != null) {
                // allaccess_notifications_v2 is defined in AllAccessNotificationFactory
                val channel = NotificationChannel(
                    AllAccessNotificationFactory.CHANNEL_ID,
                    AllAccessNotificationFactory.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "PushNotifications: Migration channel created/verified: ${AllAccessNotificationFactory.CHANNEL_ID}")
            }
        }
    }

    private fun startRegistrationFlow() {
        // 1. Firebase Installations ID (Diagnostic Only)
        // We do NOT delete or reset the FID.
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "FirebaseInstallations: ID obtained: ${task.result}")
            } else {
                Log.w(TAG, "FirebaseInstallations: Could not obtain ID: ${task.exception?.message}")
            }
        }

        // 2. Firebase Installations Auth Token
        // This often fails with FIS_AUTH_ERROR if the service is unavailable.
        FirebaseInstallations.getInstance().getToken(false).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result?.token
                Log.d(TAG, "FirebaseInstallations: Auth Token obtained. Length: ${token?.length ?: 0}")
            } else {
                // This is a known failure point (FIS_AUTH_ERROR). Log and continue.
                Log.w(TAG, "FirebaseInstallations: Auth Token request failed: ${task.exception?.message}")
            }
        }

        // 3. Register for push notifications via Pushwoosh.
        // Pushwoosh handles its own internal retries and Firebase dependency.
        try {
            Pushwoosh.getInstance().registerForPushNotifications()
            val pwToken = Pushwoosh.getInstance().pushToken
            Log.d(TAG, "Pushwoosh: Registration triggered. Immediate token present: ${!pwToken.isNullOrBlank()}")
        } catch (e: Exception) {
            Log.w(TAG, "Pushwoosh: registration call failed: ${e.message}")
        }

        // 4. Explicit FCM check for verification
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d(TAG, "FirebaseMessaging: FCM token obtained. Length: ${token?.length ?: 0}")
            } else {
                Log.w(TAG, "FirebaseMessaging: Failed to obtain FCM token: ${task.exception?.message}")
            }
        }
    }
}
