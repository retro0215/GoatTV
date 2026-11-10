package tv.own.owntv.core.catalog.versions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.catalog.ProviderCatalogMetadata
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser

/**
 * The derived-title backfill: rows written before schema v31 get their fields, a parse-rule change
 * re-parses every row once writing only real changes, and a catalogue sync always goes first.
 */
class CatalogTitleBackfillTest {

    /** An in-memory table, paged and rewritten exactly as the DAO does it. */
    private class FakeRows(override val label: String, rows: List<TitleRow>, private val failAfterPages: Int = Int.MAX_VALUE) : TitleRowStore {
        val rows = rows.associateBy { it.id }.toSortedMap()
        val writes = ArrayList<Long>()
        var pages = 0

        override suspend fun page(after: Long, limit: Int, missingOnly: Boolean): List<TitleRow> {
            if (pages++ >= failAfterPages) throw IllegalStateException("synthetic database failure")
            return rows.values.filter { it.id > after && (!missingOnly || it.titleSignature.isEmpty()) }.take(limit)
        }

        override suspend fun writeDerived(id: Long, parsed: ProviderCatalogMetadata) {
            writes += id
            rows[id] = rows.getValue(id).copy(
                canonicalTitle = parsed.canonicalTitle, titleSignature = parsed.titleSignature, parsedYear = parsed.parsedYear,
                providerLanguage = parsed.language, qualityRank = parsed.qualityRank, advertisedCapabilities = parsed.capabilities,
            )
        }
    }

    private class FakeVersion(var stored: Int) : TitleRulesVersionStore {
        override suspend fun read() = stored
        override suspend fun write(version: Int) { stored = version }
    }

    /** A row as the app writes it today: derived fields from the current parser. */
    private fun parsedRow(id: Long, name: String, year: Int? = null): TitleRow {
        val p = ProviderCatalogMetadataParser.parse(name, year)
        return TitleRow(id, name, year, p.canonicalTitle, p.titleSignature, p.parsedYear, p.language, p.qualityRank, p.capabilities)
    }

    /** A row written before schema v31: no derived fields at all. */
    private fun legacyRow(id: Long, name: String) = TitleRow(id, name, null, "", "", null, null, 0, null)

    private fun backfill(stores: List<TitleRowStore>, version: FakeVersion, waits: IntArray = IntArray(1), pageSize: Int = 3, current: Int = 1) =
        CatalogTitleBackfill(stores, version, waitForIdle = { waits[0]++ }, pageSize = pageSize, currentVersion = current)

    @Test
    fun `rows written before v31 are filled, the rest are not touched`() = runBlocking {
        val movies = FakeRows("movies", listOf(parsedRow(1, "Dune (2021) 4K"), legacyRow(2, "Arrival (2016) FHD"), parsedRow(3, "Heat (1995)"), legacyRow(9, "EN| Dune (2021)")))
        val series = FakeRows("series", listOf(legacyRow(5, "The Boys 4K")))
        val report = backfill(listOf(movies, series), FakeVersion(1)).run()

        assertEquals(listOf(2L, 9L), movies.writes)
        assertEquals(listOf(5L), series.writes)
        assertEquals("arrival", movies.rows.getValue(2).titleSignature)
        assertEquals(3, movies.rows.getValue(2).qualityRank)
        assertEquals(2016, movies.rows.getValue(2).parsedYear)
        assertEquals("EN", movies.rows.getValue(9).providerLanguage)
        assertFalse(report.fullPass)
        assertEquals(3, report.written)
    }

    @Test
    fun `an install with no stored version counts as the current rules`() {
        assertEquals(ProviderCatalogMetadataParser.RULES_VERSION, CatalogTitleBackfill.BASELINE_VERSION)
    }

    @Test
    fun `a parse-rule change re-parses every row once and writes only real changes`() = runBlocking {
        val stale = parsedRow(2, "Dune (2021) 4K").copy(qualityRank = 0) // as if an older rule missed "4K"
        val movies = FakeRows("movies", listOf(parsedRow(1, "Heat (1995)"), stale, parsedRow(3, "Arrival (2016)"), legacyRow(4, "Up (2009)")))
        val version = FakeVersion(1)
        val report = backfill(listOf(movies), version, current = 2).run()

        assertTrue(report.fullPass)
        assertEquals(4, report.scanned)
        assertEquals("only the stale and the empty row are rewritten", listOf(2L, 4L), movies.writes)
        assertEquals(4, movies.rows.getValue(2).qualityRank)
        assertEquals(2, version.stored)

        // The next start has nothing left to do.
        movies.writes.clear()
        val again = backfill(listOf(movies), version, current = 2).run()
        assertFalse(again.fullPass)
        assertEquals(emptyList<Long>(), movies.writes)
    }

    @Test
    fun `an interrupted pass keeps the old version, so it runs again`() {
        val movies = FakeRows("movies", (1L..10L).map { legacyRow(it, "Title $it (2001)") }, failAfterPages = 2)
        val version = FakeVersion(1)
        assertThrows(IllegalStateException::class.java) { runBlocking { backfill(listOf(movies), version, current = 2).run() } }
        assertEquals(1, version.stored)
        assertTrue("the pages before the failure were kept", movies.writes.isNotEmpty())
    }

    @Test
    fun `it waits for a catalogue sync before every page`() = runBlocking {
        val movies = FakeRows("movies", (1L..7L).map { legacyRow(it, "Title $it (2001)") })
        val waits = IntArray(1)
        backfill(listOf(movies), FakeVersion(1), waits = waits, pageSize = 3).run()
        assertEquals(7, movies.writes.size)
        assertEquals("pages of 3, 3 and 1 rows", 3, waits[0])
    }

    @Test
    fun `nothing to do costs one empty page per table`() = runBlocking {
        val movies = FakeRows("movies", listOf(parsedRow(1, "Heat (1995)")))
        val series = FakeRows("series", emptyList())
        val waits = IntArray(1)
        val report = backfill(listOf(movies, series), FakeVersion(1), waits = waits).run()
        assertEquals(0, report.written)
        assertEquals(2, waits[0])
    }
}
