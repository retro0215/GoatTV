package tv.own.owntv.features.epg

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * One guide row's display data, prepared off the main thread before the row is drawn. A row never
 * queries, filters or allocates while it is composed: it only reads this (while drawing).
 */
@Immutable
internal class GuideRowData(
    val channelId: Long,
    val programmes: List<EpgProgrammeEntity>,
    /** Programmes this channel can replay from the archive (the ↻ badge). */
    val catchupIds: Set<Long>,
    /** The EpgViewModel cache revision this was prepared from; an older one is refreshed in place. */
    val revision: Int,
)

/** A small LRU map with a hard size bound (plain Kotlin, so it runs in unit tests). */
internal class BoundedLru<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) { map[key] = value }
    fun getOrPut(key: K, create: () -> V): V = map[key] ?: create().also { map[key] = it }
    val size: Int get() = map.size
    fun clear() = map.clear()
}

/**
 * Prepared rows for the rows on screen and a few either side, bounded so browsing thousands of
 * channels can't grow it. Reads are snapshot reads: a row reading this while drawing redraws when its
 * data arrives, without recomposing. Written on the main thread only.
 */
internal class GuideRowStore(private val maxRows: Int = 240) {
    private val rows = mutableStateMapOf<Long, GuideRowData>()
    private val recency = ArrayDeque<Long>()

    operator fun get(channelId: Long): GuideRowData? = rows[channelId]

    /** Whether [channelId] has data prepared from [revision] (anything older is redone). */
    fun isCurrent(channelId: Long, revision: Int): Boolean = rows[channelId]?.revision == revision

    fun put(data: GuideRowData) {
        rows[data.channelId] = data
        recency.remove(data.channelId)
        recency.addLast(data.channelId)
        while (recency.size > maxRows) rows.remove(recency.removeFirst())
    }

    val size: Int get() = rows.size
}
