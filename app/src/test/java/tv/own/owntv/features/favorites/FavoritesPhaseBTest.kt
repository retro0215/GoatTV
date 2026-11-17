package tv.own.owntv.features.favorites

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.FavoritesSql
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.features.shell.MainSection
import java.sql.Connection
import java.sql.DriverManager

/** Navigation Phase B: the unified Favorites screen's data, focus and return rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesPhaseBTest {

    // ===== The Favorites SQL itself, on a real SQLite (the DAOs run exactly these constants) ==========

    private lateinit var db: Connection

    @Before fun open() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            for (t in listOf("channels", "movies", "series")) {
                st.executeUpdate("CREATE TABLE $t (id INTEGER PRIMARY KEY, sourceId INTEGER NOT NULL, name TEXT NOT NULL)")
            }
            st.executeUpdate(
                "CREATE TABLE favorites (id INTEGER PRIMARY KEY AUTOINCREMENT, profileId INTEGER NOT NULL, " +
                    "mediaType TEXT NOT NULL, itemId INTEGER NOT NULL, addedAt INTEGER NOT NULL)",
            )
        }
        // Playlist 10 is active; playlist 11 belongs to the same profile but is not the active one.
        exec("INSERT INTO channels VALUES (1, 10, 'BBC One'), (2, 11, 'Other Playlist Ch'), (3, 10, 'ITV')")
        exec("INSERT INTO movies VALUES (21, 10, 'Heat'), (22, 11, 'Alien')")
        exec("INSERT INTO series VALUES (31, 10, 'Dark'), (32, 10, 'Lost')")
        fav(1, "LIVE", 1, at = 100)
        fav(1, "LIVE", 3, at = 300)
        fav(1, "LIVE", 2, at = 200)   // inactive playlist
        fav(1, "LIVE", 99, at = 400)  // stale: channel 99 no longer in the catalog
        fav(2, "LIVE", 1, at = 500)   // another profile's favorite
        fav(1, "MOVIE", 21, at = 10)
        fav(1, "MOVIE", 22, at = 20)  // inactive playlist
        fav(1, "SERIES", 31, at = 5)
    }

    @After fun close() { db.close(); Dispatchers.resetMain() }

    private fun exec(sql: String) = db.createStatement().use { it.executeUpdate(sql) }
    private fun fav(profile: Long, type: String, item: Long, at: Long) =
        exec("INSERT INTO favorites (profileId, mediaType, itemId, addedAt) VALUES ($profile, '$type', $item, $at)")

    private fun ids(sql: String, profile: Long, sources: List<Long>): List<Long> {
        val bound = sql.replace(":profileId", profile.toString()).replace(":sourceIds", sources.joinToString(","))
        return db.createStatement().use { st -> st.executeQuery(bound).use { rs -> buildList { while (rs.next()) add(rs.getLong("id")) } } }
    }

    @Test fun `existing favorites appear, newest first, for the active playlist only`() {
        assertEquals(listOf(3L, 1L), ids(FavoritesSql.LIVE, 1, listOf(10)))
        assertEquals(listOf(21L), ids(FavoritesSql.MOVIE, 1, listOf(10)))
        assertEquals(listOf(31L), ids(FavoritesSql.SERIES, 1, listOf(10)))
    }

    @Test fun `another profile's favorites never show`() {
        assertEquals(listOf(1L), ids(FavoritesSql.LIVE, 2, listOf(10)))
        assertEquals(emptyList<Long>(), ids(FavoritesSql.MOVIE, 2, listOf(10, 11)))
    }

    @Test fun `switching to the other playlist shows its favorites`() {
        assertEquals(listOf(2L), ids(FavoritesSql.LIVE, 1, listOf(11)))
        assertEquals(listOf(22L), ids(FavoritesSql.MOVIE, 1, listOf(11)))
    }

    @Test fun `a stale favorite whose item left the catalog is not listed`() {
        assertFalse(99L in ids(FavoritesSql.LIVE, 1, listOf(10, 11)))
    }

    @Test fun `add and remove are reflected by the same query`() {
        fav(1, "SERIES", 32, at = 50)
        assertEquals(listOf(32L, 31L), ids(FavoritesSql.SERIES, 1, listOf(10)))
        exec("DELETE FROM favorites WHERE profileId = 1 AND mediaType = 'SERIES' AND itemId = 32")
        assertEquals(listOf(31L), ids(FavoritesSql.SERIES, 1, listOf(10)))
        // Removing a favorite never touches the item itself.
        val stillThere = db.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM series WHERE id = 32").use { rs -> rs.next(); rs.getInt(1) } }
        assertEquals(1, stillThere)
    }

    @Test fun `an empty category is just an empty list`() {
        assertEquals(emptyList<Long>(), ids(FavoritesSql.SERIES, 1, listOf(11)))
    }

    // ===== Visibility rules (hidden items, hidden categories, Kids, renames, duplicates) =============

    private fun ch(id: Long, cat: Long? = null, name: String = "Ch$id") =
        ChannelEntity(id = id, sourceId = 10, categoryId = cat, name = name, streamUrl = "x")

    private fun visible(items: List<ChannelEntity>, cust: SectionCustomizations, hiddenCats: Set<Long> = emptySet()) =
        FavoritesFilter.visible(items, { it.id }, CustomizeKeys::channel, { it.categoryId }, cust, hiddenCats) { c, n -> c.copy(name = n) }

    @Test fun `hidden items are filtered`() {
        val hidden = SectionCustomizations(hiddenItems = mapOf(CustomizeKeys.channel(ch(2)) to "Ch2"))
        assertEquals(listOf(1L, 3L), visible(listOf(ch(1), ch(2), ch(3)), hidden).map { it.id })
    }

    @Test fun `hidden categories are filtered`() {
        val cats = listOf(CategoryEntity(id = 7, sourceId = 10, mediaType = MediaType.LIVE, name = "Sports"))
        val cust = SectionCustomizations(hiddenCategories = setOf(CustomizeKeys.category(cats[0])))
        val hiddenIds = FavoritesFilter.hiddenCategoryIds(cats, cust, isKids = false)
        assertEquals(listOf(1L), visible(listOf(ch(1), ch(2, cat = 7)), cust, hiddenIds).map { it.id })
    }

    @Test fun `a kids profile never sees favorites from adult categories`() {
        val cats = listOf(
            CategoryEntity(id = 8, sourceId = 10, mediaType = MediaType.LIVE, name = "XXX Adults"),
            CategoryEntity(id = 9, sourceId = 10, mediaType = MediaType.LIVE, name = "Kids"),
        )
        val kidsHidden = FavoritesFilter.hiddenCategoryIds(cats, SectionCustomizations(), isKids = true)
        assertEquals(setOf(8L), kidsHidden)
        assertEquals(listOf(2L), visible(listOf(ch(1, cat = 8), ch(2, cat = 9)), SectionCustomizations(), kidsHidden).map { it.id })
        assertTrue("an adult profile is not restricted", FavoritesFilter.hiddenCategoryIds(cats, SectionCustomizations(), isKids = false).isEmpty())
    }

    @Test fun `renames from Customize are shown and duplicates collapse`() {
        val cust = SectionCustomizations(itemNames = mapOf(CustomizeKeys.channel(ch(1)) to "My BBC"))
        val out = visible(listOf(ch(1), ch(1), ch(3)), cust)
        assertEquals(listOf(1L, 3L), out.map { it.id })
        assertEquals("My BBC", out[0].name)
    }

    // ===== Tabs ===================================================================================

    @Test fun `tabs are Live, Movies, Series in that order`() {
        assertEquals(listOf(FavoritesTab.LIVE, FavoritesTab.MOVIES, FavoritesTab.SERIES), FavoritesTab.entries.toList())
    }

    private fun vm(favoriteDao: FavoriteDao = mockk(relaxed = true)) = FavoritesViewModel(
        settings = mockk(relaxed = true), sourceDao = mockk(relaxed = true), channelDao = mockk(relaxed = true),
        movieDao = mockk(relaxed = true), seriesDao = mockk(relaxed = true), categoryDao = mockk(relaxed = true),
        favoriteDao = favoriteDao, profileDao = mockk(relaxed = true), customize = mockk(relaxed = true),
    )

    @Test fun `tab defaults to Live, is kept while selected, and resets on entry`() {
        val v = vm()
        assertEquals(FavoritesTab.LIVE, v.tab.value)
        v.selectTab(FavoritesTab.SERIES)
        assertEquals(FavoritesTab.SERIES, v.tab.value)
        v.resetTab()
        assertEquals(FavoritesTab.LIVE, v.tab.value)
    }

    @Test fun `remove does nothing without an active profile`() {
        val dao = mockk<FavoriteDao>(relaxed = true)
        vm(dao).remove(FavoritesTab.MOVIES, 21)
        coVerify(exactly = 0) { dao.remove(any(), any(), any()) }
    }

    // ===== Focus after removal =====================================================================

    @Test fun `focus moves to the neighbour after a removal`() {
        assertEquals(2, FavoritesFocus.indexAfterRemoval(removedIndex = 2, newSize = 5)) // the next item slides in
        assertEquals(3, FavoritesFocus.indexAfterRemoval(removedIndex = 4, newSize = 4)) // last removed → new last
        assertEquals(0, FavoritesFocus.indexAfterRemoval(removedIndex = 0, newSize = 1))
    }

    @Test fun `removing the last favorite sends focus to the tab`() {
        assertNull(FavoritesFocus.indexAfterRemoval(removedIndex = 0, newSize = 0))
    }

    // ===== Return navigation =======================================================================

    @Test fun `entering Favorites from elsewhere starts on Live`() {
        val c = FavoritesReturn.onSectionChanged(FavoritesReturnState(), MainSection.FAVORITES)
        assertTrue(c.resetTab)
    }

    @Test fun `live playback returns to Favorites with the tab kept`() {
        var s = FavoritesReturn.openedPlayer(FavoritesReturnState())
        s = FavoritesReturn.onSectionChanged(s, MainSection.LIVE_TV).state // the shell's Sports-style switch
        val back = FavoritesReturn.onSectionChanged(s, MainSection.FAVORITES) // exitPlayer → fullscreenReturnSection
        assertFalse(back.resetTab)
    }

    @Test fun `movie details return to Favorites when closed, keeping the tab`() {
        var s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.MOVIES)
        s = FavoritesReturn.onSectionChanged(s, MainSection.MOVIES).state
        s = FavoritesReturn.onDetails(s, detailsOpen = true, section = MainSection.MOVIES, playerIdle = true).first
        val (after, goBack) = FavoritesReturn.onDetails(s, detailsOpen = false, section = MainSection.MOVIES, playerIdle = true)
        assertTrue(goBack)
        assertFalse(FavoritesReturn.onSectionChanged(after, MainSection.FAVORITES).resetTab)
    }

    @Test fun `series details return the same way`() {
        var s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.SERIES)
        s = FavoritesReturn.onDetails(s, true, MainSection.SERIES, true).first
        assertTrue(FavoritesReturn.onDetails(s, false, MainSection.SERIES, true).second)
    }

    @Test fun `no return while a title from the details page is playing`() {
        var s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.MOVIES)
        s = FavoritesReturn.onDetails(s, true, MainSection.MOVIES, true).first
        assertFalse(FavoritesReturn.onDetails(s, detailsOpen = false, section = MainSection.MOVIES, playerIdle = false).second)
    }

    @Test fun `no return before the details page was seen open`() {
        val s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.MOVIES)
        assertFalse(FavoritesReturn.onDetails(s, detailsOpen = false, section = MainSection.MOVIES, playerIdle = true).second)
    }

    @Test fun `leaving for another destination drops the return path`() {
        var s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.MOVIES)
        s = FavoritesReturn.onDetails(s, true, MainSection.MOVIES, true).first
        s = FavoritesReturn.onSectionChanged(s, MainSection.EPG).state // the customer went to the Guide
        assertNull(s.detailsFrom)
        assertFalse(FavoritesReturn.onDetails(s, false, MainSection.MOVIES, true).second)
        assertTrue("and Favorites is entered fresh later", FavoritesReturn.onSectionChanged(s, MainSection.FAVORITES).resetTab)
    }

    @Test fun `back from details in a different section than Favorites opened does not jump`() {
        var s = FavoritesReturn.openedDetails(FavoritesReturnState(), MainSection.MOVIES)
        s = FavoritesReturn.onDetails(s, true, MainSection.MOVIES, true).first
        s = FavoritesReturn.onSectionChanged(s, MainSection.SERIES).state
        assertFalse(FavoritesReturn.onDetails(s, false, MainSection.SERIES, true).second)
    }

    // ===== Brand isolation ========================================================================

    @Test fun `favorites is reachable only where the nav flag is on`() {
        assertTrue(tv.own.owntv.BuildConfig.NAV_FAVORITES_SEARCH_TABS)
        assertTrue(MainSection.FAVORITES in MainSection.browseOrderFor(favoritesEnabled = true))
        assertFalse(MainSection.FAVORITES in MainSection.browseOrderFor(favoritesEnabled = false))
        assertFalse(MainSection.FAVORITES in MainSection.dynamicVisible(true, true, true, favoritesEnabled = false))
    }

}
