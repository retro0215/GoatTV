package tv.own.owntv.features.series

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.catalog.versions.ContentVersion
import tv.own.owntv.core.catalog.versions.VersionCandidateRow
import tv.own.owntv.core.catalog.versions.VersionLabel
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.player.DeviceVideoCapabilities
import tv.own.owntv.core.player.DeviceVideoCaps
import tv.own.owntv.core.repository.SeriesRepository
import tv.own.owntv.core.catalog.versions.VersionRepository
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.features.versions.VersionChoice
import tv.own.owntv.features.versions.VersionOption
import tv.own.owntv.player.EnginePreference
import tv.own.owntv.player.OwnTVPlayer

/**
 * The series version selector end to end in the ViewModel: versions lookup, switching with the place
 * kept, episode lookup across versions, failures and cut-off requests, double OK and Kids profiles.
 * Every provider and database call is a mock; the 10 s progress loop is never run (no advanceUntilIdle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesVersionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private val seriesDao = mockk<SeriesDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val progressDao = mockk<ProgressDao>(relaxed = true)
    private val profileDao = mockk<ProfileDao>(relaxed = true)
    private val sourceDao = mockk<SourceDao>(relaxed = true)
    private val seriesRepository = mockk<SeriesRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val player = mockk<OwnTVPlayer>(relaxed = true)
    private val versionRepository = mockk<VersionRepository>()
    private val deviceVideo = mockk<DeviceVideoCapabilities>()

    // One show in two playlists: 1080p (source 1, id 10) and 4K (source 2, id 20).
    private val hd = SeriesEntity(id = 10, sourceId = 1, categoryId = 100, name = "Severance", remoteId = "s1", titleSignature = "severance", parsedYear = 2022)
    private val uhd = SeriesEntity(id = 20, sourceId = 2, categoryId = 200, name = "Severance 4K", remoteId = "s2", titleSignature = "severance", parsedYear = 2022)

    // Different provider ids on each side; season/episode numbers are what match.
    private val hdEpisodes = listOf(ep(101, 10, 1, 1, "9001"), ep(102, 10, 1, 2, "9002"), ep(103, 10, 2, 1, "9003"))
    private val uhdEpisodes = listOf(ep(201, 20, 1, 1, "55"), ep(202, 20, 1, 2, "56"))

    private fun ep(id: Long, seriesId: Long, season: Int, episode: Int, remoteId: String, durationSecs: Int? = 3000) =
        EpisodeEntity(id = id, seriesId = seriesId, seasonNumber = season, episodeNumber = episode, name = "Title $id", streamUrl = "u$id", remoteId = remoteId, durationSecs = durationSecs)

    private fun version(s: SeriesEntity, rank: Int, anchor: Boolean) = ContentVersion(
        row = VersionCandidateRow(
            id = s.id, sourceId = s.sourceId, categoryId = s.categoryId, categoryName = "Cat ${s.categoryId}", categoryRemoteId = null,
            name = s.name, remoteId = s.remoteId, titleSignature = s.titleSignature, parsedYear = s.parsedYear,
            providerLanguage = null, qualityRank = rank, advertisedCapabilities = null,
        ),
        label = VersionLabel(qualityRank = rank, advertisedPicture = null, language = null),
        isAnchor = anchor,
    )

    private var kids = false

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { settings.activeProfileId } returns flowOf(1L)
        every { settings.defaultSourceId } returns flowOf(-1L)
        every { settings.vodEnginePreference } returns flowOf(EnginePreference.MPV_FIRST)
        every { settings.rememberCategorySeries } returns flowOf(false)
        coEvery { settings.versionChoice(any(), any(), any()) } returns null
        every { sourceDao.observeForProfile(any()) } returns flowOf(emptyList())
        coEvery { sourceDao.getById(1) } returns SourceEntity(id = 1, name = "Main", type = SourceType.XTREAM, url = "")
        coEvery { sourceDao.getById(2) } returns SourceEntity(id = 2, name = "Backup", type = SourceType.XTREAM, url = "")
        coEvery { profileDao.getById(1) } answers { ProfileEntity(id = 1, name = "P", avatarColor = 0, isKids = kids) }
        coEvery { categoryDao.getById(100) } returns CategoryEntity(id = 100, sourceId = 1, mediaType = MediaType.SERIES, name = "Drama")
        coEvery { categoryDao.getById(200) } returns CategoryEntity(id = 200, sourceId = 2, mediaType = MediaType.SERIES, name = "XXX Adults")
        every { player.isPlaying } returns MutableStateFlow(false)
        every { player.queueEnded } returns MutableSharedFlow()
        every { player.queueItemChanged } returns MutableSharedFlow()
        every { deviceVideo.caps } returns DeviceVideoCaps.UNKNOWN
        coEvery { seriesDao.getSeriesById(10) } returns hd
        coEvery { seriesDao.getSeriesById(20) } returns uhd
        coEvery { seriesDao.episodesBySeriesOnce(10) } returns hdEpisodes
        coEvery { seriesDao.episodesBySeriesOnce(20) } returns uhdEpisodes
        every { seriesDao.episodesBySeries(10) } returns flowOf(hdEpisodes)
        every { seriesDao.episodesBySeries(20) } returns flowOf(uhdEpisodes)
        hdEpisodes.plus(uhdEpisodes).forEach { e -> coEvery { seriesDao.getEpisodeById(e.id) } returns e }
        coEvery { progressDao.get(any(), any(), any()) } returns null
        coEvery { progressDao.lastWatchedEpisodeId(any(), any()) } returns null
        coEvery { seriesRepository.loadEpisodesWithInfo(any(), any()) } returns null
        coEvery { versionRepository.seriesVersions(any()) } returns listOf(version(hd, 3, anchor = true), version(uhd, 4, anchor = false))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = SeriesViewModel(
        seriesDao, categoryDao, mockk(relaxed = true), mockk(relaxed = true), progressDao, profileDao, sourceDao,
        seriesRepository, settings, mockk(relaxed = true), player, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), versionRepository, deviceVideo,
    ).also { scope.runCurrent() } // the active profile is known before anything opens, as in the app

    private fun settle() = scope.runCurrent()

    @Test fun `one version leaves the page as it was`() {
        coEvery { versionRepository.seriesVersions(any()) } returns listOf(version(hd, 3, anchor = true))
        val vm = vm()
        vm.openSeries(hd)
        assertFalse(vm.seriesVersions.value!!.ready)
        settle()
        val ui = vm.seriesVersions.value!!
        assertTrue(ui.ready)
        assertFalse(ui.hasChoice)
    }

    @Test fun `several versions are listed with the open one marked and the remembered one focused`() {
        coEvery { settings.versionChoice(1, any(), true) } returns "2:s2"
        val vm = vm()
        vm.openSeries(hd)
        settle()
        val ui = vm.seriesVersions.value!!
        assertTrue(ui.hasChoice)
        assertEquals(listOf(true, false), ui.options.map { it.isCurrent })
        assertEquals(1, ui.initialIndex)
        assertEquals(listOf("Main", "Backup"), ui.options.map { it.playlistName })
    }

    @Test fun `switching keeps the episode, remembers the choice and never asks the provider when cached`() {
        coEvery { progressDao.lastWatchedEpisodeId(1, 10) } returns 102
        val vm = vm()
        vm.openSeries(hd)
        settle()
        assertEquals(102L, vm.lastPlayedEpisodeId.value)
        val ui = vm.seriesVersions.value!!
        vm.switchSeriesVersion(ui, ui.options[1], hdEpisodes[1])
        settle()
        assertEquals(20L, vm.openedSeries.value?.id)
        assertEquals(1, vm.selectedSeason.value)
        assertEquals(202L, vm.lastPlayedEpisodeId.value) // S1E2 in 4K, though its provider id differs
        assertNull(vm.versionNotice.value)
        coVerify { settings.setVersionChoice(1, ui.titleKey, "2:s2", true) }
        // The episode cache decides whether the provider is asked (SeriesRepository); one load per version.
        coVerify(exactly = 1) { seriesRepository.loadEpisodesWithInfo(uhd, any()) }
    }

    @Test fun `an episode missing in the other version keeps the season and says so`() {
        val vm = vm()
        vm.openSeries(hd)
        settle()
        vm.selectSeason(2)
        val ui = vm.seriesVersions.value!!
        vm.switchSeriesVersion(ui, ui.options[1], hdEpisodes[2]) // S2E1: 4K has no season 2
        settle()
        assertEquals(20L, vm.openedSeries.value?.id)
        assertEquals(1, vm.selectedSeason.value)
        assertNull(vm.lastPlayedEpisodeId.value)
        assertEquals(SeriesViewModel.VersionNotice.EPISODE_NOT_IN_VERSION, vm.versionNotice.value)
    }

    @Test fun `a version whose episodes cannot be loaded leaves the current one open`() {
        coEvery { seriesDao.episodesBySeriesOnce(20) } returns emptyList()
        coEvery { seriesRepository.loadEpisodesWithInfo(uhd, any()) } throws java.io.IOException("unreachable")
        val vm = vm()
        vm.openSeries(hd)
        settle()
        val ui = vm.seriesVersions.value!!
        vm.switchSeriesVersion(ui, ui.options[1], null)
        settle()
        assertEquals(10L, vm.openedSeries.value?.id)
        assertFalse(vm.episodesLoading.value)
        assertEquals(SeriesViewModel.VersionNotice.SWITCH_FAILED, vm.versionNotice.value)
        coVerify(exactly = 0) { settings.setVersionChoice(any(), any(), any(), true) }
    }

    @Test fun `a Kids profile cannot switch into a version in an adult category`() {
        kids = true
        val vm = vm()
        vm.openSeries(hd)
        settle()
        val ui = vm.seriesVersions.value!!
        vm.switchSeriesVersion(ui, ui.options[1], null)
        settle()
        assertEquals(10L, vm.openedSeries.value?.id)
    }

    @Test fun `opening another show while a switch loads cancels the switch`() {
        coEvery { seriesRepository.loadEpisodesWithInfo(uhd, any()) } coAnswers { awaitCancellation() }
        val vm = vm()
        vm.openSeries(hd)
        settle()
        val ui = vm.seriesVersions.value!!
        vm.switchSeriesVersion(ui, ui.options[1], null)
        settle()
        val other = SeriesEntity(id = 30, sourceId = 1, name = "Other")
        coEvery { seriesDao.getSeriesById(30) } returns other
        every { seriesDao.episodesBySeries(30) } returns flowOf(emptyList())
        coEvery { versionRepository.seriesVersions(other) } returns emptyList()
        vm.openSeries(other)
        settle()
        assertEquals(30L, vm.openedSeries.value?.id)
    }

    @Test fun `the episode is found in each version by number, missing or unloaded ones are listed with the reason`() {
        val third = SeriesEntity(id = 40, sourceId = 1, categoryId = 100, name = "Severance FR", remoteId = "s3", titleSignature = "severance", parsedYear = 2022)
        coEvery { seriesDao.getSeriesById(40) } returns third
        coEvery { seriesDao.episodesBySeriesOnce(40) } returns emptyList() // provider cut the request off
        coEvery { versionRepository.seriesVersions(any()) } returns listOf(version(hd, 3, true), version(uhd, 4, false), version(third, 3, false))
        val vm = vm()
        vm.openSeries(hd)
        settle()
        vm.loadEpisodeVersions(hdEpisodes[1])
        assertFalse(vm.episodeVersions.value!!.ready)
        settle()
        val ui = vm.episodeVersions.value!!
        assertEquals(listOf(102L, 202L, 40L), ui.options.map { it.itemId })
        assertEquals(listOf(null, null, VersionOption.Unavailable.NOT_LOADED), ui.options.map { it.unavailable })

        vm.loadEpisodeVersions(hdEpisodes[2]) // S2E1
        settle()
        assertEquals(VersionOption.Unavailable.MISSING, vm.episodeVersions.value!!.options[1].unavailable)
    }

    @Test fun `choosing the episode in another version offers the same point, switches the page, and a double OK plans once`() {
        coEvery { progressDao.get(1, MediaType.EPISODE, 102) } returns PlaybackProgressEntity(profileId = 1, mediaType = MediaType.EPISODE, itemId = 102, positionMs = 900_000, durationMs = 3_000_000)
        val vm = vm()
        vm.openSeries(hd)
        settle()
        vm.loadEpisodeVersions(hdEpisodes[1])
        settle()
        val ui = vm.episodeVersions.value!!
        val plans = mutableListOf<Triple<SeriesEntity, EpisodeEntity, VersionChoice.StartPlan>>()
        vm.chooseEpisodeVersion(ui, ui.options[1]) { s, e, p -> plans += Triple(s, e, p) }
        vm.chooseEpisodeVersion(ui, ui.options[1]) { s, e, p -> plans += Triple(s, e, p) }
        settle()
        assertEquals(1, plans.size)
        val (show, episode, plan) = plans.single()
        assertEquals(20L, show.id)
        assertEquals(202L, episode.id)
        assertEquals(VersionChoice.StartPlan(0, 900_000), plan) // 3000 s each: same length
        // Next and the next season now continue in 4K.
        assertEquals(20L, vm.openedSeries.value?.id)
        assertNull(vm.episodeVersions.value)
        // Progress is only read here; nothing is written to the 1080p episode.
        coVerify(exactly = 0) { progressDao.save(any()) }
    }

    @Test fun `an unavailable row cannot be chosen and closing cancels a running lookup`() {
        coEvery { seriesRepository.loadEpisodesWithInfo(uhd, any()) } coAnswers { awaitCancellation() }
        val vm = vm()
        vm.openSeries(hd)
        settle()
        vm.loadEpisodeVersions(hdEpisodes[0])
        settle()
        assertFalse(vm.episodeVersions.value!!.ready)
        vm.closeEpisodeVersions()
        settle()
        assertNull(vm.episodeVersions.value)

        var planned = false
        val ui = tv.own.owntv.features.versions.VersionsUi(101, true, "t", listOf(
            vm.seriesVersions.value!!.options[0].copy(unavailable = VersionOption.Unavailable.MISSING),
        ))
        vm.chooseEpisodeVersion(ui, ui.options[0]) { _, _, _ -> planned = true }
        settle()
        assertFalse(planned)
    }
}
