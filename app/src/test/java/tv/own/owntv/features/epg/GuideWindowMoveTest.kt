package tv.own.owntv.features.epg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.database.dao.GuideSql
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 4.6.0: the guide window moving on at a half-hour boundary keeps the loaded programmes and reads only what
 * the window gained; an interrupted preload continues where it stopped. Checked on a real SQLite with the
 * SQL the DAO runs (GuideSql): kept + topped up is what a full read of the new window gives.
 */
class GuideWindowMoveTest {

    private data class P(val id: Long, val key: String, val start: Long, val stop: Long)

    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE epg_programmes (id INTEGER PRIMARY KEY AUTOINCREMENT, sourceId INTEGER NOT NULL, epgChannelId TEXT NOT NULL, " +
                    "startMs INTEGER NOT NULL, stopMs INTEGER NOT NULL, title TEXT NOT NULL, description TEXT, contentHash TEXT)",
            )
            it.executeUpdate("CREATE INDEX index_epg_programmes_epgChannelId_startMs ON epg_programmes (epgChannelId, startMs)")
            it.executeUpdate("CREATE INDEX index_epg_programmes_stopMs ON epg_programmes (stopMs)")
        }
    }

    @After fun close() = db.close()

    private val min = 60_000L
    private val halfHour = 30 * min

    private fun add(source: Long, key: String, start: Long, stop: Long) =
        db.prepareStatement("INSERT INTO epg_programmes (sourceId, epgChannelId, startMs, stopMs, title) VALUES (?, ?, ?, ?, 't')").use { st ->
            st.setLong(1, source); st.setString(2, key); st.setLong(3, start); st.setLong(4, stop); st.executeUpdate()
        }

    private fun rows(sql: String): List<P> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(P(rs.getLong("id"), rs.getString("epgChannelId"), rs.getLong("startMs"), rs.getLong("stopMs"))) } }
    }

    /** The preload: every page of [GuideSql.WINDOW_PAGE], grouped like loadWindowGrouped. */
    private fun window(ids: List<Long>, from: Long, to: Long, page: Int = 7): Map<String, List<P>> {
        val all = ArrayList<P>()
        var after = 0L
        while (true) {
            val got = rows(
                GuideSql.WINDOW_PAGE.replace(":sourceIds", ids.joinToString(",")).replace(":from", "$from").replace(":to", "$to")
                    .replace(":afterId", "$after").replace(":limit", "$page"),
            )
            all += got
            if (got.size < page) break
            after = got.last().id
        }
        return all.groupBy { it.key }.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    /** The top-up: [GuideSql.WINDOW_TOP_UP] in key chunks, grouped like loadWindowTopUp. */
    private fun topUp(ids: List<Long>, keys: Set<String>, from: Long, to: Long): Map<String, List<P>> =
        keys.toList().chunked(3).flatMap { chunk ->
            rows(
                GuideSql.WINDOW_TOP_UP.replace(":epgKeys", chunk.joinToString(",") { "'" + it.replace("'", "''") + "'" })
                    .replace(":sourceIds", ids.joinToString(",")).replace(":from", "$from").replace(":to", "$to"),
            )
        }.groupBy { it.key }.mapValues { (_, v) -> v.sortedBy { it.start } }

    private fun keys(ids: List<Long>): Set<String> = db.createStatement().use { st ->
        st.executeQuery(GuideSql.GUIDE_KEYS.replace(":sourceIds", ids.joinToString(","))).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun merge(cached: Map<String, List<P>>, added: Map<String, List<P>>, windowStart: Long) =
        GuideWindowMove.merge(cached, added, windowStart, { it.id }, { it.start }, { it.stop })

    /** A random lineup: back-to-back programmes of 5-180 min per key, a few 24 h fillers, a second feed. */
    private fun lineup(seed: Long, from: Long, to: Long) {
        val rnd = java.util.Random(seed)
        for (k in 0 until 40) {
            var t = from + rnd.nextInt(120) * min
            while (t < to) {
                val len = if (rnd.nextInt(30) == 0) 24 * 60 * min else (5 + rnd.nextInt(176)) * min
                add(if (k % 5 == 0) 3L else 1L, "ch$k.tv", t, t + len)
                t += len + if (rnd.nextInt(10) == 0) rnd.nextInt(90) * min else 0
            }
        }
        add(9L, "other.tv", from, to) // an unrequested feed
    }

    // ---- Kept + topped up == a full read -------------------------------------------------------------------

    @Test fun `half an hour later - the kept window plus the top-up is exactly a full read`() {
        val t0 = 1_800_000_000_000L - (1_800_000_000_000L % halfHour)
        lineup(46, t0 - 3 * 24 * 60 * min, t0 + 3 * 24 * 60 * min)
        val ids = listOf(1L, 3L)
        val lookback = 2 * 24 * 60 * min
        val grid = 24 * 60 * min
        // Loaded at t0: the forward window, then the lookback (pass 2), as load() does.
        var cache = window(ids, t0, t0 + grid)
        cache = merge(cache, window(ids, t0 - lookback, t0), t0 - lookback)
        var covered = (t0 - lookback) to (t0 + grid)
        for (step in 1..5) { // five boundaries in a row
            val now = t0 + step * halfHour
            val plan = GuideWindowMove.plan(covered.first, covered.second, now, now + grid, dataUnchanged = true, sourcesChanged = false, importRunning = false, keysKnown = true)
            assertNotNull(plan)
            assertEquals(covered.second, plan!!.fetchFrom)
            cache = merge(cache, topUp(ids, keys(ids), plan.fetchFrom, plan.fetchTo), now - lookback)
            covered = maxOf(covered.first, now - lookback) to (now + grid)
            val full = merge(window(ids, now, now + grid), window(ids, now - lookback, now), now - lookback)
            assertEquals("step $step", full, cache)
        }
    }

    @Test fun `only the forward window loaded - the move still has every programme of the new forward window`() {
        val t0 = 1_800_000_000_000L - (1_800_000_000_000L % halfHour)
        lineup(7, t0 - 24 * 60 * min, t0 + 3 * 24 * 60 * min)
        val ids = listOf(1L, 3L)
        val grid = 24 * 60 * min
        val cache = window(ids, t0, t0 + grid) // the lookback pass never ran (cancelled)
        val now = t0 + halfHour
        val plan = GuideWindowMove.plan(t0, t0 + grid, now, now + grid, true, false, false, true)!!
        val moved = merge(cache, topUp(ids, keys(ids), plan.fetchFrom, plan.fetchTo), now - 24 * 60 * min)
        val forward = window(ids, now, now + grid)
        for ((k, list) in forward) assertTrue(k, moved[k].orEmpty().containsAll(list))
        // Nothing outside the new window: every kept programme overlaps it.
        assertTrue(moved.values.flatten().all { it.stop > now - 24 * 60 * min && it.start < now + grid })
    }

    @Test fun `a programme running across the old end is kept once, and programmes that ended go`() {
        val t0 = 0L
        val cached = mapOf("a" to listOf(P(1, "a", -90 * min, -10 * min), P(2, "a", -10 * min, 50 * min)))
        val added = mapOf("a" to listOf(P(3, "a", 50 * min, 80 * min)), "b" to listOf(P(4, "b", 40 * min, 70 * min)))
        val merged = merge(cached, added, windowStart = t0)
        assertEquals(listOf(2L, 3L), merged.getValue("a").map { it.id })
        assertEquals(listOf(4L), merged.getValue("b").map { it.id })
        assertFalse("a key with nothing left is dropped, as a full read would", merge(mapOf("c" to listOf(P(5, "c", -60 * min, -1))), emptyMap(), 0L).containsKey("c"))
    }

    @Test fun `the full read runs whenever the kept window can't be trusted or reached`() {
        val grid = 24 * 60 * min
        fun plan(from: Long?, to: Long?, data: Boolean = true, sources: Boolean = false, import: Boolean = false, keys: Boolean = true) =
            GuideWindowMove.plan(from, to, halfHour, halfHour + grid, data, sources, import, keys)
        assertNotNull(plan(0L, grid))
        assertNull("nothing loaded", plan(null, null))
        assertNull("the stored guide changed (a sync)", plan(0L, grid, data = false))
        assertNull("other sources", plan(0L, grid, sources = true))
        assertNull("an import is running", plan(0L, grid, import = true))
        assertNull("guide keys unknown", plan(0L, grid, keys = false))
        assertNull("loaded range starts after now", plan(2 * halfHour, grid))
        assertNull("loaded range ends before now", plan(-grid, 0L))
        assertEquals("already loaded to the end: nothing to read", GuideWindowMove.Plan(halfHour + grid, halfHour + grid),
            GuideWindowMove.plan(0L, halfHour + grid + 5, halfHour, halfHour + grid, true, false, false, true))
    }

    // ---- An interrupted preload continues ------------------------------------------------------------------

    @Test fun `a cancelled preload picks up after its last page, for the same read only`() {
        val progress = GuideWindowProgress<Int>()
        val key = GuideWindowProgress.Key(listOf(1L, 3L), 100L, 200L, stored = 5_000, maxProgrammeId = 9_000L)
        val run = progress.resume(key)
        run.add(listOf(1, 2, 3), lastId = 3L)
        // Cancelled here (left the Guide). The next open of the same window:
        val again = progress.resume(key)
        assertSame(run, again)
        assertEquals(3L, again.afterId)
        assertEquals(listOf(1, 2, 3), again.rows)
        // Another window, sources or stored guide: from the start.
        for (other in listOf(key.copy(to = 230L), key.copy(sourceIds = listOf(1L)), key.copy(stored = 5_001), key.copy(maxProgrammeId = 9_001L))) {
            assertEquals(0L, progress.resume(other).afterId)
        }
    }

    @Test fun `a finished read is not resumed`() {
        val progress = GuideWindowProgress<Int>()
        val key = GuideWindowProgress.Key(listOf(1L), 0L, 1L, 1, 1L)
        val run = progress.resume(key).also { it.add(listOf(7), 7L) }
        progress.finished(run)
        assertEquals(0L, progress.resume(key).afterId)
        progress.resume(key).add(listOf(8), 8L)
        progress.clear()
        assertEquals(0L, progress.resume(key).afterId)
    }

    // ---- Wiring --------------------------------------------------------------------------------------

    private val vm = File("src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt").readText().replace("\r\n", "\n")

    @Test fun `the view model keeps the window on a move and reads only what it gained`() {
        val load = vm.substringAfter("    fun load() {")
        val moved = load.substringAfter("} else if (move != null && loaded != null && guideKeys != null) {").substringBefore("} else if (windowChanged) {")
        assertTrue("loadWindowTopUp(ids, guideKeys, move.fetchFrom, move.fetchTo)" in moved)
        assertFalse("the loaded rows are not dropped", "rowCache.clear()" in moved)
        assertTrue("GuideWindowMove.merge(" in moved && "_cacheRevision.value++" in moved)
        // The full read still runs for everything else, and records what it loaded.
        val full = load.substringAfter("} else if (windowChanged) {").substringBefore("val hasEpg")
        assertTrue("rowCache.clear()" in full && "coverage = Coverage(ids, nowAligned, windowEnd" in full)
        // The lookback reads only the part not loaded yet.
        assertTrue("GuideWindowProgress.Key(ids, windowStart, backTo," in load)
        // A sync, an import, other sources or a released cache forget what was loaded.
        assertTrue(vm.substringAfter("private fun releaseGridCache()").substringBefore("\n    }\n").let { "coverage = null" in it && "preloadProgress.clear()" in it })
    }

    @Test fun `the top-up reads by guide key and start time, in chunks under the variable limit`() {
        assertTrue("WHERE epgChannelId IN (:epgKeys) AND sourceId IN (:sourceIds) AND startMs >= :from AND startMs < :to" in GuideSql.WINDOW_TOP_UP)
        val topUp = vm.substringAfter("private suspend fun loadWindowTopUp(").substringBefore("\n        }\n")
        assertTrue("chunked(GuideChannelQuery.CHUNK)" in topUp && "inputGate.awaitQuiet()" in topUp)
        val dao = File("src/main/java/tv/own/owntv/core/database/dao/EpgDao.kt").readText()
        assertTrue("@Query(GuideSql.WINDOW_PAGE)" in dao && "@Query(GuideSql.WINDOW_TOP_UP)" in dao)
    }

    @Test fun `the preload pages run one at a time and continue an interrupted read`() {
        val preload = vm.substringAfter("private suspend fun loadWindowGrouped(").substringBefore("/** 4.6.0: what a moved window gained")
        assertTrue(preload.indexOf("preloadProgress.lock.withLock") in 1 until preload.indexOf("programmesInWindowPage"))
        assertTrue("preloadProgress.resume(key)" in preload && "run.afterId" in preload && "preloadProgress.finished(run)" in preload)
    }
}
