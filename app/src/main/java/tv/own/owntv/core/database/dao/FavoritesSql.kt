package tv.own.owntv.core.database.dao

/**
 * The Favorites screen's queries (navigation Phase B), as constants so the DAOs' `@Query` and the SQL test
 * run the very same text. Each is the existing favorites join, scoped to one profile AND the active
 * playlist's sources, newest first. An item that no longer exists in the catalog drops out by the join.
 */
object FavoritesSql {
    const val LIVE = "SELECT c.* FROM channels c INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
        "WHERE f.profileId = :profileId AND c.sourceId IN (:sourceIds) ORDER BY f.addedAt DESC"
    const val MOVIE = "SELECT m.* FROM movies m INNER JOIN favorites f ON f.itemId = m.id AND f.mediaType = 'MOVIE' " +
        "WHERE f.profileId = :profileId AND m.sourceId IN (:sourceIds) ORDER BY f.addedAt DESC"
    const val SERIES = "SELECT s.* FROM series s INNER JOIN favorites f ON f.itemId = s.id AND f.mediaType = 'SERIES' " +
        "WHERE f.profileId = :profileId AND s.sourceId IN (:sourceIds) ORDER BY f.addedAt DESC"
}
