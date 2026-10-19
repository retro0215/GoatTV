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
     * After an automatic attempt for a source, the next one waits at least this long. A refresh that
     * succeeds moves lastSyncAt and is not due again anyway; this bounds a source that keeps failing (an
     * expired account, a feed that breaks mid-file) to one large download an hour instead of one per
     * return to the app. The worker's own retries for network errors are unaffected.
     */
    const val ATTEMPT_SPACING_MS: Long = 60L * 60 * 1000

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
