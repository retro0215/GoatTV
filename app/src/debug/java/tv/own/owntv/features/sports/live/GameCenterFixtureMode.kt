package tv.own.owntv.features.sports.live

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * DEBUG BUILDS ONLY. Game Center fixture mode for physical inspection on a TV:
 *
 *   adb shell am start -n <appId>/tv.own.owntv.features.sports.live.GameCenterFixturesActivity --ez enabled true
 *   adb shell am start -n <appId>/tv.own.owntv.features.sports.live.GameCenterFixturesActivity --ez enabled false
 *
 * then reopen Sports. While on, [GameCenterFixtureCatalog] events lead Popular Events and answer
 * Game Center detail requests. Nothing here exists in a release APK (debug source set), and
 * [SportsGameCenterDevMode.active] also refuses outside BuildConfig.DEBUG.
 */
internal object GameCenterFixturePrefs {
    private const val FILE = "sports_game_center_fixtures"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).commit()
    }
}

/** adb switch: writes the flag and finishes immediately (no UI). */
class GameCenterFixturesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enabled = intent?.getBooleanExtra("enabled", true) ?: true
        GameCenterFixturePrefs.setEnabled(this, enabled)
        android.util.Log.i("SportsGameCenter", "fixture mode " + (if (enabled) "ON" else "OFF"))
        finish()
    }
}

/** Installs the fixture hook at process start (debug manifest only); reads the flag on each use. */
class GameCenterFixtureInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        SportsGameCenterDevMode.installed = { if (GameCenterFixturePrefs.enabled(app)) GameCenterFixtureCatalog else null }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
