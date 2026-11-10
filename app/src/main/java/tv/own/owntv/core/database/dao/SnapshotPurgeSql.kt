package tv.own.owntv.core.database.dao

/**
 * The re-sync snapshot orphan purge for content order and custom-category members, as many ids at once.
 *
 * Both tables are indexed by (profileId, mediaType, contextKey[, itemId]) — folder first — so the old
 * one-row delete (`itemId = :itemId`) scanned every row of the profile for each snapshot row: quadratic,
 * and minutes to hours with tens of thousands of members while it held the database writer during a
 * re-sync. The rule is unchanged: a row goes only when its content row no longer exists.
 * Reference: upstream OwnTV_Core 1de78b1e (101 s -> 0.24 s for 62,000 rows, survivors identical).
 *
 * Shared by the DAOs and SnapshotPurgeSqlTest, which runs these exact statements on a real SQLite.
 */
object SnapshotPurgeSql {
    private const val ORPHAN_RULE =
        "(" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")"

    const val CONTENT_ORDER_ORPHANS =
        "DELETE FROM content_order WHERE profileId = :profileId AND mediaType = :type AND itemId IN (:itemIds) AND " +
            ORPHAN_RULE

    const val CUSTOM_CATEGORY_MEMBER_ORPHANS =
        "DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND itemId IN (:itemIds) AND " +
            ORPHAN_RULE

    /** Ids per statement: older SQLite engines allow 999 bound values, and two are taken already. */
    const val CHUNK = 900
}
