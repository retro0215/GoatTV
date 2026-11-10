package tv.own.owntv.core.sync

import tv.own.owntv.features.settings.data.PlaylistAutoRefresh

/**
 * "Was this just synced?" — one answer for playlists and guides alike.
 *
 * A source whose last *successful* sync finished within [RECENTLY_SYNCED_MS] is fresh: an automatic
 * refresh started now would download and compare the same data again. The typical case is first login,
 * where the setup wizard's own import finishes seconds before Home's start-up check runs in the same
 * app session. Only successes move `lastSyncAt` (a failed, interrupted or staged-partial sync leaves it
 * where it was), so nothing that still needs work is ever treated as fresh.
 */
object SyncFreshness {

    const val RECENTLY_SYNCED_MS: Long = 15L * 60 * 1000

    /** True when [lastSyncAt] is a success less than [RECENTLY_SYNCED_MS] before [nowMs]. A clock that went
     *  backwards (lastSyncAt in the future) is not trusted as fresh. */
    fun recentlySynced(lastSyncAt: Long?, nowMs: Long): Boolean {
        if (lastSyncAt == null) return false
        val age = nowMs - lastSyncAt
        return age in 0 until RECENTLY_SYNCED_MS
    }
}

/** When a playlist refreshes on its own (Home's start-up/resume check). */
object PlaylistAutoRefreshPolicy {

    /**
     * Whether [mode] makes the playlist due now. OFF never. On app start ([PlaylistAutoRefresh.STARTUP]) on
     * the first check of an app session ([coldStart]), unless the playlist finished a successful sync moments
     * ago ([SyncFreshness]) — on first login that sync is the setup wizard's own import. Intervals once the
     * last successful sync is at least that old; never synced counts as due.
     */
    fun isDue(mode: PlaylistAutoRefresh, lastSyncAt: Long?, nowMs: Long, coldStart: Boolean): Boolean = when (mode) {
        PlaylistAutoRefresh.OFF -> false
        PlaylistAutoRefresh.STARTUP -> coldStart && !SyncFreshness.recentlySynced(lastSyncAt, nowMs)
        else -> (nowMs - (lastSyncAt ?: 0L)) >= (mode.thresholdMs ?: Long.MAX_VALUE)
    }
}
