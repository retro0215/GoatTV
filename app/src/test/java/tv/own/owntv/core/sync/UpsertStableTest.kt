package tv.own.owntv.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 / S1 — `sortOrder` is compared alongside the content hash instead of being folded into it,
 * so a row the provider only *moved* is still rewritten, while an untouched row is still skipped.
 * Folding it into `computeContentHash()` would have changed every stored hash at once and turned the
 * next resync of a 170k-item catalog into a full rewrite.
 *
 * These exercise [SyncSupport.upsertStable] through a fake [ContentAdapter], so no DAO, Context or
 * android.util.Log is involved.
 */
class UpsertStableTest {

    private data class Row(val remoteId: String, val name: String, val sortOrder: Int, val id: Long = 0, val hash: Int = 0)

    private class Recorder {
        val inserted = ArrayList<Row>()
        val updated = ArrayList<Row>()
    }

    private fun adapterFor(rec: Recorder) = ContentAdapter<Row>(
        remoteIdOf = { it.remoteId },
        // Stands in for computeContentHash(): everything except sortOrder.
        hashOf = { it.name.hashCode() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> row.copy(id = id ?: 0, hash = hash) },
        updateAll = { rec.updated.addAll(it) },
        insertAll = { rec.inserted.addAll(it) },
        remoteIdsForSource = { emptyList() },
        deleteByRemoteIds = { _, _ -> },
        loadHashes = { emptyList() },
    )

    private suspend fun upsert(rows: List<Row>, stored: Map<String, StoredRow>, rec: Recorder): UpsertStats =
        SyncSupport.upsertStable(rows, stored, adapterFor(rec))

    @Test
    fun `row that only moved is updated and counted as moved`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha", sortOrder = 7)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.updated)
        assertEquals(1, stats.moved)
        assertEquals(0, stats.skippedUnchanged)
        assertEquals(0, stats.inserted)
        // Keeps its local row id, so favorites/history/resume stay linked.
        assertEquals(42L, rec.updated.single().id)
        assertEquals(7, rec.updated.single().sortOrder)
    }

    @Test
    fun `row identical in content and position is skipped entirely`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha", sortOrder = 3)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.skippedUnchanged)
        assertEquals(0, stats.updated)
        assertEquals(0, stats.moved)
        assertTrue(rec.updated.isEmpty())
    }

    @Test
    fun `changed content is updated but not counted as moved`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha renamed", sortOrder = 3)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.updated)
        assertEquals(0, stats.moved)
        assertEquals(42L, rec.updated.single().id)
    }

    @Test
    fun `unknown remote id is inserted`() = runBlocking {
        val rec = Recorder()
        val stats = upsert(listOf(Row(remoteId = "new", name = "New", sortOrder = 0)), emptyMap(), rec)

        assertEquals(1, stats.inserted)
        assertEquals(0, stats.updated)
        assertEquals(0L, rec.inserted.single().id)
    }

    @Test
    fun `a provider reorder rewrites only the rows that actually moved`() = runBlocking {
        val rec = Recorder()
        // Positions 0 and 1 swapped; "c" stayed put.
        val rows = listOf(
            Row(remoteId = "a", name = "A", sortOrder = 1),
            Row(remoteId = "b", name = "B", sortOrder = 0),
            Row(remoteId = "c", name = "C", sortOrder = 2),
        )
        val stored = mapOf(
            "a" to StoredRow(1L, "A".hashCode(), 0),
            "b" to StoredRow(2L, "B".hashCode(), 1),
            "c" to StoredRow(3L, "C".hashCode(), 2),
        )

        val stats = upsert(rows, stored, rec)

        assertEquals(2, stats.moved)
        assertEquals(2, stats.updated)
        assertEquals(1, stats.skippedUnchanged)
        assertEquals(setOf(1L, 2L), rec.updated.map { it.id }.toSet())
    }

    // ---- 4.6.1 RC3: a moved-only row writes its position alone ----

    private class MoveRecorder {
        val updated = ArrayList<Row>()
        val inserted = ArrayList<Row>()
        val moves = ArrayList<tv.own.owntv.core.database.dao.SortOrderMove>()
    }

    private fun positionAwareAdapter(rec: MoveRecorder) = ContentAdapter<Row>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.name.hashCode() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> row.copy(id = id ?: 0, hash = hash) },
        updateAll = { rec.updated.addAll(it) },
        insertAll = { rec.inserted.addAll(it) },
        remoteIdsForSource = { emptyList() },
        deleteByRemoteIds = { _, _ -> },
        loadHashes = { emptyList() },
        updateSortOrders = { rec.moves.addAll(it) },
    )

    @Test
    fun `a moved-only row writes its new position alone, keeping its local id`() = runBlocking {
        val rec = MoveRecorder()
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))
        val stats = SyncSupport.upsertStable(listOf(Row("a", "Alpha", 5)), stored, positionAwareAdapter(rec))
        assertEquals(listOf(tv.own.owntv.core.database.dao.SortOrderMove(42L, 5)), rec.moves)
        assertTrue(rec.updated.isEmpty())
        assertEquals(1, stats.updated)
        assertEquals(1, stats.moved)
    }

    @Test
    fun `a provider adding titles at the top shifts every row, and none of them is rewritten in full`() = runBlocking {
        val rec = MoveRecorder()
        val stored = (0 until 1_000).associate { i -> "r$i" to StoredRow(id = 10_000L + i, contentHash = "T$i".hashCode(), sortOrder = i) }
        val incoming = listOf(Row("new1", "New One", 0), Row("new2", "New Two", 1)) +
            (0 until 1_000).map { i -> Row("r$i", "T$i", i + 2) }
        val stats = SyncSupport.upsertStable(incoming, stored, positionAwareAdapter(rec))
        assertEquals(2, rec.inserted.size)
        assertTrue(rec.updated.isEmpty())
        assertEquals(1_000, rec.moves.size)
        assertEquals((0 until 1_000).map { tv.own.owntv.core.database.dao.SortOrderMove(10_000L + it, it + 2) }, rec.moves)
        assertEquals(1_000, stats.moved)
    }

    @Test
    fun `a row whose content changed is still written in full, even if it also moved`() = runBlocking {
        val rec = MoveRecorder()
        val stored = mapOf("a" to StoredRow(id = 7L, contentHash = "Old".hashCode(), sortOrder = 1))
        val stats = SyncSupport.upsertStable(listOf(Row("a", "New title", 9)), stored, positionAwareAdapter(rec))
        assertEquals(listOf(Row("a", "New title", 9, id = 7L, hash = "New title".hashCode())), rec.updated)
        assertTrue(rec.moves.isEmpty())
        assertEquals(0, stats.moved)
    }

    @Test
    fun `untouched rows write nothing at all`() = runBlocking {
        val rec = MoveRecorder()
        val stored = mapOf("a" to StoredRow(id = 7L, contentHash = "Same".hashCode(), sortOrder = 4))
        val stats = SyncSupport.upsertStable(listOf(Row("a", "Same", 4)), stored, positionAwareAdapter(rec))
        assertTrue(rec.updated.isEmpty() && rec.moves.isEmpty() && rec.inserted.isEmpty())
        assertEquals(1, stats.skippedUnchanged)
    }
}
