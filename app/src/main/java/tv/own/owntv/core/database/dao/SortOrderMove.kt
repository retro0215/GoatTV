package tv.own.owntv.core.database.dao

/**
 * A catalog row whose content is unchanged but whose provider position moved: only its `sortOrder`
 * is written (see SyncSupport.upsertStable). A full-row update would also rewrite every other index
 * entry of the row and re-check its foreign keys, for no change.
 */
data class SortOrderMove(val id: Long, val sortOrder: Int)
