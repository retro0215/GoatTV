package tv.own.owntv.features.movies

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.catalog.versions.ContentVersion
import tv.own.owntv.core.catalog.versions.VersionCandidateRow
import tv.own.owntv.core.catalog.versions.VersionLabel
import tv.own.owntv.core.catalog.versions.VersionRepository
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.player.DeviceVideoCapabilities
import tv.own.owntv.core.player.DeviceVideoCaps
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.features.versions.WatchAction
import tv.own.owntv.features.versions.WatchGate
import tv.own.owntv.features.versions.watchAction
import tv.own.owntv.player.EnginePreference
import tv.own.owntv.player.OwnTVPlayer

/**
 * The movie early-OK race: while the version lookup runs, Watch must wait (never play the opened
 * version directly), the lookup runs once per film, and a lookup that hangs ends as one version.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MovieVersionLookupTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val profileDao = mockk<ProfileDao>(relaxed = true)
    private val player = mockk<OwnTVPlayer>(relaxed = true)
    private val versionRepository = mockk<VersionRepository>()
    private val deviceVideo = mockk<DeviceVideoCapabilities>()

    private val dune = MovieEntity(id = 7, sourceId = 1, name = "Dune 2021 1080p", streamUrl = "u7", remoteId = "7", titleSignature = "dune", parsedYear = 2021)

    private fun version(id: Long, rank: Int, anchor: Boolean) = ContentVersion(
        VersionCandidateRow(id, 1, null, null, null, "Dune", "r$id", "dune", 2021, null, rank, null),
        VersionLabel(rank, null, null),
        anchor,
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { settings.activeProfileId } returns flowOf(1L)
        every { settings.defaultSourceId } returns flowOf(-1L)
        every { settings.vodEnginePreference } returns flowOf(EnginePreference.MPV_FIRST)
        every { settings.rememberCategoryMovies } returns flowOf(false)
        coEvery { settings.versionChoice(any(), any(), any()) } returns null
        coEvery { profileDao.getById(1) } returns ProfileEntity(id = 1, name = "P", avatarColor = 0)
        every { player.isPlaying } returns MutableStateFlow(false)
        every { deviceVideo.caps } returns DeviceVideoCaps.UNKNOWN
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = MovieViewModel(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        profileDao, mockk(relaxed = true), settings, mockk(relaxed = true), player, mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), versionRepository, deviceVideo,
    ).also { scope.runCurrent() }

    @Test fun `Watch waits while the lookup runs, then opens the picker`() {
        coEvery { versionRepository.movieVersions(dune) } returns listOf(version(7, 3, true), version(8, 4, false))
        val vm = vm()
        val gate = WatchGate(dune.id)
        vm.loadVersions(dune)
        // Pressed before the indexed query answered: held, the 1080p film does not start.
        assertEquals(null, gate.press(vm.versions.value))
        assertEquals(WatchAction.WAIT, watchAction(vm.versions.value, dune.id))
        scope.runCurrent()
        assertTrue(vm.versions.value!!.hasChoice)
        assertEquals(WatchAction.CHOOSE, gate.onVersions(vm.versions.value))
    }

    @Test fun `one version plays directly once the lookup is ready`() {
        coEvery { versionRepository.movieVersions(dune) } returns listOf(version(7, 3, true))
        val vm = vm()
        vm.loadVersions(dune)
        scope.runCurrent()
        assertEquals(WatchAction.PLAY_DIRECT, watchAction(vm.versions.value, dune.id))
    }

    @Test fun `a second request for the same film while it is looking does not query again`() {
        coEvery { versionRepository.movieVersions(dune) } returns listOf(version(7, 3, true), version(8, 4, false))
        val vm = vm()
        vm.loadVersions(dune)
        vm.loadVersions(dune)
        scope.runCurrent()
        coVerify(exactly = 1) { versionRepository.movieVersions(dune) }
    }

    @Test fun `a lookup that hangs or fails ends as one version so Watch is never stuck`() {
        coEvery { versionRepository.movieVersions(dune) } coAnswers { awaitCancellation() }
        val vm = vm()
        vm.loadVersions(dune)
        scope.runCurrent()
        assertFalse(vm.versions.value!!.ready)
        scope.advanceTimeBy(3_001)
        scope.runCurrent()
        assertEquals(WatchAction.PLAY_DIRECT, watchAction(vm.versions.value, dune.id))

        val other = dune.copy(id = 9)
        coEvery { versionRepository.movieVersions(other) } throws IllegalStateException("db closed")
        vm.loadVersions(other)
        scope.runCurrent()
        assertEquals(WatchAction.PLAY_DIRECT, watchAction(vm.versions.value, other.id))
    }
}
