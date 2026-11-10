package tv.own.owntv.core.catalog.versions

import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser

/**
 * Synthetic catalogue rows, derived exactly as the app derives them when a row is written: the raw
 * provider name (and provider year, when the panel sends one) through ProviderCatalogMetadataParser.
 */
internal object VersionFixtures {
    fun row(
        id: Long,
        name: String,
        sourceId: Long = 1,
        category: String? = null,
        categoryId: Long? = category?.let { 1000 + it.hashCode().toLong().and(0xffff) },
        providerYear: Int? = null,
        remoteId: String? = "r$id",
    ): VersionCandidateRow {
        val parsed = ProviderCatalogMetadataParser.parse(name, providerYear)
        return VersionCandidateRow(
            id = id,
            sourceId = sourceId,
            categoryId = categoryId,
            categoryName = category,
            categoryRemoteId = category?.let { "cat-${it.lowercase().replace(' ', '-')}" },
            name = name,
            remoteId = remoteId,
            titleSignature = parsed.titleSignature,
            parsedYear = parsed.parsedYear,
            providerLanguage = parsed.language,
            qualityRank = parsed.qualityRank,
            advertisedCapabilities = parsed.capabilities,
        )
    }

    /** The ids [VersionMatcher.versionsOf] groups with [anchor] among [rows]. */
    fun grouped(anchor: VersionCandidateRow, rows: List<VersionCandidateRow>): Set<Long> =
        VersionMatcher.versionsOf(anchor, rows).map { it.id }.toSet()
}
