package tv.own.owntv.features.setup

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncCounts
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.features.settings.EpgSyncUi
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.features.setup.SetupViewModel.ImportState

/**
 * First-login playlist import and its TV Guide question. "Run in background" used to be offered from the
 * first second of the import; taking it skipped the guide question for good and the Guide stayed empty.
 * Synthetic playlists and hosts only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupFirstLoginSyncTest {

    private val dispatcher = StandardTestDispatcher()

    private val source = SourceEntity(
        id = 7, name = "Synthetic Playlist", type = SourceType.XTREAM,
        url = "http://panel.invalid", username = "synthUser42", password = "synthPass99",
    )
    private val guideUrl = "http://panel.invalid/xmltv.php"
    private val guideSource = EpgSource(id = -3, name = "Synthetic Playlist", url = guideUrl)

    private val profileDao = mockk<ProfileDao>(relaxed = true)
    private val sourceDao = mockk<SourceDao>(relaxed = true)
    private val sourceRepository = mockk<SourceRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityObserver>(relaxed = true)
    private val finalizer = mockk<ImportFinalizer>(relaxed = true)
    private val epgRepository = mockk<EpgRepository>(relaxed = true)
    private val epgStore = mockk<EpgSourceStore>(relaxed = true)

    /** The playlist import, held open until a test completes it. */
    private var playlistSync = CompletableDeferred<SyncResult>()

    /** The guide download, held open until a test completes it. */
    private var guideDownload = CompletableDeferred<Int>()

    /** The persisted active profile (-1 = setup never finished) and the first-run import marker. */
    private val activeProfile = MutableStateFlow(-1L)
    private val importedMarker = MutableStateFlow(-1L)

    private lateinit var vm: SetupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { connectivity.isOnlineNow() } returns true
        coEvery { profileDao.insert(any()) } returns 1L
        every { settings.activeProfileId } returns activeProfile
        coEvery { settings.setActiveProfile(any()) } answers { activeProfile.value = firstArg() }
        every { settings.onboardingImportedSourceId } returns importedMarker
        coEvery { settings.setOnboardingImportedSource(any()) } answers { importedMarker.value = firstArg() }
        coEvery {
            sourceRepository.addXtreamSource(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns source
        coEvery { sourceRepository.sync(any(), any(), any(), any()) } coAnswers { playlistSync.await() }
        coEvery { sourceDao.getById(source.id) } returns source
        coEvery { sourceDao.getAllOnce() } returns listOf(source)
        coEvery { finalizer.finalize(any(), any()) } returns SyncCounts(120, 0, 0)
        every { epgRepository.guideUrl(any()) } returns guideUrl
        coEvery { epgStore.getAll() } returns emptyList()
        coEvery { epgStore.add(any(), any(), any()) } returns guideSource
        coEvery { epgRepository.refreshUrl(any(), any(), any(), any()) } coAnswers { guideDownload.await() }

        vm = SetupViewModel(
            profileDao, sourceDao, sourceRepository, mockk(relaxed = true), settings, connectivity, finalizer,
            epgRepository, epgStore, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun startPlaylist() = vm.startXtream("Synthetic Playlist", "http://panel.invalid", "synthUser42", "synthPass99")

    /** Starts a new playlist and lets its import finish successfully. */
    private fun TestScope.importPlaylist() {
        startPlaylist()
        advanceUntilIdle()
        playlistSync.complete(SyncResult.Success())
        advanceUntilIdle()
    }

    private class Entered { var profile: Long? = null; var calls = 0 }

    private fun Entered.callback(): (Long?) -> Unit = { profile = it; calls++ }

    @Test
    fun `Run in background is hidden while a new playlist imports, and refused if called`() = runTest(dispatcher) {
        startPlaylist()
        advanceUntilIdle()
        assertEquals(ImportState.Running, vm.state.value)
        assertFalse(vm.runInBackgroundAvailable.value)

        val entered = Entered()
        vm.continueInBackground(entered.callback())
        advanceUntilIdle()
        assertEquals("the wizard was not left", 0, entered.calls)
        coVerify(exactly = 0) { settings.setActiveProfile(any()) }

        // The import then finishes and the guide question is still asked.
        playlistSync.complete(SyncResult.Success())
        advanceUntilIdle()
        assertTrue(vm.state.value is ImportState.Success)
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), vm.epgSync.value)
    }

    @Test
    fun `accepted guide downloads, and keeps downloading after Run in background`() = runTest(dispatcher) {
        importPlaylist()
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), vm.epgSync.value)

        vm.syncPendingEpg()
        advanceUntilIdle()
        assertEquals(EpgSyncUi.Syncing(0), vm.epgSync.value)

        // The guide dialog's "Run in background": enter the app now, without waiting for the download.
        val entered = Entered()
        vm.syncEpgInBackground(entered.callback())
        advanceUntilIdle()
        assertEquals(1, entered.calls)
        assertEquals(1L, entered.profile)
        coVerify { settings.setActiveProfile(1L) }

        // The download was not cancelled by leaving: it finishes and is recorded as synced.
        guideDownload.complete(5_000)
        advanceUntilIdle()
        assertEquals(EpgSyncUi.Done, vm.epgSync.value)
        coVerify(exactly = 1) { epgRepository.refreshUrl(guideSource.id, guideUrl, any(), any()) }
        coVerify { epgStore.setSynced(guideSource.id, any(), null) }
    }

    @Test
    fun `a second Sync tap does not start a second guide download`() = runTest(dispatcher) {
        importPlaylist()
        vm.syncPendingEpg()
        vm.syncPendingEpg()
        advanceUntilIdle()
        vm.syncPendingEpg()
        advanceUntilIdle()
        guideDownload.complete(10)
        advanceUntilIdle()
        coVerify(exactly = 1) { epgRepository.refreshUrl(any(), any(), any(), any()) }
        coVerify(exactly = 1) { epgStore.add(any(), any(), any()) }
    }

    @Test
    fun `declined guide is kept declined and onboarding continues`() = runTest(dispatcher) {
        importPlaylist()
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), vm.epgSync.value)

        vm.dismissPendingEpg() // "Not now"
        vm.syncPendingEpg() // nothing pending any more
        advanceUntilIdle()
        assertEquals(EpgSyncUi.Hidden, vm.epgSync.value)
        coVerify(exactly = 0) { epgStore.add(any(), any(), any()) }
        coVerify(exactly = 0) { epgRepository.refreshUrl(any(), any(), any(), any()) }

        // Continue enters the app; the playlist stays, nothing was cleaned up.
        val entered = Entered()
        vm.finish(entered.callback())
        advanceUntilIdle()
        assertEquals(1L, entered.profile)
        coVerify(exactly = 0) { sourceRepository.deleteSource(any()) }
    }

    @Test
    fun `the guide question is asked exactly once per import`() = runTest(dispatcher) {
        importPlaylist()
        val asked = vm.epgSync.value
        advanceUntilIdle()
        assertEquals(asked, vm.epgSync.value)
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), asked)
    }

    @Test
    fun `returning to the app mid-import still finds the import and then the guide question`() = runTest(dispatcher) {
        // The TV's Home button: the wizard stops being shown, the activity-scoped import keeps going, and
        // nothing calls continueInBackground. Coming back shows whatever the state holds.
        startPlaylist()
        advanceUntilIdle()
        assertEquals(ImportState.Running, vm.state.value)
        playlistSync.complete(SyncResult.Success())
        advanceUntilIdle()
        assertTrue(vm.state.value is ImportState.Success)
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), vm.epgSync.value)

        // Same during the guide download: still downloading on return, then done.
        vm.syncPendingEpg()
        advanceUntilIdle()
        assertTrue(vm.epgSync.value is EpgSyncUi.Syncing)
        guideDownload.complete(42)
        advanceUntilIdle()
        assertEquals(EpgSyncUi.Done, vm.epgSync.value)
    }

    @Test
    fun `a failed first import cleans up, and the retry asks the guide question once`() = runTest(dispatcher) {
        startPlaylist()
        advanceUntilIdle()
        playlistSync.complete(SyncResult.Failed("timeout"))
        advanceUntilIdle()
        assertTrue(vm.state.value is ImportState.Failed)
        assertEquals(EpgSyncUi.Hidden, vm.epgSync.value)
        coVerify(exactly = 1) { sourceRepository.deleteSource(source) }

        // TRY AGAIN: a clean import, still without "Run in background", then one guide question.
        playlistSync = CompletableDeferred()
        vm.reset()
        startPlaylist()
        advanceUntilIdle()
        assertFalse(vm.runInBackgroundAvailable.value)
        playlistSync.complete(SyncResult.Success())
        advanceUntilIdle()
        assertTrue(vm.state.value is ImportState.Success)
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), vm.epgSync.value)
    }

    @Test
    fun `a cancelled first import removes the playlist and asks nothing`() = runTest(dispatcher) {
        startPlaylist()
        advanceUntilIdle()
        vm.cancelImport()
        advanceUntilIdle()
        assertEquals(ImportState.Idle, vm.state.value)
        assertEquals(EpgSyncUi.Hidden, vm.epgSync.value)
        coVerify { sourceRepository.deleteSource(source) }
    }

    @Test
    fun `a playlist without a guide feed has no question and onboarding continues`() = runTest(dispatcher) {
        every { epgRepository.guideUrl(any()) } returns null
        importPlaylist()
        assertTrue(vm.state.value is ImportState.Success)
        assertEquals(EpgSyncUi.Hidden, vm.epgSync.value)
    }

    @Test
    fun `re-syncing linked existing playlists keeps Run in background`() = runTest(dispatcher) {
        vm.linkExisting(setOf(source.id))
        advanceUntilIdle()
        assertEquals(ImportState.Running, vm.state.value)
        assertTrue(vm.runInBackgroundAvailable.value)

        val entered = Entered()
        vm.continueInBackground(entered.callback())
        advanceUntilIdle()
        assertEquals(1, entered.calls)

        playlistSync.complete(SyncResult.Success())
        advanceUntilIdle()
        assertTrue(vm.state.value is ImportState.Success)
        assertEquals("linked playlists ask no guide question", EpgSyncUi.Hidden, vm.epgSync.value)
    }

    // ---- Interrupted first run: the app killed after the profile / playlist existed, before setup finished ----

    private val leftProfile = ProfileEntity(id = 1, name = "Synthetic Profile", avatarColor = 0)

    /** What a killed first run leaves in the database: [profile], linked to [left] when given. */
    private fun leftBehind(profile: ProfileEntity = leftProfile, left: SourceEntity? = source) {
        coEvery { profileDao.getAllOnce() } returns listOf(profile)
        coEvery { sourceDao.allLinks() } returns listOfNotNull(left?.let { ProfileSourceCrossRef(profileId = profile.id, sourceId = it.id) })
        coEvery { sourceDao.getAllOnce() } returns listOfNotNull(left)
    }

    @Test
    fun `a finished import marks itself and finishing setup clears the mark`() = runTest(dispatcher) {
        importPlaylist()
        assertEquals(source.id, importedMarker.value)
        vm.dismissPendingEpg()
        vm.finish()
        advanceUntilIdle()
        assertEquals(-1L, importedMarker.value)
        assertEquals(1L, activeProfile.value)
    }

    @Test
    fun `killed at the guide question, the restart resumes there with the same profile and playlist`() = runTest(dispatcher) {
        leftBehind()
        importedMarker.value = source.id
        val setup = vm.findInterruptedSetup()!!
        assertTrue(setup.importFinished)
        assertEquals(SetupViewModel.ResumePoint.IMPORT, vm.resumeInterruptedSetup(setup))
        advanceUntilIdle()

        assertTrue(vm.state.value is ImportState.Success)
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), vm.epgSync.value)
        assertFalse("still no Run in background before the answer", vm.runInBackgroundAvailable.value)
        // Nothing created or downloaded a second time, nothing removed.
        coVerify(exactly = 0) { profileDao.insert(any()) }
        coVerify(exactly = 0) { sourceRepository.addXtreamSource(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { sourceRepository.sync(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sourceRepository.clearSourceContent(any()) }
        coVerify(exactly = 0) { sourceRepository.deleteSource(any()) }

        vm.syncPendingEpg()
        advanceUntilIdle()
        guideDownload.complete(100)
        advanceUntilIdle()
        val entered = Entered()
        vm.finish(entered.callback())
        advanceUntilIdle()
        assertEquals(1L, entered.profile)
        assertEquals(1L, activeProfile.value)
    }

    @Test
    fun `a guide already accepted before the interruption is not asked again`() = runTest(dispatcher) {
        leftBehind()
        importedMarker.value = source.id
        coEvery { epgStore.getAll() } returns listOf(guideSource) // registered; the automatic refresh fetches it
        vm.resumeInterruptedSetup(vm.findInterruptedSetup()!!)
        advanceUntilIdle()
        assertTrue(vm.state.value is ImportState.Success)
        assertEquals(EpgSyncUi.Hidden, vm.epgSync.value)
    }

    @Test
    fun `killed mid-import, the restart imports the same playlist again from a clean slate`() = runTest(dispatcher) {
        leftBehind()
        val setup = vm.findInterruptedSetup()!!
        assertFalse(setup.importFinished)
        assertEquals(SetupViewModel.ResumePoint.IMPORT, vm.resumeInterruptedSetup(setup))
        advanceUntilIdle()
        assertEquals(ImportState.Running, vm.state.value)
        assertFalse(vm.runInBackgroundAvailable.value)
        coVerify { sourceRepository.clearSourceContent(source.id) } // partial rows from the killed run
        coVerify(exactly = 0) { sourceRepository.addXtreamSource(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { profileDao.insert(any()) }
        coVerify(exactly = 0) { settings.setPlaylistAutoRefresh(any(), any()) } // the playlist keeps its own choice

        playlistSync.complete(SyncResult.Success())
        advanceUntilIdle()
        assertTrue(vm.state.value is ImportState.Success)
        assertEquals(EpgSyncUi.Ask("Synthetic Playlist"), vm.epgSync.value)
        coVerify(exactly = 1) { sourceRepository.sync(source, any(), any(), any()) }
    }

    @Test
    fun `killed before any playlist, the restart keeps the profile and asks for a playlist`() = runTest(dispatcher) {
        leftBehind(left = null)
        assertEquals(SetupViewModel.ResumePoint.ADD_PLAYLIST, vm.resumeInterruptedSetup(vm.findInterruptedSetup()!!))
        startPlaylist()
        advanceUntilIdle()
        coVerify(exactly = 0) { profileDao.insert(any()) }
        coVerify { sourceRepository.addXtreamSource(leftProfile.id, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a resumed PIN-locked profile still asks for its PIN`() = runTest(dispatcher) {
        leftBehind(profile = leftProfile.copy(pinHash = "synthetic-hash"))
        importedMarker.value = source.id
        vm.resumeInterruptedSetup(vm.findInterruptedSetup()!!)
        advanceUntilIdle()
        vm.dismissPendingEpg()
        val entered = Entered()
        vm.finish(entered.callback())
        advanceUntilIdle()
        assertEquals(1, entered.calls)
        assertEquals("no id: the profile gate asks for the PIN", null, entered.profile)
        assertEquals(1L, activeProfile.value)
    }

    @Test
    fun `nothing is resumed when setup finished, never started, or is still running here`() = runTest(dispatcher) {
        // A profile is active: setup finished long ago.
        leftBehind()
        activeProfile.value = 1L
        assertEquals(null, vm.findInterruptedSetup())

        // No profile at all: a genuine first run.
        activeProfile.value = -1L
        coEvery { profileDao.getAllOnce() } returns emptyList()
        assertEquals(null, vm.findInterruptedSetup())

        // This ViewModel is itself mid-setup (a configuration change, not an interruption).
        leftBehind()
        vm.createProfile("Synthetic Profile", 0, false, null)
        advanceUntilIdle()
        assertEquals(null, vm.findInterruptedSetup())
    }

    @Test
    fun `only linked playlists may run in background during the import`() {
        assertTrue(importBackgroundAllowed(SetupViewModel.ImportKind.EXISTING_PLAYLISTS))
        assertFalse(importBackgroundAllowed(SetupViewModel.ImportKind.NEW_PLAYLIST))
        assertFalse(importBackgroundAllowed(null))
    }
}
