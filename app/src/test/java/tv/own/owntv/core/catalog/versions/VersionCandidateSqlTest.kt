package tv.own.owntv.core.catalog.versions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The DAOs' own version and backfill SQL on a real SQLite: what it returns, that it is served by the
 * indexes the app already has (no catalogue scan on a details page), and how long it takes on a catalogue
 * the size of a large provider's (200,000 films over three playlists).
 */
class VersionCandidateSqlTest {

    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE categories (id INTEGER PRIMARY KEY, sourceId INTEGER NOT NULL, name TEXT NOT NULL, remoteId TEXT)")
            for (t in listOf("movies", "series")) {
                st.executeUpdate(
                    "CREATE TABLE $t (id INTEGER PRIMARY KEY AUTOINCREMENT, sourceId INTEGER NOT NULL, categoryId INTEGER, " +
                        "name TEXT NOT NULL, year INTEGER, remoteId TEXT, canonicalTitle TEXT NOT NULL DEFAULT '', " +
                        "titleSignature TEXT NOT NULL DEFAULT '', parsedYear INTEGER, providerLanguage TEXT, " +
                        "qualityRank INTEGER NOT NULL DEFAULT 0, advertisedCapabilities TEXT)",
                )
                // The index the app's entities declare (ContentEntities: Index(sourceId, titleSignature, parsedYear)).
                st.executeUpdate("CREATE INDEX index_${t}_sourceId_titleSignature_parsedYear ON $t (sourceId, titleSignature, parsedYear)")
            }
        }
    }

    @After fun close() = db.close()

    /** Room-style binding: list parameters expand in place; the values here are plain numbers and text. */
    private fun bind(sql: String, sourceIds: List<Long> = emptyList(), signature: String = "", limit: Int = 0, after: Long = 0) = sql
        .replace(":sourceIds", sourceIds.joinToString(","))
        .replace(":signature", "'$signature'")
        .replace(":limit", limit.toString())
        .replace(":after", after.toString())

    private fun plan(sql: String): String = db.createStatement().use { st ->
        st.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs -> buildString { while (rs.next()) appendLine(rs.getString("detail")) } }
    }

    private fun insertMovie(sourceId: Long, categoryId: Long?, name: String, signature: String, year: Int?, rank: Int) =
        db.prepareStatement(
            "INSERT INTO movies (sourceId, categoryId, name, remoteId, titleSignature, parsedYear, qualityRank) VALUES (?,?,?,?,?,?,?)",
        ).use { st ->
            st.setLong(1, sourceId)
            if (categoryId == null) st.setNull(2, java.sql.Types.INTEGER) else st.setLong(2, categoryId)
            st.setString(3, name); st.setString(4, name.hashCode().toString()); st.setString(5, signature)
            if (year == null) st.setNull(6, java.sql.Types.INTEGER) else st.setInt(6, year)
            st.setInt(7, rank); st.executeUpdate()
        }

    private fun candidates(sql: String, sourceIds: List<Long>, signature: String): List<Map<String, Any?>> =
        db.createStatement().use { st ->
            st.executeQuery(bind(sql, sourceIds, signature, VersionSql.CANDIDATE_LIMIT)).use { rs ->
                val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                buildList { while (rs.next()) add(cols.associateWith { rs.getObject(it) }) }
            }
        }

    @Test
    fun `candidates come from the given playlists only, with their category names`() {
        db.createStatement().use { it.executeUpdate("INSERT INTO categories VALUES (7, 2, '4K MOVIES', 'c7')") }
        insertMovie(1, null, "Dune (2021) FHD", "dune", 2021, 3)
        insertMovie(2, 7, "Dune (2021)", "dune", 2021, 0)
        insertMovie(3, null, "Dune (2021) 4K", "dune", 2021, 4)
        insertMovie(1, null, "Arrival (2016)", "arrival", 2016, 0)

        val rows = candidates(VersionSql.MOVIE_CANDIDATES, listOf(1, 2), "dune")
        assertEquals(listOf("Dune (2021) FHD", "Dune (2021)"), rows.map { it["name"] })
        assertEquals("4K MOVIES", rows[1]["categoryName"])
        assertEquals("c7", rows[1]["categoryRemoteId"])
        // Every column the projection class reads is present.
        assertEquals(
            setOf("id", "sourceId", "categoryId", "categoryName", "categoryRemoteId", "name", "remoteId", "titleSignature",
                "parsedYear", "providerLanguage", "qualityRank", "advertisedCapabilities"),
            rows.first().keys,
        )
    }

    @Test
    fun `candidate lookups are index seeks, never a scan`() {
        for (sql in listOf(VersionSql.MOVIE_CANDIDATES, VersionSql.SERIES_CANDIDATES)) {
            val p = plan(bind(sql, listOf(1, 2, 3), "dune", VersionSql.CANDIDATE_LIMIT))
            assertTrue(p, p.contains("USING INDEX index_") && p.contains("sourceId_titleSignature_parsedYear"))
            assertTrue(p, p.contains("sourceId=? AND titleSignature=?"))
            assertTrue("no full scan of the content table: $p", !Regex("""SCAN (x|movies|series)\b""").containsMatchIn(p))
        }
    }

    @Test
    fun `backfill pages are rowid ranges, resumable, never a whole-table read`() {
        for (sql in listOf(VersionSql.MOVIE_TITLE_PAGE, VersionSql.MOVIE_TITLE_MISSING_PAGE, VersionSql.SERIES_TITLE_PAGE, VersionSql.SERIES_TITLE_MISSING_PAGE)) {
            val p = plan(bind(sql, limit = 500, after = 1000))
            assertTrue(p, p.contains("INTEGER PRIMARY KEY (rowid>?)"))
        }
    }

    @Test
    fun `the derived-title update touches only the derived columns`() {
        insertMovie(1, null, "Dune (2021) FHD", "", null, 0)
        val sql = VersionSql.MOVIE_UPDATE_DERIVED
            .replace(":canonicalTitle", "'Dune'").replace(":titleSignature", "'dune'").replace(":parsedYear", "2021")
            .replace(":providerLanguage", "NULL").replace(":qualityRank", "3").replace(":advertisedCapabilities", "NULL")
            .replace(":id", "1")
        db.createStatement().use { it.executeUpdate(sql) }
        db.createStatement().use { st ->
            st.executeQuery("SELECT name, titleSignature, parsedYear, qualityRank, remoteId FROM movies WHERE id = 1").use { rs ->
                rs.next()
                assertEquals("Dune (2021) FHD", rs.getString(1))
                assertEquals("dune", rs.getString(2))
                assertEquals(2021, rs.getInt(3))
                assertEquals(3, rs.getInt(4))
                assertEquals("Dune (2021) FHD".hashCode().toString(), rs.getString(5))
            }
        }
    }

    @Test
    fun `a large catalogue answers a version lookup in milliseconds`() {
        db.autoCommit = false
        db.prepareStatement("INSERT INTO movies (sourceId, name, remoteId, titleSignature, parsedYear, qualityRank) VALUES (?,?,?,?,?,?)").use { st ->
            for (i in 0 until 200_000) {
                st.setLong(1, (i % 3 + 1).toLong())
                st.setString(2, "Title $i"); st.setString(3, "r$i")
                st.setString(4, "title${i / 3}") // three playlists, each title in each
                st.setInt(5, 1990 + i % 30); st.setInt(6, i % 5)
                st.addBatch()
                if (i % 10_000 == 9_999) st.executeBatch()
            }
            st.executeBatch()
        }
        db.commit(); db.autoCommit = true
        db.createStatement().use { it.executeUpdate("ANALYZE") }

        candidates(VersionSql.MOVIE_CANDIDATES, listOf(1, 2, 3), "title500") // warm up
        val runs = 50
        val started = System.nanoTime()
        var found = 0
        repeat(runs) { found += candidates(VersionSql.MOVIE_CANDIDATES, listOf(1, 2, 3), "title${it * 997}").size }
        val avgMs = (System.nanoTime() - started) / 1_000_000.0 / runs
        assertEquals(runs * 3, found)
        println("version lookup on 200,000 rows: average %.3f ms over %d lookups".format(avgMs, runs))
        assertTrue("average lookup ${"%.2f".format(avgMs)} ms on 200,000 rows", avgMs < 20.0)
    }
}
