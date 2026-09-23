package tv.own.owntv.rooms

import android.content.Context
import java.util.UUID

class TvSessionManager(context: Context? = null) {
    private val prefs = context?.getSharedPreferences("owntv_tv_pairing_session", Context.MODE_PRIVATE)

    fun getOrCreateTvSessionId(): String {
        val existing = prefs?.getString("tv_session_uuid", null)
        if (!existing.isNullOrBlank()) return existing

        val newUuid = UUID.randomUUID().toString()
        prefs?.edit()?.putString("tv_session_uuid", newUuid)?.apply()
        return newUuid
    }
}
