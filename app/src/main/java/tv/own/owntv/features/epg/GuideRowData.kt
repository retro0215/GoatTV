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

/** A cell's title and time label, measured off the UI thread at its fully visible width. */
internal class GuideCellText(
    val width: Int,
    /** Whether the label was built with the on-air ("NOW") prefix; a cell that has since changed is re-measured. */
    val labelNow: Boolean,
    val title: androidx.compose.ui.text.TextLayoutResult,
    val time: androidx.compose.ui.text.TextLayoutResult,
)

/**
 * A row's prepared cell texts for the part of the timeline in view. [key] identifies what they were
 * prepared for (data revision and timeline bucket) — see [GuideNav.textKey].
 */
internal class GuideRowTexts(val key: Long, val cells: Map<Long, GuideCellText>)

/** A value made for [key], kept while the key is unchanged and replaced when it changes. */
internal class KeptFor<K, V : Any> {
    private var key: Any? = NO_KEY
    private var value: V? = null

    fun get(key: K, create: () -> V): V {
        val kept = value
        if (kept != null && this.key == key) return kept
        return create().also { value = it; this.key = key }
    }

    private companion object { val NO_KEY = Any() }
}

/** Where the guide was scrolled when it was left, reused only for the same list of rows. */
internal class GuideScrollMemory {
    class Position(val firstRow: Int, val firstRowOffset: Int, val timelinePx: Int)

    private var list: Any? = null
    private var revision = -1
    private var position: Position? = null

    fun save(list: Any, revision: Int, position: Position) {
        this.list = list; this.revision = revision; this.position = position
    }

    /** The saved position, if the guide shows the same rows ([list] is the same object, same [revision]). */
    fun restore(list: Any, revision: Int): Position? = position?.takeIf { this.list === list && this.revision == revision }
}

/**
 * 4.6.0: what the Guide screen prepared — row data, cell texts, logos and its scroll position — kept by the
 * activity-scoped EpgViewModel between visits. Each Guide open built them again from nothing: back from
 * full screen the rows on screen stayed empty for 1–2 s on the onn while they were re-prepared and their
 * texts re-measured. Row data and texts carry the cache revision they were made from, so a changed guide
 * still refreshes them; texts and logos are kept only for the same text style and logo size.
 */
internal class GuideScreenCache {
    val rows = GuideRowStore()
    private val texts = KeptFor<Any, KeyedStates<Long, GuideRowTexts>>()
    private val logos = KeptFor<Int, GuideLogoCache>()
    val scroll = GuideScrollMemory()

    /** Cell texts measured with [style] (density, font scale, typography, colours, locale, time format). */
    fun texts(style: Any, maxRows: Int): KeyedStates<Long, GuideRowTexts> = texts.get(style) { KeyedStates(maxRows) }

    fun logos(sizePx: Int, create: () -> GuideLogoCache): GuideLogoCache = logos.get(sizePx, create)

    private var rowsFrom: List<*>? = null
    private var rowsShowAll = false
    private var rowsRevision = -1
    private var rowsKept: List<tv.own.owntv.core.database.entity.ChannelEntity>? = null

    /**
     * 4.6.0: the rows on screen for [channels] (the list itself, not an equal copy), [showAll] and the cache
     * [revision], worked out by [compute] only when one of them changed. Each Guide open filtered all ~19k
     * channels again in its first composition (~7 % of the open's main-thread time on the onn).
     */
    fun visibleRows(
        channels: List<tv.own.owntv.core.database.entity.ChannelEntity>, showAll: Boolean, revision: Int,
        compute: () -> List<tv.own.owntv.core.database.entity.ChannelEntity>,
    ): List<tv.own.owntv.core.database.entity.ChannelEntity> {
        val kept = rowsKept
        if (kept != null && rowsFrom === channels && rowsShowAll == showAll && rowsRevision == revision) return kept
        return compute().also { rowsKept = it; rowsFrom = channels; rowsShowAll = showAll; rowsRevision = revision }
    }
}
