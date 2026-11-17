package tv.own.owntv.features.search

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelSearchResult
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.features.favorites.FavoritesReturn
import tv.own.owntv.features.favorites.FavoritesReturnState
import tv.own.owntv.features.shell.MainSection

/** Navigation Phase C: tabbed Search results (Live | Movies | Series) with honest counts. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPhaseCTest {

    @After fun tearDown() = Dispatchers.resetMain()

    // ===== Counts and the 500+ cap =================================================================

    @Test fun `tabbed search asks for one row more than the cap, the old list keeps 40`() {
        assertEquals(501, SearchCounts.queryLimit(tabbed = true, legacyLimit = 40))
        assertEquals(40, SearchCounts.queryLimit(tabbed = false, legacyLimit = 40))
    }

    @Test fun `below the cap the visible count is exact`() {
        val (rows, capped) = SearchCounts.bound(rawRows = 300, visible = List(290) { it }, tabbed = true)
        assertEquals(290, rows.size)
        assertFalse(capped)
    }

    @Test fun `past the cap the tab says 500+, and never lists more than 500`() {
        val (rows, capped) = SearchCounts.bound(rawRows = 501, visible = List(501) { it }, tabbed = true)
        assertEquals(500, rows.size)
        assertTrue(capped)
        // Filtering removed some of the 501 rows: still "more than we looked at".
        assertTrue(SearchCounts.bound(rawRows = 501, visible = List(470) { it }, tabbed = true).second)
    }

    @Test fun `the old mixed layout is never capped or truncated`() {
        val (rows, capped) = SearchCounts.bound(rawRows = 40, visible = List(40) { it }, tabbed = false)
        assertEquals(40, rows.size)
        assertFalse(capped)
    }

    // ===== Debounce, cancellation, errors (the pipeline the ViewModel uses) ==========================

    private fun TestScope.collect(flow: kotlinx.coroutines.flow.Flow<SearchResults>): MutableList<SearchResults> {
        val out = mutableListOf<SearchResults>()
        backgroundScope.launch { flow.collect { out += it } }
        return out
    }

    @Test fun `typing is debounced - only the settled query is searched`() = runTest {
        val q = MutableStateFlow("")
        val searched = mutableListOf<String>()
        val out = collect(searchResultsFlow(q) { searched += it; SearchResults(query = it) })
        for (t in listOf("b", "ba", "bat", "batm")) { q.value = t; advanceTimeBy(100) }
        advanceTimeBy(301)
        assertEquals(listOf("batm"), searched)
        assertEquals("batm", out.last().query)
    }

    @Test fun `a newer query cancels the running search`() = runTest {
        val q = MutableStateFlow("")
        val finished = mutableListOf<String>()
        val out = collect(searchResultsFlow(q) { query -> delay(1_000); finished += query; SearchResults(query = query) })
        q.value = "bat"; advanceTimeBy(350)       // "bat" starts searching
        q.value = "batman"; advanceTimeBy(2_000)  // …and is overtaken (background work: advance time explicitly)
        assertEquals(listOf("batman"), finished)
        assertEquals(listOf("batman"), out.map { it.query }.filter { it.isNotEmpty() })
    }

    @Test fun `a failing search becomes an error result, not a crash`() = runTest {
        val q = MutableStateFlow("")
        val out = collect(searchResultsFlow(q) { error("db closed") })
        q.value = "bat"; advanceTimeBy(1_000)
        assertTrue(out.last().error)
        assertEquals("bat", out.last().query)
    }

    @Test fun `under two characters there is no search`() = runTest {
        val q = MutableStateFlow("")
        var calls = 0
        val out = collect(searchResultsFlow(q) { calls++; SearchResults(query = it) })
        q.value = "b"; advanceTimeBy(1_000)
        assertEquals(0, calls)
        assertTrue(out.last().isEmpty)
    }

    // ===== The ViewModel: tabs, playlist/profile scoping, hidden and Kids filtering ==================

    private val src10 = SourceEntity(id = 10, name = "Active", type = SourceType.XTREAM, url = "x", syncMovies = false)
    private val src11 = SourceEntity(id = 11, name = "Other", type = SourceType.XTREAM, url = "y")
    private fun ch(id: Long, cat: Long? = null) = ChannelSearchResult(ChannelEntity(id = id, sourceId = 10, categoryId = cat, name = "Bat $id", streamUrl = "s"), null)
    private val adult = CategoryEntity(id = 8, sourceId = 10, mediaType = MediaType.LIVE, name = "XXX Adults")

    private class Fixture(val vm: SearchViewModel, val channelDao: tv.own.owntv.core.database.dao.ChannelDao, val movieDao: tv.own.owntv.core.database.dao.MovieDao, val liveIds: io.mockk.CapturingSlot<List<Long>>, val limit: io.mockk.CapturingSlot<Int>)

    private fun fixture(scope: TestScope, kids: Boolean, rows: List<ChannelSearchResult>, hiddenKey: String? = null): Fixture {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        Dispatchers.setMain(dispatcher)
        val settings = mockk<tv.own.owntv.features.settings.data.SettingsRepository>(relaxed = true)
        every { settings.activeProfileId } returns flowOf(1L)
        every { settings.defaultSourceId } returns flowOf(10L) // the active playlist is source 10
        every { settings.recentSearches } returns flowOf(emptyList())
        every { settings.externalPlayerMovies } returns flowOf(false)
        val sourceDao = mockk<tv.own.owntv.core.database.dao.SourceDao>(relaxed = true)
        every { sourceDao.observeForProfile(1L) } returns flowOf(listOf(src10, src11))
        val profileDao = mockk<tv.own.owntv.core.database.dao.ProfileDao>(relaxed = true)
        coEvery { profileDao.getById(1L) } returns ProfileEntity(id = 1, name = "P", avatarColor = 0, isKids = kids)
        val customize = mockk<tv.own.owntv.core.customize.CustomizationStore>(relaxed = true)
        every { customize.observe(1L, any()) } returns flowOf(
            SectionCustomizations(hiddenItems = hiddenKey?.let { mapOf(it to "x") } ?: emptyMap()),
        )
        val categoryDao = mockk<tv.own.owntv.core.database.dao.CategoryDao>(relaxed = true)
        every { categoryDao.observe(any(), any()) } returns flowOf(listOf(adult))
        val channelDao = mockk<tv.own.owntv.core.database.dao.ChannelDao>(relaxed = true)
        val liveIds = slot<List<Long>>(); val limit = slot<Int>()
        coEvery { channelDao.searchListDetailedFts(any(), capture(liveIds), capture(limit)) } returns rows
        val movieDao = mockk<tv.own.owntv.core.database.dao.MovieDao>(relaxed = true)
        val vm = SearchViewModel(
            channelDao = channelDao, categoryDao = categoryDao, movieDao = movieDao, seriesDao = mockk(relaxed = true),
            historyDao = mockk(relaxed = true), profileDao = profileDao, sourceDao = sourceDao, settings = settings,
            customize = customize, favoriteDao = mockk(relaxed = true), player = mockk(relaxed = true),
            externalPlayerLauncher = mockk(relaxed = true), streamUrlResolver = mockk(relaxed = true),
        )
        vm.workDispatcher = dispatcher
        scope.backgroundScope.launch { vm.results.collect {} }
        return Fixture(vm, channelDao, movieDao, liveIds, limit)
    }

    @Test fun `only the active playlist is searched, and an Off section is never queried`() = runTest {
        val f = fixture(this, kids = false, rows = listOf(ch(1)))
        runCurrent()
        f.vm.setQuery("bat"); advanceUntilIdle()
        assertEquals(listOf(10L), f.liveIds.captured)          // not source 11
        assertEquals(501, f.limit.captured)                     // GoatTV: tabbed cap + 1
        coVerify(exactly = 0) { f.movieDao.searchListFts(any(), any(), any()) } // source 10 has Movies off
        assertEquals(listOf(1L), f.vm.results.value.channels.map { it.channel.id })
    }

    @Test fun `hidden items and a kids profile's adult categories never surface or count`() = runTest {
        val hidden = CustomizeKeys.channel(ch(2).channel)
        val f = fixture(this, kids = true, rows = listOf(ch(1), ch(2), ch(3, cat = 8)), hiddenKey = hidden)
        runCurrent()
        f.vm.setQuery("bat"); advanceUntilIdle()
        assertEquals(listOf(1L), f.vm.results.value.channels.map { it.channel.id })
        assertFalse(f.vm.results.value.channelsCapped)
    }

    @Test fun `an adult profile sees the adult category result`() = runTest {
        val f = fixture(this, kids = false, rows = listOf(ch(1), ch(3, cat = 8)))
        runCurrent()
        f.vm.setQuery("bat"); advanceUntilIdle()
        assertEquals(listOf(1L, 3L), f.vm.results.value.channels.map { it.channel.id })
    }

    @Test fun `tabs are Live, Movies, Series and a new search starts on Live`() = runTest {
        assertEquals(listOf(SearchTab.LIVE, SearchTab.MOVIES, SearchTab.SERIES), SearchTab.entries.toList())
        val f = fixture(this, kids = false, rows = listOf(ch(1)))
        assertEquals(SearchTab.LIVE, f.vm.tab.value)
        f.vm.setQuery("bat"); advanceUntilIdle()
        f.vm.selectTab(SearchTab.SERIES)
        assertEquals(SearchTab.SERIES, f.vm.tab.value)
        f.vm.setQuery("lost")
        assertEquals("a new query starts on Live", SearchTab.LIVE, f.vm.tab.value)
    }

    @Test fun `switching tabs keeps the query and does not search again`() = runTest {
        val f = fixture(this, kids = false, rows = listOf(ch(1)))
        runCurrent()
        f.vm.setQuery("bat"); advanceUntilIdle()
        val before = f.vm.results.value
        f.vm.selectTab(SearchTab.MOVIES); f.vm.selectTab(SearchTab.SERIES); f.vm.selectTab(SearchTab.LIVE)
        advanceUntilIdle()
        assertEquals("bat", f.vm.query.value)
        assertTrue(before === f.vm.results.value)
        coVerify(exactly = 1) { f.channelDao.searchListDetailedFts(any(), any(), any()) }
    }

    @Test fun `a re-typed identical query keeps the tab`() = runTest {
        val f = fixture(this, kids = false, rows = listOf(ch(1)))
        f.vm.setQuery("bat"); f.vm.selectTab(SearchTab.MOVIES)
        f.vm.setQuery("bat ") // same trimmed query
        assertEquals(SearchTab.MOVIES, f.vm.tab.value)
    }

    @Test fun `empty results are an empty, not capped, answer for the query`() = runTest {
        val f = fixture(this, kids = false, rows = emptyList())
        runCurrent()
        f.vm.setQuery("zzz"); advanceUntilIdle()
        val r = f.vm.results.value
        assertTrue(r.isEmpty)
        assertEquals("zzz", r.query)
        assertFalse(r.channelsCapped)
    }

    // ===== Return to Search after details ==========================================================

    @Test fun `movie details opened from search return to search`() {
        var s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.MOVIES, returnTo = MainSection.SEARCH)
        s = FavoritesReturn.onSectionChanged(s, MainSection.MOVIES).state
        s = FavoritesReturn.onDetails(s, detailsOpen = true, section = MainSection.MOVIES, playerIdle = true).first
        assertEquals(MainSection.SEARCH, FavoritesReturn.onDetails(s, false, MainSection.MOVIES, true).second)
    }

    @Test fun `series details opened from search return to search`() {
        var s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.SERIES, returnTo = MainSection.SEARCH)
        s = FavoritesReturn.onDetails(s, true, MainSection.SERIES, true).first
        assertEquals(MainSection.SEARCH, FavoritesReturn.onDetails(s, false, MainSection.SERIES, true).second)
    }

    @Test fun `a search return does not disturb the Favorites tab`() {
        val s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.MOVIES, returnTo = MainSection.SEARCH)
        assertFalse(s.keepTab)
        val arrived = FavoritesReturn.onSectionChanged(s, MainSection.SEARCH)
        assertNull(arrived.state.detailsFrom)
        assertFalse(arrived.resetTab)
    }

    // ===== Brand isolation ========================================================================

    @Test fun `tabbed search is GoatTV-only`() {
        assertTrue(tv.own.owntv.BuildConfig.NAV_FAVORITES_SEARCH_TABS)
        assertTrue(MainSection.favoritesNavEnabled)
        // Where the flag is off, the queries keep the old 40 rows and nothing is capped (asserted above).
        assertEquals(40, SearchCounts.queryLimit(tabbed = false, legacyLimit = 40))
    }
}
