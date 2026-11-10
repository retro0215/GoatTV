package tv.own.owntv.core.sync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgAutoRefreshPolicy
import tv.own.owntv.core.epg.EpgImportGate
import tv.own.owntv.core.epg.EpgMigration
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.features.settings.data.EpgAutoRefresh
import tv.own.owntv.features.settings.data.PlaylistAutoRefresh
import java.io.IOException

/**
 * 4.6.1 RC1: on first login the playlist and the TV Guide were each imported twice — Home's start-up check
 * re-synced a playlist the setup wizard had finished seconds earlier, and an automatic guide refresh that
 * queued during the onboarding guide download got the import lock before onboarding recorded success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstLoginDuplicateSyncTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    // ---- Catalog: Home's start-up check ----

    @Test fun `Home right after a successful first-login import does not import again`() {
        assertFalse(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, lastSyncAt = now - 20_000L, nowMs = now, coldStart = true))
    }

    @Test fun `a failed or interrupted import leaves lastSyncAt unset or old, so Home still syncs`() {
        // A failed first import never stamps lastSyncAt.
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, lastSyncAt = null, nowMs = now, coldStart = true))
        // A failed re-sync keeps yesterday's stamp.
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, lastSyncAt = now - 26 * 60 * minute, nowMs = now, coldStart = true))
    }

    @Test fun `a staged first import with Movies or Series still to come is not treated as synced`() {
        // SyncManager stamps lastSyncAt only when a pass covers every enabled section; the remainder job
        // (add_remainder, REPLACE) stamps it when it finishes. Until then the playlist reads as never synced,
        // and the start-up enqueue (KEEP) leaves a queued remainder job in place.
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, lastSyncAt = null, nowMs = now, coldStart = true))
    }

    @Test fun `a later genuine app launch refreshes as On App Start promises`() {
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, lastSyncAt = now - 15 * minute, nowMs = now, coldStart = true))
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, lastSyncAt = now - 8 * 60 * minute, nowMs = now, coldStart = true))
        // Resume never runs STARTUP refreshes.
        assertFalse(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, lastSyncAt = null, nowMs = now, coldStart = false))
    }

    @Test fun `interval and Off modes behave as before`() {
        assertFalse(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.OFF, null, now, coldStart = true))
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.HOURS_6, now - 6 * 60 * minute, now, coldStart = false))
        assertFalse(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.HOURS_6, now - 5 * 60 * minute, now, coldStart = true))
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.HOURS_24, null, now, coldStart = false))
    }

    @Test fun `a clock that went backwards is not trusted as fresh`() {
        assertFalse(SyncFreshness.recentlySynced(now + 5 * minute, now))
        assertTrue(PlaylistAutoRefreshPolicy.isDue(PlaylistAutoRefresh.STARTUP, now + 5 * minute, now, coldStart = true))
    }

    @Test fun `a guide set to refresh on app start skips one refreshed moments ago too`() {
        assertFalse(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.STARTUP, now - minute, now, coldStart = true))
        assertTrue(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.STARTUP, now - 20 * minute, now, coldStart = true))
        assertTrue(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.STARTUP, null, now, coldStart = true))
    }

    // ---- Guide: success recorded inside the import lock ----

    /** Guide store as the import paths see it. Writing suspends first, as the DataStore edit really does. */
    private class FakeStore {
        var lastSyncAt: Long? = null
        var lastError: String? = null
        suspend fun setSynced(at: Long) { yield(); lastSyncAt = at; lastError = null }
    }

    private class Clock(var ms: Long)

    @Test fun `an auto-refresh queued behind the onboarding guide import skips once it gets its turn`() = runTest {
        val gate = EpgImportGate()
        val store = FakeStore()
        val clock = Clock(now)
        val onboardingDownload = CompletableDeferred<Int>()
        var downloads = 0

        // Onboarding: "Sync now" during first login.
        val onboarding = async {
            gate.exclusive(onSuccess = { store.setSynced(clock.ms) }) { downloads++; onboardingDownload.await() }
        }
        runCurrent()
        // Home opens meanwhile and its start-up check queues an automatic refresh: it waits for the lock.
        val auto = async {
            gate.exclusiveIfStillNeeded(
                stillNeeded = { EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, clock.ms) },
                onSuccess = { store.setSynced(clock.ms) },
            ) { downloads++; 1 }
        }
        runCurrent()
        clock.ms = now + 6 * minute // the import took six minutes
        onboardingDownload.complete(644_156)

        assertEquals(644_156, onboarding.await())
        assertNull(auto.await()) // skipped: refreshed meanwhile
        assertEquals(1, downloads)
        assertEquals(now + 6 * minute, store.lastSyncAt) // the completion time, not the start
    }

    @Test fun `a failed guide download records nothing and stays eligible for retry`() = runTest {
        val gate = EpgImportGate()
        val store = FakeStore()
        val failed = runCatching {
            gate.exclusive<Int>(onSuccess = { store.setSynced(now) }) { throw IOException("Unexpected end of ZLIB input stream") }
        }
        assertTrue(failed.isFailure)
        assertNull(store.lastSyncAt)
        assertTrue(EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, now))
        val retried = gate.exclusiveIfStillNeeded({ EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, now) }, { store.setSynced(now) }) { 5 }
        assertEquals(5, retried)
    }

    @Test fun `an import cut off by an app restart records nothing and the next run imports`() = runTest {
        val gate = EpgImportGate()
        val store = FakeStore()
        val never = CompletableDeferred<Int>()
        val interrupted = launch { gate.exclusive(onSuccess = { store.setSynced(now) }) { never.await() } }
        runCurrent()
        interrupted.cancel()
        runCurrent()
        assertNull(store.lastSyncAt)
        val next = gate.exclusiveIfStillNeeded({ EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, now) }, { store.setSynced(now) }) { 7 }
        assertEquals(7, next)
    }

    @Test fun `a manual refresh overlapping an automatic one runs once, and an automatic one behind it skips`() = runTest {
        val gate = EpgImportGate()
        val store = FakeStore()
        val clock = Clock(now)
        val autoDownload = CompletableDeferred<Int>()
        var downloads = 0
        val stillNeeded: suspend () -> Boolean = { EpgAutoRefreshPolicy.stillNeeded(store.lastSyncAt, clock.ms) }

        val firstAuto = async { gate.exclusiveIfStillNeeded(stillNeeded, { store.setSynced(clock.ms) }) { downloads++; autoDownload.await() } }
        runCurrent()
        // The customer asks for a refresh while the automatic one downloads: theirs waits, then runs.
        val manual = async { gate.exclusive(onSuccess = { store.setSynced(clock.ms) }) { downloads++; 2 } }
        runCurrent()
        val secondAuto = async { gate.exclusiveIfStillNeeded(stillNeeded, { store.setSynced(clock.ms) }) { downloads++; 3 } }
        runCurrent()
        clock.ms = now + 4 * minute
        autoDownload.complete(1)

        assertEquals(1, firstAuto.await())
        assertEquals(2, manual.await())
        assertNull(secondAuto.await())
        assertEquals(2, downloads)
    }

    // ---- The callers record success inside the lock ----

    @Test fun `the one-time migration records a success and leaves a failure due`() = runTest {
        val store = mockk<EpgSourceStore>(relaxed = true)
        val sourceDao = mockk<SourceDao>()
        val repo = mockk<EpgRepository>()
        val playlist = SourceEntity(id = 3, name = "Synthetic", type = SourceType.XTREAM, url = "http://panel.invalid")
        coEvery { store.isMigrated() } returns false
        coEvery { store.getAll() } returns emptyList()
        coEvery { store.add(any(), any(), any()) } returns EpgSource(id = -1, name = "Synthetic", url = "http://panel.invalid/xmltv.php")
        coEvery { sourceDao.getAllOnce() } returns listOf(playlist)
        every { repo.guideUrl(playlist) } returns "http://panel.invalid/xmltv.php"

        coEvery { repo.refreshUrl(any(), any(), any(), any(), any()) } coAnswers { arg<suspend (Int) -> Unit>(4).invoke(10); 10 }
        EpgMigration(store, sourceDao, repo).run()
        coVerify(exactly = 1) { store.setSynced(-1, any(), null) }

        val failingStore = mockk<EpgSourceStore>(relaxed = true)
        coEvery { failingStore.isMigrated() } returns false
        coEvery { failingStore.getAll() } returns emptyList()
        coEvery { failingStore.add(any(), any(), any()) } returns EpgSource(id = -1, name = "Synthetic", url = "http://panel.invalid/xmltv.php")
        coEvery { repo.refreshUrl(any(), any(), any(), any(), any()) } throws IOException("synthetic drop")
        EpgMigration(failingStore, sourceDao, repo).run()
        coVerify(exactly = 0) { failingStore.setSynced(any(), any(), any()) }
        coVerify { failingStore.markError(-1, "synthetic drop") }
    }
}
