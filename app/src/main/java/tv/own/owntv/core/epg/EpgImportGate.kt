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
 *
 * [onSuccess] runs while the lock is still held, right after the import finished: the caller records the
 * guide as synced there (with the completion time). A job waiting for its turn therefore always sees that
 * record when its [exclusiveIfStillNeeded] check runs — recording it after the release let a waiting
 * automatic refresh slip in first and download the whole guide again (first login, 4.6.1 RC1). A failed or
 * cancelled import never reaches [onSuccess].
 */
class EpgImportGate {
    private val mutex = Mutex()

    suspend fun <T> exclusive(onSuccess: suspend (T) -> Unit = {}, block: suspend () -> T): T =
        mutex.withLock { block().also { onSuccess(it) } }

    /** As [exclusive], but asks [stillNeeded] once it has the turn; null when it said no. */
    suspend fun <T : Any> exclusiveIfStillNeeded(
        stillNeeded: suspend () -> Boolean,
        onSuccess: suspend (T) -> Unit = {},
        block: suspend () -> T,
    ): T? = mutex.withLock { if (stillNeeded()) block().also { onSuccess(it) } else null }
}
