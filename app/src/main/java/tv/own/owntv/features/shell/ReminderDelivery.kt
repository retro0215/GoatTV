package tv.own.owntv.features.shell

/**
 * Phase 4: what the app does when a programme reminder fires, wherever the customer is.
 *
 * Until 4.6 the only listener lived inside the full-screen player, and the reminder events are not
 * replayed: a reminder that fired on Home, in the Guide or in Settings was lost. The shell now listens
 * once, while the app is in the foreground, and asks these rules what to do.
 *
 * The channel to tune comes from [tv.own.owntv.core.sync.work.ProgramReminders.tuneChannelId], which is
 * null for another profile's reminder, a reminder on another playlist, or a channel that is missing (an
 * ambiguous re-link never changes a reminder's channel). Null never tunes.
 *
 * Pure rules, so they can be tested without Compose.
 */
internal object ReminderDelivery {

    /** Where the customer is when a reminder fires. */
    enum class Place { PLAYER, BROWSE, MULTISCREEN }

    fun place(isMultiscreen: Boolean, playerShown: Boolean): Place = when {
        isMultiscreen -> Place.MULTISCREEN
        playerShown -> Place.PLAYER
        else -> Place.BROWSE
    }

    /** The 60-second warning: a banner with Watch / Dismiss, for the active profile's reminders only. */
    fun showWarning(belongsToActiveProfile: Boolean): Boolean = belongsToActiveProfile

    sealed interface Start {
        /** Tune [channelId] full screen now. */
        data class Tune(val channelId: Long) : Start
        /** Multiscreen: say it is starting; leaving the layout needs Watch. */
        data class Confirm(val channelId: Long) : Start
        data object Ignore : Start
    }

    /**
     * The programme starts. [channelId] is the safe tune target (null = do nothing); [alreadyHandled] is
     * true when this reminder was already tuned or watched (a duplicate event).
     */
    fun onStart(place: Place, channelId: Long?, alreadyHandled: Boolean): Start = when {
        channelId == null || alreadyHandled -> Start.Ignore
        place == Place.MULTISCREEN -> Start.Confirm(channelId)
        else -> Start.Tune(channelId)
    }

    /**
     * Reminders already acted on (tuned, or watched from the banner), so a repeated event or the start
     * after a Watch never tunes twice. Bounded; a reminder is keyed by its channel and start, which stay
     * the same across the warning and start events (the row id would too, but the start worker deletes
     * the row as it fires).
     */
    class Handled(private val capacity: Int = 64) {
        private val keys = LinkedHashSet<Pair<Long, Long>>()

        /** Marks the reminder handled; false if it already was. */
        fun claim(channelId: Long, programStartMs: Long): Boolean {
            val key = channelId to programStartMs
            if (!keys.add(key)) return false
            while (keys.size > capacity) keys.remove(keys.first())
            return true
        }

        fun contains(channelId: Long, programStartMs: Long): Boolean = (channelId to programStartMs) in keys
    }
}
