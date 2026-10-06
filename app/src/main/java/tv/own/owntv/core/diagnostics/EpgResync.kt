package tv.own.owntv.core.diagnostics

import tv.own.owntv.core.sync.work.EpgSyncState

/**
 * Re-sync EPG from the Diagnostic Center. The sync itself belongs to WorkManager (the existing
 * production EpgSyncWorker), never to the screen: leaving the Diagnostic Center or the app does not
 * cancel it, and coming back simply re-reads WorkManager's state. These rules keep a second press from
 * starting — or, with WorkManager's REPLACE policy, restarting — a sync that is already running.
 */
object EpgResync {

    enum class Decision { START, ALREADY_RUNNING, NO_SOURCES }

    /** Live state for the button: null = idle. [programmes] is a real running count, never a percentage. */
    data class Progress(val programmes: Int)

    fun decide(sourceCount: Int, progress: Progress?): Decision = when {
        sourceCount == 0 -> Decision.NO_SOURCES
        progress != null -> Decision.ALREADY_RUNNING
        else -> Decision.START
    }

    /** Combines each EPG source's WorkManager state into one "in progress" value (null when all idle). */
    fun progressOf(states: List<EpgSyncState>): Progress? {
        val syncing = states.filterIsInstance<EpgSyncState.Syncing>()
        if (syncing.isEmpty()) return null
        return Progress(programmes = syncing.sumOf { it.programmes })
    }
}
