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

    /** What [tryExclusiveIfStillNeeded] did. */
    sealed interface Turn<out T> {
        /** Another import holds the gate; nothing was run and nothing was waited for. */
        data object Busy : Turn<Nothing>
        /** The gate was free but [stillNeeded] said no (refreshed meanwhile). */
        data object NotNeeded : Turn<Nothing>
        data class Ran<T>(val value: T) : Turn<T>
    }

    /**
     * As [exclusiveIfStillNeeded], but never waits: when another import holds the gate it returns [Turn.Busy]
     * at once. For the background worker (4.6.1, Shield): Android stops a job after about ten minutes, and
     * an automatic refresh that spent four of them queued behind the first-login import was stopped mid-way
     * and started over. Deferring the job instead keeps its whole window for the import.
     */
    suspend fun <T : Any> tryExclusiveIfStillNeeded(
        stillNeeded: suspend () -> Boolean,
        onSuccess: suspend (T) -> Unit = {},
        block: suspend () -> T,
    ): Turn<T> {
        if (!mutex.tryLock()) return Turn.Busy
        try {
            if (!stillNeeded()) return Turn.NotNeeded
            return Turn.Ran(block().also { onSuccess(it) })
        } finally {
            mutex.unlock()
        }
    }
}
