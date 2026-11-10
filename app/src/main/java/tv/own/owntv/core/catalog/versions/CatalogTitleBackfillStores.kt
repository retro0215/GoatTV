package tv.own.owntv.core.catalog.versions

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.catalog.ProviderCatalogMetadata
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.sync.SyncActivityTracker
import tv.own.owntv.features.settings.data.SettingsRepository

/** [CatalogTitleBackfill] over the real tables, the rules version in settings, and the sync tracker. */
fun catalogTitleBackfill(
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    settings: SettingsRepository,
    syncs: SyncActivityTracker,
): CatalogTitleBackfill = CatalogTitleBackfill(
    stores = listOf(MovieTitleRows(movieDao), SeriesTitleRows(seriesDao)),
    versions = object : TitleRulesVersionStore {
        override suspend fun read(): Int = settings.catalogTitleRulesVersion(CatalogTitleBackfill.BASELINE_VERSION)
        override suspend fun write(version: Int) = settings.setCatalogTitleRulesVersion(version)
    },
    waitForIdle = {
        // A catalogue sync always goes first; between pages, leave the writer to the app.
        syncs.active.first { it.isEmpty() }
        delay(PAGE_PAUSE_MS)
    },
)

/** Pause between two backfill pages, so a long pass never holds the database writer for long. */
private const val PAGE_PAUSE_MS = 25L

private class MovieTitleRows(private val dao: MovieDao) : TitleRowStore {
    override val label = "movies"
    override suspend fun page(after: Long, limit: Int, missingOnly: Boolean) =
        if (missingOnly) dao.titleMissingPage(after, limit) else dao.titlePage(after, limit)
    override suspend fun writeDerived(id: Long, parsed: ProviderCatalogMetadata) = dao.updateDerivedTitle(
        id, parsed.canonicalTitle, parsed.titleSignature, parsed.parsedYear, parsed.language, parsed.qualityRank, parsed.capabilities,
    )
}

private class SeriesTitleRows(private val dao: SeriesDao) : TitleRowStore {
    override val label = "series"
    override suspend fun page(after: Long, limit: Int, missingOnly: Boolean) =
        if (missingOnly) dao.titleMissingPage(after, limit) else dao.titlePage(after, limit)
    override suspend fun writeDerived(id: Long, parsed: ProviderCatalogMetadata) = dao.updateDerivedTitle(
        id, parsed.canonicalTitle, parsed.titleSignature, parsed.parsedYear, parsed.language, parsed.qualityRank, parsed.capabilities,
    )
}
