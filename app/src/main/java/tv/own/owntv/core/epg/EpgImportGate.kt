package tv.own.owntv.core.epg

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One guide import at a time, app-wide.
 *
 * Imports arrive from several places — the background worker, onboarding and Settings → Playlists (which
 * import directly), the one-time playlist migration and the Guide's refresh. Two at once would write the
 * same source's rows and cache file together and double the memory a large guide needs on a TV box, so
 * each waits for the one in progress instead.
 */
class EpgImportGate {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** As [exclusive], but asks [stillNeeded] once it has the turn; null when it said no. */
    suspend fun <T : Any> exclusiveIfStillNeeded(stillNeeded: suspend () -> Boolean, block: suspend () -> T): T? =
        mutex.withLock { if (stillNeeded()) block() else null }
}
