package tv.own.owntv.core.epg

import tv.own.owntv.features.settings.data.EpgAutoRefresh

/**
 * When a guide source refreshes on its own.
 *
 * The per-source choice is stored only once someone picks it (the Add/Edit form, or a restored backup). A
 * source with no stored choice — notably the provider guide that onboarding registers — gets [DEFAULT];
 * an explicitly stored choice, Off included, is always kept.
 */
object EpgAutoRefreshPolicy {

    val DEFAULT: EpgAutoRefresh = EpgAutoRefresh.HOURS_24

    /**
     * How often the background check ([tv.own.owntv.core.sync.work.EpgAutoRefreshWorker]) looks for due
     * sources while the app stays open or idle: the shortest interval a source can choose. The check itself
     * only reads settings; a download starts only for a source that is due.
     */
    const val CHECK_INTERVAL_MS: Long = 60L * 60 * 1000

    /**
     * After an automatic attempt for a source, the next one waits at least this long. A refresh that
     * succeeds moves lastSyncAt and is not due again anyway; this bounds a source that keeps failing (an
     * expired account, a feed that breaks mid-file) to about one large download an hour instead of one per
     * return to the app. Shorter than [CHECK_INTERVAL_MS] because periodic work may run a little early.
     */
    const val ATTEMPT_SPACING_MS: Long = 45L * 60 * 1000

    /** Network-error retries of one sync job (WorkManager backoff between them); later errors wait for the next check. */
    const val MAX_RETRY_ATTEMPTS: Int = 3

    /**
     * An automatic refresh that finds the source refreshed this recently does nothing: another import of
     * the same feed (onboarding, a manual re-sync) finished while it was waiting its turn.
     */
    const val RECENTLY_SYNCED_MS: Long = 15L * 60 * 1000

    fun effective(stored: Map<Long, EpgAutoRefresh>, sourceId: Long): EpgAutoRefresh = stored[sourceId] ?: DEFAULT

    /**
     * Whether [mode] makes the source due now. OFF never; STARTUP on a cold start only; an interval once the
     * last successful sync is at least that old (never synced counts as due).
     */
    fun isDue(mode: EpgAutoRefresh, lastSyncAt: Long?, nowMs: Long, coldStart: Boolean): Boolean = when (mode) {
        EpgAutoRefresh.OFF -> false
        EpgAutoRefresh.STARTUP -> coldStart
        else -> (nowMs - (lastSyncAt ?: 0L)) >= (mode.thresholdMs ?: Long.MAX_VALUE)
    }

    /** One guide source as the check sees it. */
    data class SourceState(val id: Long, val lastSyncAt: Long?)

    /**
     * The sources an automatic check should enqueue now, in order. Each one returned has been recorded in
     * [spacing], so a second check moments later (resume right after the periodic run) returns none of them.
     */
    fun dueSources(
        sources: List<SourceState>,
        stored: Map<Long, EpgAutoRefresh>,
        nowMs: Long,
        coldStart: Boolean,
        spacing: AttemptSpacing,
        elapsedMs: Long,
    ): List<Long> = sources.filter { src ->
        isDue(effective(stored, src.id), src.lastSyncAt, nowMs, coldStart) && spacing.tryAcquire(src.id, elapsedMs)
    }.map { it.id }

    /**
     * Whether a failed sync job asks WorkManager to retry: only network trouble, and only [MAX_RETRY_ATTEMPTS]
     * times. A permanent error (bad address, malformed feed) waits for the next due check instead.
     */
    fun shouldRetry(networkError: Boolean, runAttemptCount: Int): Boolean =
        networkError && runAttemptCount < MAX_RETRY_ATTEMPTS

    /** For an automatic run that has just got its turn: false when the source was refreshed meanwhile. */
    fun stillNeeded(lastSyncAt: Long?, nowMs: Long): Boolean =
        lastSyncAt == null || nowMs - lastSyncAt >= RECENTLY_SYNCED_MS

    /** Spaces out automatic attempts per source within one app process (monotonic clock). */
    class AttemptSpacing(private val spacingMs: Long = ATTEMPT_SPACING_MS) {
        private val lastAttemptAt = HashMap<Long, Long>()

        /** Records an attempt and returns true, or returns false if the previous one was too recent. */
        @Synchronized
        fun tryAcquire(sourceId: Long, elapsedMs: Long): Boolean {
            val last = lastAttemptAt[sourceId]
            if (last != null && elapsedMs - last < spacingMs) return false
            lastAttemptAt[sourceId] = elapsedMs
            return true
        }
    }
}
