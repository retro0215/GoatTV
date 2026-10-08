package tv.own.owntv.core.content

import tv.own.owntv.core.database.entity.CategoryEntity

/**
 * Whether a Movie or Series is adult (X-rated) content, for customer-facing discovery surfaces such as
 * the Home rails. Shared by every brand.
 *
 * Evidence, strongest first:
 *  1. an explicit provider adult flag that is set (`true`);
 *  2. membership of a provider category classified as adult ([AdultCategoryClassifier]), resolved once
 *     per load into category ids so each item is a set lookup.
 *
 * Titles are never used: real catalogues carry many mainstream titles containing "porn", "xxx" or
 * "sex" (documentaries, comedies, the xXx films). An item with no category is not known to be adult.
 *
 * A provider flag of `false` is not proof of "not adult": on a real Xtream provider (2026-10) the adult
 * VOD category "18+ | …" held 692 X-rated films, every one sent with `is_adult = "0"`. The importer does
 * not store the flag today; [isAdultContent] accepts it for sources that do set it.
 *
 * Read-only: nothing here deletes or hides provider content, favorites or history.
 */
object AdultContentPolicy {

    fun adultCategoryIds(categories: Iterable<CategoryEntity>): Set<Long> =
        categories.filter { AdultCategoryClassifier.isAdult(it.name) }.mapTo(hashSetOf()) { it.id }

    fun isAdultContent(categoryId: Long?, adultCategoryIds: Set<Long>, providerAdultFlag: Boolean? = null): Boolean =
        providerAdultFlag == true || (categoryId != null && categoryId in adultCategoryIds)

    /**
     * Fills a ranked rail with up to [limit] items that are not [excluded], in the source's order. Pages
     * past excluded items so the rail stays full ("20 newest" means 20 newest eligible, not 20 minus the
     * adult ones). The first page is exactly [limit], so a source with nothing to exclude costs one query,
     * as before. Scanning stops after [maxScan] candidates to bound the cost on a pathological source.
     */
    suspend fun <T> fillRail(
        limit: Int,
        excluded: (T) -> Boolean,
        pageSize: Int = PAGE_SIZE,
        maxScan: Int = MAX_SCAN,
        fetchPage: suspend (offset: Int, count: Int) -> List<T>,
    ): List<T> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<T>(limit)
        var offset = 0
        var count = limit
        while (out.size < limit && offset < maxScan) {
            val page = fetchPage(offset, count)
            for (item in page) {
                if (!excluded(item)) {
                    out += item
                    if (out.size == limit) break
                }
            }
            if (page.size < count) break
            offset += page.size
            count = minOf(pageSize, maxScan - offset)
        }
        return out
    }

    const val PAGE_SIZE = 200
    const val MAX_SCAN = 5_000
}
