package tv.own.owntv.features.epg

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * One guide row's display data, prepared off the main thread before the row is drawn. A row never
 * queries, filters or allocates while it is composed: it only reads this (while drawing).
 *
 * [programmes] is the row's normalized list (see [GuideNav.normalizeProgrammes]): duplicates from a
 * second feed and programmes hidden under another are left out of the DRAWING only — nothing in the
 * database changes. [drawStarts] holds, per programme, where its cell begins on the timeline: its own
 * start, or the end of the programme before it when the two overlap.
 */
@Immutable
internal class GuideRowData(
    val channelId: Long,
    val programmes: List<EpgProgrammeEntity>,
    val drawStarts: LongArray,
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
 * Per-key observable values with a bounded number of loaded values. Each key owns its OWN state
 * object, so a row that reads key A while drawing is invalidated only by writes to A. (Phase 3D kept
 * every row in one snapshot map; Compose tracks a map read as a read of the whole map, so preparing one
 * row or decoding one logo re-recorded every visible row — 14-24 ms each on the onn.)
 *
 * Holders are tiny and stay for the screen's lifetime, so a row keeps a stable holder; only the values
 * are bounded: past [maxLoaded], the least recently written values are cleared (that row re-prepares
 * when it scrolls back). Written on the main thread only.
 */
internal class KeyedStates<K, V : Any>(private val maxLoaded: Int) {
    private val holders = HashMap<K, MutableState<V?>>()
    private val loaded = ArrayDeque<K>()

    /** The state for [key] — read its value while drawing. Not itself observable (a plain map). */
    fun state(key: K): State<V?> = holders.getOrPut(key) { mutableStateOf(null) }

    operator fun get(key: K): V? = holders[key]?.value

    fun put(key: K, value: V) {
        (holders.getOrPut(key) { mutableStateOf(null) }).value = value
        loaded.remove(key)
        loaded.addLast(key)
        while (loaded.size > maxLoaded) holders[loaded.removeFirst()]?.value = null
    }

    /** Values currently loaded (bounded by maxLoaded). */
    val loadedCount: Int get() = loaded.size
}

/** Prepared rows by channel id; see [KeyedStates]. */
internal class GuideRowStore(maxRows: Int = 240) {
    private val states = KeyedStates<Long, GuideRowData>(maxRows)

    fun state(channelId: Long): State<GuideRowData?> = states.state(channelId)
    operator fun get(channelId: Long): GuideRowData? = states[channelId]

    /** Whether [channelId] has data prepared from [revision] (anything older is redone). */
    fun isCurrent(channelId: Long, revision: Int): Boolean = states[channelId]?.revision == revision

    fun put(data: GuideRowData) = states.put(data.channelId, data)

    val size: Int get() = states.loadedCount
}
