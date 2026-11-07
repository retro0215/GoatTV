package tv.own.owntv.features.multiscreen

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.live.LiveKey

/**
 * The paged channel list behind the Multiscreen channel picker.
 *
 * Room invalidates a [PagingSource] whenever a table it reads changes — History after every live play,
 * Favorites on every toggle, the channel table on every playlist re-sync — and the [Pager] then asks its
 * factory for a fresh one. The picker used to hand back the one source it built up front, so the first
 * invalidation (e.g. picker on History, closed, then any live channel played) threw "An instance of
 * PagingSource was re-used" on the main thread and crashed the app. [sourceFactory] opens a new query on
 * every call.
 */
internal object MultiscreenPickerPaging {

    const val PAGE_SIZE = 50

    /** A factory that returns a NEW [PagingSource] for this category/search on every call. A search wins over the category. */
    fun sourceFactory(
        dao: ChannelDao,
        profileId: Long,
        sourceIds: List<Long>,
        key: LiveKey,
        query: String,
    ): () -> PagingSource<Int, ChannelEntity> = {
        when {
            query.isNotBlank() -> dao.searchAll(query, sourceIds)
            key is LiveKey.Favorites -> dao.pagingFavorites(profileId)
            key is LiveKey.History -> dao.pagingHistory(profileId, sourceIds)
            key is LiveKey.Folder -> dao.pagingByCategory(key.id)
            else -> dao.pagingAllOriginal(sourceIds)
        }
    }

    fun pager(dao: ChannelDao, profileId: Long, sourceIds: List<Long>, key: LiveKey, query: String): Pager<Int, ChannelEntity> =
        Pager(PagingConfig(pageSize = PAGE_SIZE), pagingSourceFactory = sourceFactory(dao, profileId, sourceIds, key, query))
}
