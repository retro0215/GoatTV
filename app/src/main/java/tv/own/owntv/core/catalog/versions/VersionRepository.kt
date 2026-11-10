package tv.own.owntv.core.catalog.versions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.features.settings.data.SettingsRepository

/** One version of a title as discovery found it: the row itself, untouched, and how it is labelled. */
data class ContentVersion(val row: VersionCandidateRow, val label: VersionLabel, val isAnchor: Boolean)

/**
 * Alternate versions of a movie or series (the same title as 1080p, 4K, 4K Dolby Vision, another
 * language), across the active profile's playlists.
 *
 * Cost per lookup: nothing for a title that can never group, otherwise one indexed query (one seek per
 * playlist on (sourceId, titleSignature, parsedYear), at most [VersionSql.CANDIDATE_LIMIT] rows) — no
 * catalogue scan when a details page opens. The profile's restrictions ([VersionScope]) apply before the
 * matcher ([VersionMatcher]) decides which candidates are really the same title.
 *
 * Used by the Movies and Series version selectors (4.6.1 stages 2 and 3).
 */
class VersionRepository(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val profileDao: ProfileDao,
) {
    /** Versions of [movie] the active profile can see, best first; just [movie] when it has none. */
    suspend fun movieVersions(movie: MovieEntity): List<ContentVersion> = withContext(Dispatchers.IO) {
        val anchor = movie.asCandidate()
        if (!VersionMatcher.isGroupable(anchor.titleSignature)) return@withContext listOf(anchorOnly(anchor))
        val scope = scopeFor(MediaType.MOVIE) ?: return@withContext listOf(anchorOnly(anchor))
        val rows = movieDao.versionCandidates(scope.sourceIds, anchor.titleSignature, VersionSql.CANDIDATE_LIMIT)
        resolve(anchor, rows, scope)
    }

    /** Versions of [series] the active profile can see, best first; just [series] when it has none. */
    suspend fun seriesVersions(series: SeriesEntity): List<ContentVersion> = withContext(Dispatchers.IO) {
        val anchor = series.asCandidate()
        if (!VersionMatcher.isGroupable(anchor.titleSignature)) return@withContext listOf(anchorOnly(anchor))
        val scope = scopeFor(MediaType.SERIES) ?: return@withContext listOf(anchorOnly(anchor))
        val rows = seriesDao.versionCandidates(scope.sourceIds, anchor.titleSignature, VersionSql.CANDIDATE_LIMIT)
        resolve(anchor, rows, scope)
    }

    private suspend fun scopeFor(type: MediaType): VersionScope? {
        val active = activeProfileSources(settings, sourceDao).first()
        if (active.profileId < 0) return null
        val sourceIds = active.sourceIdsFor(type)
        if (sourceIds.isEmpty()) return null
        val custom = customize.observe(active.profileId, type).first()
        val isKids = profileDao.getById(active.profileId)?.isKids == true
        return VersionScope(sourceIds, custom.hiddenItems.keys, custom.hiddenCategories, isKids)
    }

    private fun anchorOnly(anchor: VersionCandidateRow) = ContentVersion(anchor, VersionLabels.of(anchor), isAnchor = true)

    companion object {
        /**
         * [rows] narrowed to what [scope] allows, grouped with [anchor] by [VersionMatcher] and labelled,
         * the anchor marked. The anchor's own row from the query (it carries its category's name) is
         * preferred over the bare one built from the entity. Pure, so it is unit-tested end to end.
         */
        fun resolve(anchor: VersionCandidateRow, rows: List<VersionCandidateRow>, scope: VersionScope): List<ContentVersion> {
            val anchorRow = rows.firstOrNull { it.id == anchor.id } ?: anchor
            val visible = rows.filter { it.id == anchorRow.id || scope.allows(it) }
            val members = VersionMatcher.versionsOf(anchorRow, visible, limitReached = rows.size >= VersionSql.CANDIDATE_LIMIT)
            return VersionMatcher.ordered(members.map { it to VersionLabels.of(it) })
                .map { (row, label) -> ContentVersion(row, label, isAnchor = row.id == anchorRow.id) }
        }

        fun MovieEntity.asCandidate() = VersionCandidateRow(
            id = id, sourceId = sourceId, categoryId = categoryId, categoryName = null, categoryRemoteId = null,
            name = name, remoteId = remoteId, titleSignature = titleSignature, parsedYear = parsedYear,
            providerLanguage = providerLanguage, qualityRank = qualityRank, advertisedCapabilities = advertisedCapabilities,
        )

        fun SeriesEntity.asCandidate() = VersionCandidateRow(
            id = id, sourceId = sourceId, categoryId = categoryId, categoryName = null, categoryRemoteId = null,
            name = name, remoteId = remoteId, titleSignature = titleSignature, parsedYear = parsedYear,
            providerLanguage = providerLanguage, qualityRank = qualityRank, advertisedCapabilities = advertisedCapabilities,
        )
    }
}
