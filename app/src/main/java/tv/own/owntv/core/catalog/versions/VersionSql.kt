package tv.own.owntv.core.catalog.versions

/**
 * SQL behind alternate-version discovery and the derived-title backfill, shared by the DAOs and the
 * SQLite tests (VersionCandidateSqlTest), which run these exact statements and check their query plans.
 */
object VersionSql {
    private const val CANDIDATE_COLUMNS =
        "x.id AS id, x.sourceId AS sourceId, x.categoryId AS categoryId, c.name AS categoryName, " +
            "c.remoteId AS categoryRemoteId, x.name AS name, x.remoteId AS remoteId, " +
            "x.titleSignature AS titleSignature, x.parsedYear AS parsedYear, " +
            "x.providerLanguage AS providerLanguage, x.qualityRank AS qualityRank, " +
            "x.advertisedCapabilities AS advertisedCapabilities"

    /**
     * Every movie with this title signature in the given playlists, with its category's name (a quality or
     * language label such as "4K MOVIES"). Served by the (sourceId, titleSignature, parsedYear) index: one
     * index seek per playlist, never a catalogue scan. [VersionMatcher] decides which rows really are the
     * same title.
     */
    const val MOVIE_CANDIDATES =
        "SELECT " + CANDIDATE_COLUMNS + " FROM movies x LEFT JOIN categories c ON c.id = x.categoryId " +
            "WHERE x.sourceId IN (:sourceIds) AND x.titleSignature = :signature LIMIT :limit"

    /** [MOVIE_CANDIDATES] for series, served by the same index on the series table. */
    const val SERIES_CANDIDATES =
        "SELECT " + CANDIDATE_COLUMNS + " FROM series x LEFT JOIN categories c ON c.id = x.categoryId " +
            "WHERE x.sourceId IN (:sourceIds) AND x.titleSignature = :signature LIMIT :limit"

    private const val TITLE_COLUMNS =
        "id, name, year, canonicalTitle, titleSignature, parsedYear, providerLanguage, qualityRank, advertisedCapabilities"

    /** One page of movie rows after [after], in id order (a rowid range read, resumable). */
    const val MOVIE_TITLE_PAGE = "SELECT " + TITLE_COLUMNS + " FROM movies WHERE id > :after ORDER BY id LIMIT :limit"

    /** One page of movie rows whose derived title was never computed (written before schema v31). */
    const val MOVIE_TITLE_MISSING_PAGE =
        "SELECT " + TITLE_COLUMNS + " FROM movies WHERE id > :after AND titleSignature = '' ORDER BY id LIMIT :limit"

    const val SERIES_TITLE_PAGE = "SELECT " + TITLE_COLUMNS + " FROM series WHERE id > :after ORDER BY id LIMIT :limit"

    const val SERIES_TITLE_MISSING_PAGE =
        "SELECT " + TITLE_COLUMNS + " FROM series WHERE id > :after AND titleSignature = '' ORDER BY id LIMIT :limit"

    private const val SET_DERIVED =
        " SET canonicalTitle = :canonicalTitle, titleSignature = :titleSignature, parsedYear = :parsedYear, " +
            "providerLanguage = :providerLanguage, qualityRank = :qualityRank, " +
            "advertisedCapabilities = :advertisedCapabilities WHERE id = :id"

    /** Rewrites only the derived title columns of one row; the provider's own fields are untouched. */
    const val MOVIE_UPDATE_DERIVED = "UPDATE movies" + SET_DERIVED
    const val SERIES_UPDATE_DERIVED = "UPDATE series" + SET_DERIVED

    /** Rows a version lookup reads at most. More than this many titles sharing one signature means a
     *  generic title, which the matcher then refuses to group. */
    const val CANDIDATE_LIMIT = 200
}
