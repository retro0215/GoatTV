package tv.own.owntv.core.sync.work

import android.app.Notification
import android.content.pm.ServiceInfo
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.6.1: guide imports run as a foreground job ("Updating TV Guide") so Android does not stop them at the end
 * of a regular job's ten-minute window. Android 7 to 16 differ in what that takes; a refusal (Android 12+
 * while the app is in the background) must leave the import running as a regular job, never claimed as
 * foreground, and with the restart budget still in force.
 */
class GuideForegroundTest {

    private val notification = mockk<Notification>(relaxed = true)

    @Test fun `Android 10 and later are told the dataSync service type the manifest declares`() {
        for (sdk in listOf(29, 31, 34, 35, 36)) {
            val info = GuideSyncNotifications.foregroundInfo(notification, sdk)
            assertEquals("sdk $sdk", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        }
    }

    @Test fun `Android 7 to 9 use the untyped form`() {
        for (sdk in listOf(24, 25, 26, 28)) {
            assertEquals("sdk $sdk", 0, GuideSyncNotifications.foregroundInfo(notification, sdk).foregroundServiceType)
        }
    }

    @Test fun `its own notification id, apart from the downloads notification`() {
        val info = GuideSyncNotifications.foregroundInfo(notification, 34)
        assertEquals(4202, info.notificationId)
        assertTrue(info.notification === notification)
    }

    @Test fun `a channel is created from Android 8 only`() {
        val notifications = java.io.File("src/main/java/tv/own/owntv/core/sync/work/GuideSyncNotifications.kt").readText()
        val ensure = notifications.substringAfter("private fun ensureChannel(")
        assertTrue(ensure.indexOf("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return") in 1 until ensure.indexOf("createNotificationChannel"))
    }

    // ---- Worker wiring (WorkManager is not run in a JVM test) ----

    private val worker by lazy {
        java.io.File("src/main/java/tv/own/owntv/core/sync/work/EpgSyncWorker.kt").readText().replace("\r\n", "\n")
    }
    private val manifest by lazy { java.io.File("src/main/AndroidManifest.xml").readText() }

    @Test fun `a refused foreground start is reported as such and the import goes on`() {
        val promote = worker.substringAfter("private suspend fun runAsForeground(): Boolean = try {").substringBefore("\n    }\n")
        assertTrue("setForeground(GuideSyncNotifications.foregroundInfo(applicationContext, localeStore))\n        true" in promote)
        assertTrue("catch (c: CancellationException) {\n        throw c" in promote)
        assertTrue("catch (e: Exception) {" in promote)
        assertTrue(promote.trimEnd().endsWith("false"))
    }

    @Test fun `the foreground request comes after the run budget and the busy check, before the import`() {
        val budget = worker.indexOf("!EpgAutoRefreshPolicy.automaticRunAllowed(runAttemptCount)")
        val busy = worker.indexOf("if (automatic && epgRepository.isImportRunning()) return deferBusy(source.id, reason)")
        val promote = worker.indexOf("val foreground = runAsForeground()")
        val start = worker.indexOf("activityTracker.started(")
        assertTrue(budget in 1 until busy)
        assertTrue(busy in 1 until promote)
        assertTrue(promote in 1 until start)
        assertTrue("foreground=\$foreground" in worker)
    }

    @Test fun `no new permission or service declaration was needed`() {
        assertTrue("android.permission.FOREGROUND_SERVICE\"" in manifest)
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in manifest)
        assertTrue("android.permission.POST_NOTIFICATIONS" in manifest)
        val service = manifest.substringAfter("androidx.work.impl.foreground.SystemForegroundService").substringBefore("/>")
        assertTrue("android:foregroundServiceType=\"dataSync\"" in service)
    }
}
