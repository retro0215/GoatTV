package tv.own.owntv.core.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * What a "moved" film costs to write on refresh — on the movies table exactly as the app creates it
 * (Room schema v38: 15 indexes, foreign keys, the external-content FTS4 table and its sync triggers).
 *
 * A provider that adds a few titles near the top of its list shifts the position of every title after
 * them. Before 4.6.1 RC3 each such row went through Room's @Update, which sets every column: the row,
 * all 15 index entries, two foreign-key checks and the FTS delete/insert per film (about 9.5 s per 5,000
 * films on the onn 4K Plus). A position-only change now writes `sortOrder` alone. This test pins both
 * the result (same order, search still works) and that the narrow write is the cheaper one.
 */
class SortOrderWriteCostTest {

    private lateinit var db: Connection
    private val rows = 20_000

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate("PRAGMA foreign_keys = ON")
            st.executeUpdate("CREATE TABLE sources (id INTEGER PRIMARY KEY)")
            st.executeUpdate("CREATE TABLE categories (id INTEGER PRIMARY KEY)")
            st.executeUpdate(
                "CREATE TABLE movies (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `categoryId` INTEGER, " +
                    "`name` TEXT NOT NULL, `posterUrl` TEXT, `backdropUrl` TEXT, `year` INTEGER, `rating` REAL, `durationSecs` INTEGER, " +
                    "`plot` TEXT, `streamUrl` TEXT NOT NULL, `containerExt` TEXT, `remoteId` TEXT, `addedAt` INTEGER, `sortOrder` INTEGER NOT NULL, " +
                    "`httpHeaders` TEXT, `drmConfig` TEXT, `contentHash` INTEGER NOT NULL DEFAULT 0, `canonicalTitle` TEXT NOT NULL DEFAULT '', " +
                    "`titleSignature` TEXT NOT NULL DEFAULT '', `parsedYear` INTEGER, `providerLanguage` TEXT, `qualityRank` INTEGER NOT NULL DEFAULT 0, " +
                    "`advertisedCapabilities` TEXT, FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            listOf(
                "sourceId", "categoryId", "name", "sourceId, name", "categoryId, name", "sourceId, sortOrder, name",
                "categoryId, sortOrder, name", "sourceId, rating, name", "categoryId, rating, name", "sourceId, addedAt, sortOrder",
                "categoryId, addedAt, sortOrder", "sourceId, titleSignature, parsedYear",
            ).forEachIndexed { i, cols -> st.executeUpdate("CREATE INDEX idx_$i ON movies ($cols)") }
            st.executeUpdate("CREATE UNIQUE INDEX idx_remote ON movies (sourceId, remoteId)")
            st.executeUpdate("CREATE VIRTUAL TABLE movies_fts USING FTS4(`name` TEXT NOT NULL, content=`movies`)")
            st.executeUpdate("CREATE TRIGGER fts_bu BEFORE UPDATE ON `movies` BEGIN DELETE FROM `movies_fts` WHERE `docid`=OLD.`rowid`; END")
            st.executeUpdate("CREATE TRIGGER fts_bd BEFORE DELETE ON `movies` BEGIN DELETE FROM `movies_fts` WHERE `docid`=OLD.`rowid`; END")
            st.executeUpdate("CREATE TRIGGER fts_au AFTER UPDATE ON `movies` BEGIN INSERT INTO `movies_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
            st.executeUpdate("CREATE TRIGGER fts_ai AFTER INSERT ON `movies` BEGIN INSERT INTO `movies_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
            st.executeUpdate("INSERT INTO sources VALUES (1)")
            for (c in 1..30) st.executeUpdate("INSERT INTO categories VALUES ($c)")
        }
        tx {
            db.prepareStatement(
                "INSERT INTO movies (sourceId, categoryId, name, plot, streamUrl, remoteId, addedAt, sortOrder, rating, titleSignature, parsedYear) " +
                    "VALUES (1,?,?,?,?,?,?,?,?,?,?)",
            ).use { ps ->
                for (i in 0 until rows) {
                    ps.setInt(1, i % 30 + 1); ps.setString(2, "Synthetic Film $i Part ${i % 7}")
                    ps.setString(3, "A synthetic plot line for title $i, long enough to look like a provider's description.")
                    ps.setString(4, "http://panel.invalid/movie/x/y/$i.mkv"); ps.setString(5, "r$i"); ps.setLong(6, 1_700_000_000L + i)
                    ps.setInt(7, i); ps.setDouble(8, (i % 10).toDouble()); ps.setString(9, "syntheticfilm$i"); ps.setInt(10, 1990 + i % 30)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    @After fun close() = db.close()

    private fun tx(block: () -> Unit) {
        db.autoCommit = false
        try { block(); db.commit() } finally { db.autoCommit = true }
    }

    /** Room's @Update for MovieEntity: every column set, by primary key. */
    private val fullRowUpdate =
        "UPDATE movies SET id=?, sourceId=?, categoryId=?, name=?, posterUrl=?, backdropUrl=?, year=?, rating=?, durationSecs=?, plot=?, " +
            "streamUrl=?, containerExt=?, remoteId=?, addedAt=?, sortOrder=?, httpHeaders=?, drmConfig=?, contentHash=?, canonicalTitle=?, " +
            "titleSignature=?, parsedYear=?, providerLanguage=?, qualityRank=?, advertisedCapabilities=? WHERE id=?"

    /** Every film shifted down by two places, as when a provider adds two titles at the top. */
    private fun shiftAllFull(): Long {
        val started = System.nanoTime()
        tx {
            db.prepareStatement("SELECT * FROM movies").use { sel ->
                db.prepareStatement(fullRowUpdate).use { up ->
                    sel.executeQuery().use { rs ->
                        while (rs.next()) {
                            for (c in 1..24) up.setObject(c, rs.getObject(c))
                            up.setInt(15, rs.getInt("sortOrder") + 2)
                            up.setLong(25, rs.getLong("id"))
                            up.addBatch()
                        }
                    }
                    up.executeBatch()
                }
            }
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun shiftAllNarrow(): Long {
        val started = System.nanoTime()
        tx {
            db.prepareStatement("UPDATE movies SET sortOrder = ? WHERE id = ?").use { up ->
                db.createStatement().use { st ->
                    st.executeQuery("SELECT id, sortOrder FROM movies").use { rs ->
                        while (rs.next()) { up.setInt(1, rs.getInt(2) + 2); up.setLong(2, rs.getLong(1)); up.addBatch() }
                    }
                }
                up.executeBatch()
            }
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun providerOrder(): List<Long> = db.createStatement().use { st ->
        st.executeQuery("SELECT id FROM movies WHERE sourceId = 1 ORDER BY sortOrder, name").use { rs ->
            buildList { while (rs.next()) add(rs.getLong(1)) }
        }
    }

    private fun searchCount(term: String): Int = db.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM movies WHERE id IN (SELECT rowid FROM movies_fts WHERE movies_fts MATCH '$term')").use { it.next(); it.getInt(1) }
    }

    @Test fun `a position-only write keeps provider order and search exactly as a full-row write does`() {
        val before = providerOrder()
        shiftAllNarrow()
        assertEquals(before, providerOrder())
        assertEquals(rows / 7 + 1, searchCount("Part 0"))
        assertEquals(1, searchCount("\"Synthetic Film 12345\""))
        shiftAllFull()
        assertEquals(before, providerOrder())
        assertEquals(rows / 7 + 1, searchCount("Part 0"))
    }

    @Test fun `a position-only write is cheaper than Room's full-row update`() {
        // Warm-up pass each, then measure, so neither side pays for first-touch page loads.
        shiftAllNarrow(); shiftAllFull()
        val full = shiftAllFull()
        val narrow = shiftAllNarrow()
        println("SortOrderWriteCost rows=$rows fullRowMs=$full sortOrderOnlyMs=$narrow ratio=${"%.2f".format(full.toDouble() / narrow.coerceAtLeast(1))}")
        assertTrue("sortOrder-only ($narrow ms) should beat the full-row update ($full ms)", narrow < full)
    }
}
