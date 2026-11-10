package tv.own.owntv.features.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.EpgRepository
import java.io.IOException

/**
 * The guide download shared by onboarding and Settings → Playlists. A failed (or interrupted) first
 * download must leave lastSyncAt unset, so the automatic refresh still treats the guide as due and
 * fetches it. Synthetic playlists and hosts only.
 */
class EpgSemiAutoSyncTest {

    private val source = SourceEntity(id = 7, name = "Synthetic Playlist", type = SourceType.XTREAM, url = "http://panel.invalid")
    private val guideUrl = "http://panel.invalid/xmltv.php"
    private val registered = EpgSource(id = -3, name = "Synthetic Playlist", url = guideUrl)

    private val epgRepository = mockk<EpgRepository>(relaxed = true)
    private val store = mockk<EpgSourceStore>(relaxed = true).also {
        coEvery { it.getAll() } returns emptyList()
        coEvery { it.add(any(), any(), any()) } returns registered
    }

    private fun run(): List<EpgSyncUi> {
        val states = ArrayList<EpgSyncUi>()
        runBlocking { runSemiAutoEpgSync(source, epgRepository, store) { states += it } }
        return states
    }

    @Test
    fun `a finished download is recorded as synced`() {
        every { epgRepository.guideUrl(source) } returns guideUrl
        // As the real import lock does: the success callback runs before refreshUrl returns.
        coEvery { epgRepository.refreshUrl(any(), any(), any(), any(), any()) } coAnswers { arg<suspend (Int) -> Unit>(4).invoke(900); 900 }
        val states = run()
        assertEquals(EpgSyncUi.Done, states.last())
        coVerify { store.setSynced(registered.id, any(), null) }
        coVerify(exactly = 0) { store.markError(any(), any()) }
    }

    @Test
    fun `success is recorded inside the import lock, with the completion time, not after it is released`() {
        every { epgRepository.guideUrl(source) } returns guideUrl
        val events = ArrayList<String>()
        coEvery { store.setSynced(any(), any(), any()) } answers { events += "stamped" }
        coEvery { epgRepository.refreshUrl(any(), any(), any(), any(), any()) } coAnswers {
            events += "imported"
            arg<suspend (Int) -> Unit>(4).invoke(900) // still holding the lock here
            events += "lock released"
            900
        }
        val before = System.currentTimeMillis()
        run()
        assertEquals(listOf("imported", "stamped", "lock released"), events)
        coVerify { store.setSynced(registered.id, match { it >= before }, null) }
    }

    @Test
    fun `a failed download keeps the guide due for the automatic refresh`() {
        every { epgRepository.guideUrl(source) } returns guideUrl
        coEvery { epgRepository.refreshUrl(any(), any(), any(), any(), any()) } throws IOException("Unexpected end of ZLIB input stream")
        val states = run()
        assertTrue(states.last() is EpgSyncUi.Failed)
        coVerify { store.markError(registered.id, "Unexpected end of ZLIB input stream") }
        coVerify(exactly = 0) { store.setSynced(any(), any(), any()) }
    }

    @Test
    fun `the guide feed is registered before the download, so an interrupted one is retried later`() {
        every { epgRepository.guideUrl(source) } returns guideUrl
        coEvery { epgRepository.refreshUrl(any(), any(), any(), any(), any()) } throws IOException("synthetic drop")
        run()
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            store.add("Synthetic Playlist", guideUrl, any())
            epgRepository.refreshUrl(registered.id, guideUrl, any(), any(), any())
        }
    }

    @Test
    fun `an already registered feed is reused, not added twice`() {
        every { epgRepository.guideUrl(source) } returns guideUrl
        coEvery { store.getAll() } returns listOf(registered)
        coEvery { epgRepository.refreshUrl(any(), any(), any(), any(), any()) } returns 1
        run()
        coVerify(exactly = 0) { store.add(any(), any(), any()) }
    }

    @Test
    fun `no guide feed means nothing to download`() {
        every { epgRepository.guideUrl(source) } returns null
        assertEquals(listOf<EpgSyncUi>(EpgSyncUi.Done), run())
        coVerify(exactly = 0) { store.add(any(), any(), any()) }
    }
}
