package tv.own.owntv.features.epg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.database.dao.GuideChannelCandidate
import tv.own.owntv.core.database.dao.GuideSql
import java.sql.Connection
import java.sql.DriverManager

/**
 * Phase 4C: the Guide's new channel query gives exactly what Phase 4B's one statement gave. Both run on a
 * real SQLite, using the SQL the DAOs run (GuideSql): the old CHANNELS_WITH_GUIDE against the new path —
 * GUIDE_KEYS once, the ordered GUIDE_CANDIDATES, GuideChannelQuery.select, CHANNELS_BY_IDS in chunks.
 */
class GuideChannelQuerySqlTest {

    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate("CREATE TABLE channels (id INTEGER PRIMARY KEY, sourceId INTEGER NOT NULL, name TEXT NOT NULL, number INTEGER, epgChannelId TEXT)")
            it.executeUpdate("CREATE TABLE epg_programmes (id INTEGER PRIMARY KEY AUTOINCREMENT, sourceId INTEGER NOT NULL, epgChannelId TEXT NOT NULL, startMs INTEGER NOT NULL)")
            it.executeUpdate("CREATE INDEX index_epg_programmes_sourceId_epgChannelId ON epg_programmes (sourceId, epgChannelId)")
        }
    }

    @After fun close() = db.close()

    // ---- helpers -----------------------------------------------------------------------------------------

    private fun channel(id: Long, source: Long, name: String, number: Int?, epg: String?) =
        db.prepareStatement("INSERT INTO channels VALUES (?, ?, ?, ?, ?)").use { st ->
            st.setLong(1, id); st.setLong(2, source); st.setString(3, name)
            if (number == null) st.setNull(4, java.sql.Types.INTEGER) else st.setInt(4, number)
            if (epg == null) st.setNull(5, java.sql.Types.VARCHAR) else st.setString(5, epg)
            st.executeUpdate()
        }

    /** Programme keys are stored normalized (trimmed, lower case), as the importer writes them. */
    private fun programme(source: Long, key: String, start: Long = 0) =
        db.prepareStatement("INSERT INTO epg_programmes (sourceId, epgChannelId, startMs) VALUES (?, ?, ?)").use { st ->
            st.setLong(1, source); st.setString(2, key); st.setLong(3, start); st.executeUpdate()
        }

    /** Room-style binding: list parameters expand in place; the test values are plain numbers and text. */
    private fun bind(sql: String, sourceIds: List<Long>, query: String = "", limit: Int = 0, ids: List<Long> = emptyList()) = sql
        .replace(":sourceIds", sourceIds.joinToString(","))
        .replace(":query", "'" + query.replace("'", "''") + "'")
        .replace(":limit", limit.toString())
        .replace(":ids", ids.joinToString(","))

    private fun ids(sql: String): List<Long> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getLong("id")) } }
    }

    private fun old(sourceIds: List<Long>, query: String = "", limit: Int = 50_000) =
        ids(bind(GuideSql.CHANNELS_WITH_GUIDE, sourceIds, query, limit))

    private fun new(sourceIds: List<Long>, query: String = "", limit: Int = 50_000): List<Long> {
        val keys = db.createStatement().use { st ->
            st.executeQuery(bind(GuideSql.GUIDE_KEYS, sourceIds)).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        val candidates = db.createStatement().use { st ->
            st.executeQuery(bind(GuideSql.GUIDE_CANDIDATES, sourceIds, query)).use { rs ->
                buildList { while (rs.next()) add(GuideChannelCandidate(rs.getLong("id"), rs.getString("guideKey"))) }
            }
        }
        val wanted = GuideChannelQuery.select(candidates, keys, limit)
        val rows = GuideChannelQuery.chunks(wanted).flatMap { chunk -> ids(bind(GuideSql.CHANNELS_BY_IDS, sourceIds, ids = chunk)) }
        return GuideChannelQuery.inOrder(wanted, rows) { it }
    }

    // ---- matching edge cases ----------------------------------------------------------------------------

    @Test fun `case and surrounding spaces match, other differences do not, as before`() {
        programme(1, "fox.us"); programme(1, "cnn.us"); programme(1, "étoile.fr"); programme(1, "abc")
        channel(1, 1, "Fox", 1, " FOX.us ")       // matches: trimmed, lower-cased
        channel(2, 1, "CNN", 2, "cnn.us")         // matches as is
        channel(3, 1, "Tab", 3, "\tfox.us")       // TRIM only removes spaces: no match
        channel(4, 1, "Étoile", 4, "ÉTOILE.fr")   // SQLite LOWER is ASCII-only: whatever it gives, both paths agree
        channel(5, 1, "None", 5, null)            // no guide id
        channel(6, 1, "Blank", 6, "")             // blank guide id
        channel(7, 1, "No guide", 7, "nbc.us")    // no programmes
        channel(8, 2, "Other source", 8, "abc")   // not in the requested sources
        assertEquals(old(listOf(1)), new(listOf(1)))
        assertEquals(listOf(1L, 2L), new(listOf(1)).filter { it <= 3 })
    }

    @Test fun `programmes must come from the requested sources`() {
        programme(9, "fox.us") // a feed that is not requested
        channel(1, 1, "Fox", 1, "fox.us")
        assertEquals(emptyList<Long>(), old(listOf(1)))
        assertEquals(old(listOf(1)), new(listOf(1)))
        assertEquals(listOf(1L), new(listOf(1, 9))) // a standalone EPG source's programmes count when requested
        assertEquals(old(listOf(1, 9)), new(listOf(1, 9)))
    }

    @Test fun `guide order with unnumbered channels, the name search and the limit are kept`() {
        listOf("a", "b", "c", "d").forEach { programme(1, it) }
        channel(1, 1, "Delta", 4, "d")
        channel(2, 1, "Alpha", null, "a")  // NULL numbers sort first, as before
        channel(3, 1, "Bravo", 2, "b")
        channel(4, 1, "Charlie", 2, "c")   // same number: by name
        assertEquals(old(listOf(1)), new(listOf(1)))
        assertEquals(listOf(2L, 3L, 4L, 1L), new(listOf(1)))
        assertEquals(old(listOf(1), query = "ha"), new(listOf(1), query = "ha"))
        assertEquals(old(listOf(1), limit = 2), new(listOf(1), limit = 2))
        assertEquals(2, new(listOf(1), limit = 2).size)
    }

    @Test fun `the same list as before on a large random lineup, across searches and limits`() {
        val rnd = java.util.Random(46)
        val keys = (0 until 600).map { "ch$it.tv" }
        // Guide data for two thirds of the keys, from the playlist (1) and a standalone feed (3); some from an
        // unrequested feed (9) only.
        keys.forEachIndexed { i, k ->
            when (i % 3) {
                0 -> programme(1, k)
                1 -> { programme(3, k); programme(3, k, 1) }
                else -> if (i % 2 == 0) programme(9, k)
            }
        }
        var id = 1L
        repeat(2_500) { n ->
            val k = keys[rnd.nextInt(keys.size)]
            val variant = when (rnd.nextInt(5)) {
                0 -> k.uppercase(); 1 -> " $k "; 2 -> k; 3 -> k.replaceFirstChar { it.uppercase() }; else -> if (rnd.nextBoolean()) null else ""
            }
            val number = if (rnd.nextInt(10) == 0) null else rnd.nextInt(400)
            channel(id++, if (rnd.nextInt(8) == 0) 2L else 1L, "Channel ${n.toString().padStart(5, '0')}", number, variant)
        }
        for (sources in listOf(listOf(1L), listOf(1L, 3L), listOf(1L, 2L, 3L))) {
            for (q in listOf("", "1", "0042")) {
                for (limit in listOf(50_000, 300, 1)) {
                    assertEquals("sources=$sources q=$q limit=$limit", old(sources, q, limit), new(sources, q, limit))
                }
            }
        }
        assertTrue(new(listOf(1L, 3L)).size > 500)
    }

    @Test fun `full rows are read in chunks under the SQLite variable limit`() {
        programme(1, "k")
        (1L..2_000L).forEach { channel(it, 1, "C$it", it.toInt(), "k") }
        val wanted = (1L..2_000L).toList()
        val chunks = GuideChannelQuery.chunks(wanted)
        assertTrue(chunks.all { it.size <= 900 })
        assertEquals(wanted, chunks.flatten())
        assertEquals(old(listOf(1)), new(listOf(1)))
    }
}
