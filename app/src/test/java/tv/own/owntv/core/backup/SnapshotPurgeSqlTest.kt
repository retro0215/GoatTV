package tv.own.owntv.core.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.database.dao.SnapshotPurgeSql
import tv.own.owntv.core.model.MediaType
import java.sql.Connection
import java.sql.DriverManager

/**
 * The re-sync snapshot purge for content order and custom-category members now deletes sets of ids
 * instead of one row at a time (one row scanned every row of the profile: quadratic, and minutes to
 * hours under the writer with tens of thousands of members). The survivors must be exactly what the
 * old one-row statement left. Runs the DAOs' own SQL on a real SQLite. Synthetic data.
 */
class SnapshotPurgeSqlTest {

    private lateinit var db: Connection

    /** The statement the DAOs ran before 4.6.1, one row per call — the reference for "same rule". */
    private val oneRow = { table: String ->
        "DELETE FROM $table WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")"
    }

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            for (t in listOf("channels", "movies", "series")) st.executeUpdate("CREATE TABLE $t (id INTEGER PRIMARY KEY)")
            for (t in listOf("content_order", "custom_category_members")) {
                st.executeUpdate(
                    "CREATE TABLE $t (id INTEGER PRIMARY KEY AUTOINCREMENT, profileId INTEGER NOT NULL, " +
                        "mediaType TEXT NOT NULL, contextKey TEXT NOT NULL, itemId INTEGER NOT NULL)",
                )
                st.executeUpdate("CREATE UNIQUE INDEX ${t}_key ON $t (profileId, mediaType, contextKey, itemId)")
            }
        }
    }

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.executeUpdate(sql) }

    /** Room-style binding: the list parameter expands in place; the one-row reference takes a single id. */
    private fun bind(sql: String, profileId: Long, type: MediaType, itemIds: List<Long>): String {
        val bound = sql
            .replace(":profileId", profileId.toString())
            .replace(":type", "'${type.name}'")
            .replace(":itemIds", itemIds.joinToString(","))
        return if (":itemId" in bound) bound.replace(":itemId", itemIds.single().toString()) else bound
    }

    private fun survivors(table: String): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT profileId, mediaType, contextKey, itemId FROM $table ORDER BY profileId, mediaType, contextKey, itemId").use { rs ->
            buildList { while (rs.next()) add("${rs.getLong(1)}|${rs.getString(2)}|${rs.getString(3)}|${rs.getLong(4)}") }
        }
    }

    /**
     * Two profiles, three media types, several folders. Content rows exist for even ids only, so every
     * odd id is an orphan. The snapshot names some rows of each kind, orphans and live rows alike.
     */
    private fun seed(table: String) {
        for (id in 0L until 40L step 2) for (t in listOf("channels", "movies", "series")) exec("INSERT INTO $t VALUES ($id)")
        for (profile in 1L..2L) for (type in MediaType.values()) for (folder in listOf("a", "b")) for (item in 0L until 40L) {
            exec("INSERT INTO $table (profileId, mediaType, contextKey, itemId) VALUES ($profile, '${type.name}', '$folder', $item)")
        }
    }

    private val snapshot: List<SnapshotRow> = buildList {
        for (kind in listOf("order", "member")) for (profile in 1L..2L) for (type in listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)) {
            for (item in 0L until 30L) add(SnapshotRow(kind, profile, type, item))
            add(SnapshotRow(kind, profile, type, 5L)) // a repeated id is harmless
        }
    }

    private fun purgeOneRow(table: String, kind: String) =
        snapshot.filter { it.kind == kind }.forEach { exec(bind(oneRow(table), it.profileId, it.type, listOf(it.itemId))) }

    private fun purgeSets(table: String, kind: String, sql: String, chunk: Int) =
        snapshotPurgeBatches(snapshot, setOf(kind), chunk).forEach { exec(bind(sql, it.profileId, it.type, it.itemIds)) }

    private fun assertSameSurvivors(table: String, kind: String, sql: String, chunk: Int) {
        seed(table)
        purgeOneRow(table, kind)
        val expected = survivors(table)
        exec("DELETE FROM $table")
        db.createStatement().use { st -> for (t in listOf("channels", "movies", "series")) st.executeUpdate("DELETE FROM $t") }

        seed(table)
        purgeSets(table, kind, sql, chunk)
        assertEquals(expected, survivors(table))
        // And the rule really did its job, the same way: orphans named in the snapshot go (every folder),
        // rows whose content exists stay, and orphans the snapshot does not name stay too.
        assertTrue("named orphan removed", "1|LIVE|a|1" !in expected && "2|SERIES|b|29" !in expected)
        assertTrue("live content kept", "1|LIVE|a|2" in expected && "2|MOVIE|b|28" in expected)
        assertTrue("orphan outside the snapshot kept", "1|LIVE|a|31" in expected)
    }

    @Test
    fun `content order keeps exactly what the one-row purge kept`() {
        assertSameSurvivors("content_order", "order", SnapshotPurgeSql.CONTENT_ORDER_ORPHANS, chunk = SnapshotPurgeSql.CHUNK)
    }

    @Test
    fun `custom-category members keep exactly what the one-row purge kept`() {
        assertSameSurvivors("custom_category_members", "member", SnapshotPurgeSql.CUSTOM_CATEGORY_MEMBER_ORPHANS, chunk = SnapshotPurgeSql.CHUNK)
    }

    @Test
    fun `small chunks give the same survivors as one statement`() {
        assertSameSurvivors("custom_category_members", "member", SnapshotPurgeSql.CUSTOM_CATEGORY_MEMBER_ORPHANS, chunk = 7)
    }

    @Test
    fun `batches group by kind, profile and type, each id once, within the chunk size`() {
        val rows = listOf(
            SnapshotRow("member", 1, MediaType.LIVE, 1), SnapshotRow("member", 1, MediaType.LIVE, 2),
            SnapshotRow("member", 1, MediaType.LIVE, 2), SnapshotRow("member", 1, MediaType.LIVE, 3),
            SnapshotRow("member", 2, MediaType.LIVE, 1), SnapshotRow("order", 1, MediaType.MOVIE, 9),
            SnapshotRow("fav", 1, MediaType.LIVE, 4),
        )
        val batches = snapshotPurgeBatches(rows, setOf("member", "order"), chunk = 2)
        assertEquals(
            listOf(
                SnapshotPurgeBatch("member", 1, MediaType.LIVE, listOf(1, 2)),
                SnapshotPurgeBatch("member", 1, MediaType.LIVE, listOf(3)),
                SnapshotPurgeBatch("member", 2, MediaType.LIVE, listOf(1)),
                SnapshotPurgeBatch("order", 1, MediaType.MOVIE, listOf(9)),
            ),
            batches,
        )
    }

    @Test
    fun `a large purge stays within SQLite's bound-value limit`() {
        val rows = (1L..5_000L).map { SnapshotRow("member", 1, MediaType.LIVE, it) }
        val batches = snapshotPurgeBatches(rows, setOf("member"))
        assertTrue(batches.all { it.itemIds.size + 2 <= 999 })
        assertEquals(5_000, batches.sumOf { it.itemIds.size })
    }
}
