package tv.own.owntv.core.catalog.versions

import android.util.Log
import tv.own.owntv.core.catalog.ProviderCatalogMetadata
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser

/** One table's rows as the backfill pages and rewrites them (movies or series). */
interface TitleRowStore {
    val label: String
    suspend fun page(after: Long, limit: Int, missingOnly: Boolean): List<TitleRow>
    suspend fun writeDerived(id: Long, parsed: ProviderCatalogMetadata)
}

/** Which parse rules the stored derived fields were written with. */
interface TitleRulesVersionStore {
    suspend fun read(): Int
    suspend fun write(version: Int)
}

/**
 * Keeps every movie and series row's derived title fields (signature, year, quality, picture, language)
 * computed with the current parse rules — what version discovery groups by.
 *
 * - **Missing:** rows written before schema v31 kept an empty signature until something rewrote them,
 *   and the existing backfill only ran for playlists with Now Trending on. Every run fills them.
 * - **Rules changed:** when [ProviderCatalogMetadataParser.RULES_VERSION] is newer than the stored one,
 *   every row is re-parsed once and only rows whose result differs are written; then the new version is
 *   stored. An interrupted pass leaves the old version stored, so it simply runs again.
 *
 * Pages of [pageSize] rows in id order (a rowid range read, never one whole-catalogue query). Before each
 * page it waits through [waitForIdle], so a catalogue sync always goes first, and only the derived
 * columns are written — the provider's own fields stay as they are.
 */
class CatalogTitleBackfill(
    private val stores: List<TitleRowStore>,
    private val versions: TitleRulesVersionStore,
    private val waitForIdle: suspend () -> Unit,
    private val pageSize: Int = PAGE_SIZE,
    private val parse: (String, Int?) -> ProviderCatalogMetadata = ProviderCatalogMetadataParser::parse,
    private val currentVersion: Int = ProviderCatalogMetadataParser.RULES_VERSION,
) {
    data class Report(val scanned: Int, val written: Int, val fullPass: Boolean)

    suspend fun run(): Report {
        val fullPass = versions.read() < currentVersion
        var scanned = 0
        var written = 0
        for (store in stores) {
            var after = 0L
            while (true) {
                waitForIdle()
                val page = store.page(after, pageSize, missingOnly = !fullPass)
                if (page.isEmpty()) break
                for (row in page) {
                    val parsed = parse(row.name, row.year)
                    if (row.differsFrom(parsed)) {
                        store.writeDerived(row.id, parsed)
                        written++
                    }
                }
                scanned += page.size
                after = page.last().id
                if (page.size < pageSize) break
            }
            Log.i(TAG, "${store.label}: scanned=$scanned written=$written fullPass=$fullPass")
        }
        if (fullPass) versions.write(currentVersion)
        return Report(scanned, written, fullPass)
    }

    companion object {
        private const val TAG = "CatalogTitleBackfill"
        const val PAGE_SIZE = 500

        /**
         * The rules version an install with no stored version is on. The parser has not changed since
         * schema v31 introduced these fields, so every row written since then already matches version 1
         * and only rows with an empty signature need work — no full pass on the first run.
         */
        const val BASELINE_VERSION = 1

        internal fun TitleRow.differsFrom(p: ProviderCatalogMetadata): Boolean =
            canonicalTitle != p.canonicalTitle ||
                titleSignature != p.titleSignature ||
                parsedYear != p.parsedYear ||
                providerLanguage != p.language ||
                qualityRank != p.qualityRank ||
                advertisedCapabilities != p.capabilities
    }
}
