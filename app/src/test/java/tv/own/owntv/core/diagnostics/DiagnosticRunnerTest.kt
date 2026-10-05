package tv.own.owntv.core.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.diagnostics.DiagnosticTestFacts.healthy
import java.io.IOException

class DiagnosticRunnerTest {

    /** Fake probes returning [healthy]; individual probes can be overridden to throw or hang. */
    private open class FakeProbes : DiagnosticProbes {
        val calls = mutableListOf<DiagCheck>()
        override suspend fun internet() = healthy.internet!!.also { calls += DiagCheck.INTERNET }
        override suspend fun service() = healthy.service!!.also { calls += DiagCheck.SERVICE }
        override suspend fun playlist() = healthy.playlist!!.also { calls += DiagCheck.PLAYLIST }
        override suspend fun epg() = healthy.epg!!.also { calls += DiagCheck.EPG }
        override suspend fun sports() = healthy.sports!!.also { calls += DiagCheck.SPORTS }
        override suspend fun player() = healthy.player!!.also { calls += DiagCheck.PLAYER }
        override suspend fun device() = healthy.device!!.also { calls += DiagCheck.DEVICE }
        override suspend fun update() = healthy.update!!.also { calls += DiagCheck.APP_VERSION }
    }

    @Test
    fun `runs every probe in order, reports CHECKING for each, and finishes all PASS`() = runBlocking {
        val probes = FakeProbes()
        val seen = mutableListOf<DiagnosticProgress>()
        val done = DiagnosticRunner(probes).run(DiagnosticTestFacts.app) { seen += it }

        val order = listOf(
            DiagCheck.INTERNET, DiagCheck.SERVICE, DiagCheck.PLAYLIST, DiagCheck.EPG,
            DiagCheck.SPORTS, DiagCheck.PLAYER, DiagCheck.DEVICE, DiagCheck.APP_VERSION,
        )
        assertEquals(order, probes.calls)
        assertEquals(order + listOf<DiagCheck?>(null), seen.map { it.current })
        assertNull(done.current)
        assertEquals(healthy, done.facts)
        assertTrue(DiagnosticRunner.results(done).all { it.status == DiagStatus.PASS })
    }

    @Test
    fun `progress marks the running check CHECKING and the playlist step spins live TV and VOD too`() = runBlocking {
        val snapshots = mutableListOf<List<CheckResult>>()
        DiagnosticRunner(FakeProbes()).run(DiagnosticTestFacts.app) { snapshots += DiagnosticRunner.results(it) }
        val atPlaylist = snapshots[2].filter { it.status == DiagStatus.CHECKING }.map { it.check }.toSet()
        assertEquals(setOf(DiagCheck.PLAYLIST, DiagCheck.LIVE_TV, DiagCheck.VOD), atPlaylist)
        val atInternet = snapshots[0].filter { it.status == DiagStatus.CHECKING }.map { it.check }
        assertEquals(listOf(DiagCheck.INTERNET), atInternet)
    }

    @Test
    fun `a throwing probe fails only its own check and the run continues`() = runBlocking {
        val probes = object : FakeProbes() {
            override suspend fun epg(): EpgFact = throw IOException("db locked")
        }
        val done = DiagnosticRunner(probes).run(DiagnosticTestFacts.app) {}
        val results = DiagnosticRunner.results(done).associate { it.check to it.status }
        assertEquals(DiagStatus.FAILED, results[DiagCheck.EPG])
        assertEquals(DiagStatus.PASS, results[DiagCheck.SPORTS])
        assertEquals(DiagStatus.PASS, results[DiagCheck.APP_VERSION])
        assertTrue(DiagCheck.APP_VERSION in probes.calls)
    }

    @Test
    fun `a hung probe times out as FAILED instead of freezing the run`() = runBlocking {
        val probes = object : FakeProbes() {
            override suspend fun sports(): SportsFact = awaitCancellation()
        }
        val done = withTimeout(5_000) { DiagnosticRunner(probes, probeTimeoutMs = 50).run(DiagnosticTestFacts.app) {} }
        val results = DiagnosticRunner.results(done).associate { it.check to it.status }
        assertEquals(DiagStatus.FAILED, results[DiagCheck.SPORTS])
        assertEquals(DiagStatus.PASS, results[DiagCheck.PLAYER])
    }

    @Test
    fun `cancelling (Back) stops the run - later probes never start`() = runBlocking {
        val reachedSports = CompletableDeferred<Unit>()
        val probes = object : FakeProbes() {
            override suspend fun sports(): SportsFact {
                reachedSports.complete(Unit)
                awaitCancellation()
            }
        }
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            DiagnosticRunner(probes).run(DiagnosticTestFacts.app) {}
        }
        reachedSports.await()
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertFalse(DiagCheck.PLAYER in probes.calls)
        assertFalse(DiagCheck.APP_VERSION in probes.calls)
    }
}
