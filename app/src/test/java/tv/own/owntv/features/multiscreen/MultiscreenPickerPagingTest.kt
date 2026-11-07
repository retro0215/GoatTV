package tv.own.owntv.features.multiscreen

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.live.LiveKey
import java.util.Collections

/**
 * The Multiscreen picker crashed GoatTV: picker on History, closed, then any live channel played — the
 * History write invalidated the picker's PagingSource and its Pager got the same, spent instance back.
 * Room invalidates exactly like [FakeSource.invalidate] here; the picker's flow is kept alive in the
 * ViewModel's scope (cachedIn) while the picker is closed, as in the app.
 */
class MultiscreenPickerPagingTest {

    private class FakeSource(private val rows: () -> List<ChannelEntity>) : PagingSource<Int, ChannelEntity>() {
        override fun getRefreshKey(state: PagingState<Int, ChannelEntity>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChannelEntity> =
            LoadResult.Page(rows(), prevKey = null, nextKey = null)
    }

    private fun ch(id: Long) = ChannelEntity(
        id = id, sourceId = 1, categoryId = null, name = "Ch$id", streamUrl = "http://x/$id.ts", remoteId = "$id",
    )

    @Volatile private var history = listOf(ch(1), ch(2))
    @Volatile private var favorites = listOf(ch(5))
    @Volatile private var all = listOf(ch(1), ch(2), ch(3))

    /** Every PagingSource the DAO opened, in order. */
    private val opened: MutableList<FakeSource> = Collections.synchronizedList(mutableListOf())
    private fun open(rows: () -> List<ChannelEntity>) = FakeSource(rows).also { opened += it }

    private val dao = mockk<ChannelDao> {
        every { pagingHistory(any(), any()) } answers { open { history } }
        every { pagingFavorites(any()) } answers { open { favorites } }
        every { pagingAllOriginal(any()) } answers { open { all } }
        every { pagingByCategory(any()) } answers { open { all.filter { it.id == 3L } } }
        every { searchAll(any(), any()) } answers { open { all.filter { it.name.contains(firstArg<String>()) } } }
    }

    /** The ViewModel's scope; any exception thrown in it (the crash) lands in [errors]. */
    private val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errors += e })

    @After fun tearDown() = vmScope.cancel()

    private fun picker(key: LiveKey, query: String = ""): Flow<PagingData<ChannelEntity>> =
        MultiscreenPickerPaging.pager(dao, profileId = 1L, sourceIds = listOf(1L), key = key, query = query).flow.cachedIn(vmScope)

    /** The picker on screen: a LazyPagingItems-style collector, cancelled when the picker closes. */
    private class OpenPicker(flow: Flow<PagingData<ChannelEntity>>, scope: CoroutineScope) {
        val presenter = object : PagingDataPresenter<ChannelEntity>(mainContext = Dispatchers.Default) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<ChannelEntity>) {}
        }
        val job: Job = scope.launch { flow.collectLatest { presenter.collectFrom(it) } }
        fun ids(): List<Long> = presenter.snapshot().items.map { it.id }
        suspend fun close() = job.cancelAndJoin()
    }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        runCatching { withTimeout(5_000) { while (!check()) delay(10) } }
            .onFailure { throw AssertionError("timed out waiting for: $what (errors=$errors)") }
    }

    private fun assertNoReuse() {
        assertTrue("crashed: $errors", errors.isEmpty())
        val distinct = Collections.newSetFromMap(java.util.IdentityHashMap<FakeSource, Boolean>()).apply { addAll(opened) }
        assertEquals("a PagingSource was handed to the Pager twice", opened.size, distinct.size)
    }

    // ---- The factory ----

    @Test
    fun `every call opens a new PagingSource for every category and for search`() {
        val cases = listOf(LiveKey.Favorites to "", LiveKey.History to "", LiveKey.All to "", LiveKey.Folder(7L) to "", LiveKey.All to "Ch")
        for ((key, query) in cases) {
            val factory = MultiscreenPickerPaging.sourceFactory(dao, 1L, listOf(1L), key, query)
            assertNotSame("$key/'$query'", factory(), factory())
        }
    }

    @Test
    fun `each category opens its own query and a search wins over the category`() {
        fun make(key: LiveKey, query: String = "") = MultiscreenPickerPaging.sourceFactory(dao, 9L, listOf(1L, 2L), key, query)()
        make(LiveKey.Favorites); verify { dao.pagingFavorites(9L) }
        make(LiveKey.History); verify { dao.pagingHistory(9L, listOf(1L, 2L)) }
        make(LiveKey.All); verify { dao.pagingAllOriginal(listOf(1L, 2L)) }
        make(LiveKey.Folder(7L)); verify { dao.pagingByCategory(7L) }
        make(LiveKey.History, "espn"); verify { dao.searchAll("espn", listOf(1L, 2L)) }
        make(LiveKey.Folder(7L), "   "); verify(exactly = 2) { dao.pagingByCategory(7L) } // blank search = the category
    }

    // ---- The crash and its neighbours ----

    @Test
    fun `History picker closed, a channel played, History updated - no crash and reopening shows the update`() = runBlocking {
        val flow = picker(LiveKey.History)
        val first = OpenPicker(flow, vmScope)
        eventually("History listed") { first.ids() == listOf(1L, 2L) }
        first.close()

        history = listOf(ch(3)) + history // ensurePlaying writes History ~5 s into live playback
        opened.last().invalidate()
        eventually("History re-queried") { opened.size == 2 }

        val reopened = OpenPicker(flow, vmScope)
        eventually("reopened History shows the new channel") { reopened.ids() == listOf(3L, 1L, 2L) }

        history = listOf(ch(2), ch(3), ch(1)) // and History updated again with the picker open
        opened.last().invalidate()
        eventually("open picker follows the update") { reopened.ids() == listOf(2L, 3L, 1L) }
        reopened.close()
        assertNoReuse()
    }

    @Test
    fun `favourite toggles invalidate Favorites repeatedly without a crash`() = runBlocking {
        val open = OpenPicker(picker(LiveKey.Favorites), vmScope)
        eventually("Favorites listed") { open.ids() == listOf(5L) }
        for (id in 6L..9L) {
            favorites = favorites + ch(id)
            opened.last().invalidate()
            eventually("favourite $id shown") { open.ids().lastOrNull() == id }
        }
        open.close()
        assertNoReuse()
    }

    @Test
    fun `a playlist refresh while the picker is open on All Channels re-queries without a crash`() = runBlocking {
        val open = OpenPicker(picker(LiveKey.All), vmScope)
        eventually("All listed") { open.ids() == listOf(1L, 2L, 3L) }
        all = listOf(ch(1), ch(2), ch(3), ch(4))
        repeat(3) { opened.last().invalidate(); delay(20) } // a re-sync writes the table in several batches
        eventually("new channel shown") { open.ids() == listOf(1L, 2L, 3L, 4L) }
        open.close()
        assertNoReuse()
    }

    @Test
    fun `search and category switching each get their own pager and survive invalidation`() = runBlocking {
        for ((key, query, expected) in listOf(
            Triple(LiveKey.History, "", listOf(1L, 2L)),
            Triple(LiveKey.Folder(7L), "", listOf(3L)),
            Triple(LiveKey.All, "Ch2", listOf(2L)),
            Triple(LiveKey.Favorites, "", listOf(5L)),
        )) {
            val open = OpenPicker(picker(key, query), vmScope)
            eventually("$key/'$query' listed") { open.ids() == expected }
            opened.last().invalidate()
            eventually("$key/'$query' re-queried") { open.ids() == expected && opened.last().invalid.not() }
            open.close()
        }
        assertNoReuse()
    }

    @Test
    fun `rapid picker open and close with History writes in between never reuses a source`() = runBlocking {
        val flow = picker(LiveKey.History)
        repeat(15) { i ->
            val open = OpenPicker(flow, vmScope)
            if (i % 3 == 0) eventually("listed") { open.ids().isNotEmpty() }
            open.close()
            history = listOf(ch(100L + i)) + history
            opened.lastOrNull()?.invalidate()
        }
        val last = OpenPicker(flow, vmScope)
        eventually("latest History shown") { last.ids().firstOrNull() == 114L }
        last.close()
        assertNoReuse()
    }

    /** Guards the test itself: the shipped `Pager { source }` pattern must fail here the way the app did. */
    @Test
    fun `the old prebuilt-source pager crashes on the first History write`() = runBlocking {
        val prebuilt = dao.pagingHistory(1L, listOf(1L))
        val flow = Pager(PagingConfig(pageSize = MultiscreenPickerPaging.PAGE_SIZE)) { prebuilt }.flow.cachedIn(vmScope)
        val open = OpenPicker(flow, vmScope)
        eventually("History listed") { open.ids() == listOf(1L, 2L) }
        open.close()
        prebuilt.invalidate()
        eventually("the reuse crash") { errors.isNotEmpty() }
        assertTrue(errors.first().message.orEmpty().contains("re-used"))
    }
}
