package tv.own.owntv.features.home

import tv.own.owntv.core.catalog.versions.VersionMatcher

/**
 * One card per film in every Home row built from the continuation list (Keep Watching and Continue
 * Watching Movies): when two versions of a title (1080p and 4K, say) are both part-way, only the one
 * watched last stays, in its place. Folded only when certainly the same title — the same groupable
 * signature and the same explicit year ([VersionMatcher.certainGroupKey]); remakes, generic titles and
 * yearless rows are never merged. Episodes and channels pass through untouched, and nothing is added,
 * so the profile's playlists, hidden items and Kids rules already applied upstream still hold.
 */
object ContinueWatchingFold {

    fun <T> fold(
        items: List<T>,
        isMovie: (T) -> Boolean,
        lastUsedOf: (T) -> Long,
        groupKeyOf: (T) -> String?,
    ): List<T> {
        val movies = items.filter(isMovie)
        if (movies.size < 2) return items
        val kept = VersionMatcher.withoutRepeatedVersions(movies, lastUsedOf, groupKeyOf)
            .mapTo(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())) { it }
        return items.filter { !isMovie(it) || it in kept }
    }
}
