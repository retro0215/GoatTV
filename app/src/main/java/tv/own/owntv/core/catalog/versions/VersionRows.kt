package tv.own.owntv.core.catalog.versions

/**
 * One movie or series row as alternate-version discovery reads it ([VersionSql.MOVIE_CANDIDATES]):
 * the row's own identity (playlist, provider id, name) plus the derived title fields stored at write
 * time and its category's name and provider id.
 */
data class VersionCandidateRow(
    val id: Long,
    val sourceId: Long,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryRemoteId: String?,
    val name: String,
    val remoteId: String?,
    val titleSignature: String,
    val parsedYear: Int?,
    val providerLanguage: String?,
    val qualityRank: Int,
    val advertisedCapabilities: String?,
)

/** A row's raw title and its stored derived title fields — what the backfill compares and rewrites. */
data class TitleRow(
    val id: Long,
    val name: String,
    val year: Int?,
    val canonicalTitle: String,
    val titleSignature: String,
    val parsedYear: Int?,
    val providerLanguage: String?,
    val qualityRank: Int,
    val advertisedCapabilities: String?,
)
