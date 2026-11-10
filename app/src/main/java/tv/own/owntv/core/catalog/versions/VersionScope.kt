package tv.own.owntv.core.catalog.versions

import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizeKeys

/**
 * What one profile may see when versions are looked up — the same restrictions its lists apply:
 * only the playlists the profile has (narrowed to the active one when a playlist is selected, with the
 * section switched on), never an item or category the profile hid, and on a Kids profile never an
 * adult category. Versions never reach across into another profile's playlists.
 */
data class VersionScope(
    val sourceIds: List<Long>,
    val hiddenItemKeys: Set<String>,
    val hiddenCategoryKeys: Set<String>,
    val isKids: Boolean,
) {
    fun allows(row: VersionCandidateRow): Boolean {
        if (row.sourceId !in sourceIds) return false
        if (CustomizeKeys.item(row.sourceId, row.remoteId, row.name) in hiddenItemKeys) return false
        if (row.categoryId != null && row.categoryName != null &&
            CustomizeKeys.item(row.sourceId, row.categoryRemoteId, row.categoryName) in hiddenCategoryKeys
        ) return false
        if (isKids && AdultCategoryClassifier.isAdult(row.categoryName)) return false
        return true
    }
}
