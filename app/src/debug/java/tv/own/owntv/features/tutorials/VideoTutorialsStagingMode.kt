package tv.own.owntv.features.tutorials

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * DEBUG BUILDS ONLY. Points Settings → Video Tutorials at the staging catalog for testing:
 *
 *   adb shell am start -n <appId>/tv.own.owntv.features.tutorials.VideoTutorialsStagingActivity --ez enabled true
 *   adb shell am start -n <appId>/tv.own.owntv.features.tutorials.VideoTutorialsStagingActivity --ez enabled false
 *
 * Requires the staging URL / anon key to have been supplied at build time (out-of-repo properties,
 * see app/build.gradle.kts); without them, or when off, the debug build reads production like a
 * release build. Nothing here exists in a release APK (debug source set + debug manifest), and
 * [VideoTutorialsEndpoints.resolve] refuses staging outside BuildConfig.DEBUG as well.
 */
internal object VideoTutorialsStagingPrefs {
    private const val FILE = "video_tutorials_staging"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).commit()
    }
}

/** adb switch: writes the flag and finishes immediately (no UI). Off by default. */
class VideoTutorialsStagingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enabled = intent?.getBooleanExtra("enabled", false) ?: false
        VideoTutorialsStagingPrefs.setEnabled(this, enabled)
        val endpoint = VideoTutorialsEndpoints.current()
        android.util.Log.i("VideoTutorials", "staging switch " + (if (enabled) "ON" else "OFF") + "; catalog = " + endpoint.cacheScope)
        finish()
    }
}

/** Installs the switch at process start (debug manifest only); the flag is read on each request. */
class VideoTutorialsStagingInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        VideoTutorialsEndpoints.stagingSwitch = { VideoTutorialsStagingPrefs.enabled(app) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
