package tv.own.owntv.core.epg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.epg.EpgImportGate.Turn
import java.io.IOException

/**
 * 4.6.1, Shield: the automatic guide refresh queued behind the first-login import waited four minutes for the
 * import lock inside its own job, so Android stopped it at the end of its ten-minute window, mid-download, and
 * WorkManager started it over from scratch. Automatic jobs now never wait for the lock — they are deferred —
 * and a job that keeps being stopped gives up after a fixed number of starts instead of restarting forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideJobSchedulingTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private class Store(var lastSyncAt: Long? = null)

    // ---- The gate never makes an automatic job wait ----

    @Test fun `a free gate runs the import and records it as synced`() = runTest {
        val gate = EpgImportGate()
        val store = Store()
        val turn = gate.tryExclusiveIfStillNeeded(
            stillNeeded = { EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, now) },
            onSuccess = { store.lastSyncAt = now },
        ) { 503_749 }
        assertEquals(Turn.Ran(503_749), turn)
        assertEquals(now, store.lastSyncAt)
    }

    @Test fun `with another import running the job is told Busy at once and nothing runs`() = runTest {
        val gate = EpgImportGate()
        val onboardingDownload = CompletableDeferred<Int>()
        val onboarding = async { gate.exclusive { onboardingDownload.await() } }
        runCurrent() // onboarding holds the gate

        var asked = false
        var downloaded = false
        val turn = gate.tryExclusiveIfStillNeeded(stillNeeded = { asked = true; true }) { downloaded = true; 1 }

        assertEquals(Turn.Busy, turn)
        assertFalse("the freshness check waits for a real turn", asked)
        assertFalse(downloaded)
        onboardingDownload.complete(7)
        assertEquals(7, onboarding.await())
    }

    @Test fun `the deferred job skips once the import it deferred to has refreshed the source`() = runTest {
        val gate = EpgImportGate()
        val store = Store()
        val clock = longArrayOf(now)
        val onboardingDownload = CompletableDeferred<Int>()
        var downloads = 0
        val onboarding = async {
            gate.exclusive(onSuccess = { store.lastSyncAt = clock[0] }) { downloads++; onboardingDownload.await() }
        }
        runCurrent()
        val stillNeeded: suspend () -> Boolean = { EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, clock[0]) }

        assertEquals(Turn.Busy, gate.tryExclusiveIfStillNeeded(stillNeeded) { downloads++; 1 })
        clock[0] = now + 5 * minute
        onboardingDownload.complete(644_156)
        onboarding.await()

        // WorkManager's retry, after its backoff
        assertEquals(Turn.NotNeeded, gate.tryExclusiveIfStillNeeded(stillNeeded) { downloads++; 1 })
        assertEquals("no duplicate import", 1, downloads)
    }

    @Test fun `a deferred job behind an import that was interrupted still imports`() = runTest {
        val gate = EpgImportGate()
        val store = Store()
        val wizard = async {
            runCatching { gate.exclusive(onSuccess = { store.lastSyncAt = now }) { throw IOException("app closed") } }
        }
        wizard.await()
        assertNull("an interrupted import records nothing", store.lastSyncAt)

        val turn = gate.tryExclusiveIfStillNeeded(
            stillNeeded = { EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, now) },
            onSuccess = { store.lastSyncAt = now },
        ) { 503_749 }
        assertEquals(Turn.Ran(503_749), turn)
    }

    @Test fun `a failed import records nothing and frees the gate for the retry`() = runTest {
        val gate = EpgImportGate()
        val store = Store()
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                gate.tryExclusiveIfStillNeeded(stillNeeded = { true }, onSuccess = { store.lastSyncAt = now }) {
                    throw IOException("connection reset")
                }
            }
        }
        assertNull(store.lastSyncAt)
        assertEquals(Turn.Ran(1), gate.tryExclusiveIfStillNeeded(stillNeeded = { true }) { 1 })
    }

    // ---- Run budget: no endless restarts after Android stops the job ----

    @Test fun `an automatic job may start six times in all`() {
        (0 until EpgAutoRefreshPolicy.MAX_AUTOMATIC_RUNS).forEach { assertTrue(EpgAutoRefreshPolicy.automaticRunAllowed(it)) }
        assertFalse(EpgAutoRefreshPolicy.automaticRunAllowed(EpgAutoRefreshPolicy.MAX_AUTOMATIC_RUNS))
        assertFalse(EpgAutoRefreshPolicy.automaticRunAllowed(50))
    }

    @Test fun `a guide that is always stopped at the end of the window is downloaded at most six times`() {
        // WorkManager counts every start (0 on the first), restarts after a stop included.
        var downloads = 0
        var run = 0
        while (EpgAutoRefreshPolicy.automaticRunAllowed(run)) {
            downloads++ // ... and Android stops the job before it finishes
            run++
        }
        assertEquals(6, downloads)
    }

    @Test fun `a job that keeps finding the gate busy stops deferring at the same limit`() {
        // Busy on run n: deferred only if run n+1 is still allowed.
        val deferrals = (0 until 20).takeWhile { EpgAutoRefreshPolicy.automaticRunAllowed(it + 1) }.count()
        assertEquals(5, deferrals)
    }

    @Test fun `network retries keep their own, smaller limit`() {
        assertTrue(EpgAutoRefreshPolicy.shouldRetry(networkError = true, runAttemptCount = 0))
        assertTrue(EpgAutoRefreshPolicy.shouldRetry(networkError = true, runAttemptCount = 2))
        assertFalse(EpgAutoRefreshPolicy.shouldRetry(networkError = true, runAttemptCount = 3))
        assertFalse(EpgAutoRefreshPolicy.shouldRetry(networkError = false, runAttemptCount = 0))
    }

    // ---- Worker wiring (WorkManager is not run in a JVM test) ----

    private val worker by lazy {
        java.io.File("src/main/java/tv/own/owntv/core/sync/work/EpgSyncWorker.kt").readText().replace("\r\n", "\n")
    }

    @Test fun `the worker checks its run budget before starting an automatic import`() {
        val budget = worker.indexOf("!EpgAutoRefreshPolicy.automaticRunAllowed(runAttemptCount)")
        assertTrue(budget > 0)
        assertTrue("before the pill and the download", budget < worker.indexOf("activityTracker.started("))
    }

    @Test fun `automatic imports never wait for the gate and Busy defers within the budget`() {
        assertTrue("epgRepository.tryRefreshUrlIfStillNeeded(" in worker)
        assertFalse("refreshUrlIfStillNeeded(" in worker.replace("tryRefreshUrlIfStillNeeded(", ""))
        val busy = worker.substringAfter("EpgImportGate.Turn.Busy -> {").substringBefore("}\n")
        assertTrue("EpgAutoRefreshPolicy.automaticRunAllowed(runAttemptCount + 1)" in busy)
        assertTrue("Result.retry()" in busy)
    }

    @Test fun `manual and onboarding imports still wait for their turn`() {
        assertTrue("epgRepository.refreshUrl(source.id, source.url, source.userAgent, onProgress, onSuccess = recordSynced)" in worker)
    }

    @Test fun `the stop reason is only read where Android provides it`() {
        assertTrue("if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else -1" in worker)
    }
}
