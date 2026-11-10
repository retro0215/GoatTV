package tv.own.owntv.features.versions

import org.json.JSONArray
import tv.own.owntv.core.catalog.versions.AdvertisedPicture
import tv.own.owntv.core.player.DeviceVideoCaps

/**
 * One version of a film, a series or an episode as the picker shows it. [key] is the version's stable
 * identity (`<playlist>:<provider id or name>`), the same form the rest of the app keys items by, so it
 * survives re-syncs that renumber rows. [itemId] is the row to play or open (for an episode, that
 * version's own matching episode, or the series row when [unavailable] is set).
 */
data class VersionOption(
    val itemId: Long,
    val key: String,
    val qualityRank: Int,
    val picture: AdvertisedPicture?,
    val language: String?,
    val playlistName: String?,
    val categoryName: String?,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val progressUpdatedAt: Long,
    val isFavorite: Boolean,
    val isAnchor: Boolean,
    /** This TV is known to show the version as labelled (see [VersionChoice.confirmedPlayable]). */
    val confirmed: Boolean,
    /** The label promises a picture format (Dolby Vision, HDR) this TV is not known to show. */
    val pictureMayFallBack: Boolean,
    /** Why this version cannot be chosen here (listed, never hidden); null when it can. */
    val unavailable: Unavailable? = null,
    /** The version already open on screen. */
    val isCurrent: Boolean = false,
) {
    enum class Unavailable {
        /** The episode is not in this version (its numbering is trustworthy and has no such episode). */
        MISSING,
        /** The numbering on one side cannot be trusted (app-made, duplicated), so nothing is matched. */
        UNMATCHED,
        /** This version's episodes could not be loaded (provider unreachable, request cut off). */
        NOT_LOADED,
    }
}

/**
 * The versions of the item whose details are open. [ready] false: the lookup is still running, and
 * Watch must wait for it ([watchAction]) — a press during the lookup must not slip past the picker.
 * Ready with fewer than two [options]: one version, Watch plays exactly as it always has.
 */
data class VersionsUi(
    val itemId: Long,
    val ready: Boolean,
    val titleKey: String = "",
    val options: List<VersionOption> = emptyList(),
    val initialIndex: Int = 0,
) {
    val hasChoice: Boolean get() = ready && options.size > 1

    companion object {
        fun loading(itemId: Long) = VersionsUi(itemId, ready = false)
        fun single(itemId: Long) = VersionsUi(itemId, ready = true)
    }
}

/** What a Watch press does for [itemId] given the version lookup's state. */
enum class WatchAction { WAIT, PLAY_DIRECT, CHOOSE }

fun watchAction(ui: VersionsUi?, itemId: Long): WatchAction = when {
    ui == null || ui.itemId != itemId || !ui.ready -> WatchAction.WAIT
    ui.options.size > 1 -> WatchAction.CHOOSE
    else -> WatchAction.PLAY_DIRECT
}

/**
 * Watch on a details page whose version lookup may still be running. A press during the lookup is
 * held, not dropped and not played: when the lookup is ready, [onVersions] hands out the action once
 * (the picker for several versions, direct playback for one). More presses while held add nothing, so
 * waiting never turns into two playbacks.
 */
class WatchGate(private val itemId: Long) {
    var pending: Boolean = false
        private set

    /** Watch pressed: what to do now, or null when it is held until the lookup is ready. */
    fun press(ui: VersionsUi?): WatchAction? {
        val action = watchAction(ui, itemId)
        if (action == WatchAction.WAIT) {
            pending = true
            return null
        }
        pending = false
        return action
    }

    /** The lookup state changed: the held press's action, once, when it becomes ready. */
    fun onVersions(ui: VersionsUi?): WatchAction? {
        if (!pending) return null
        val action = watchAction(ui, itemId)
        if (action == WatchAction.WAIT) return null
        pending = false
        return action
    }

    /** The page went away or Back was pressed: forget a held press. */
    fun cancel() { pending = false }
}

object VersionChoice {

    /**
     * Whether this TV is known to show a version as advertised. Unknown answers never count as yes.
     * - 4K and above: a 4K output and a hardware HEVC decoder for 2160p.
     * - Dolby Vision: a Dolby Vision display and decoder, and films starting on ExoPlayer — the current mpv
     *   engine shows Dolby Vision profile 5 with wrong colours, so a Dolby Vision version is never the
     *   default while films start on mpv.
     * - HDR10 / HDR10+ / HDR: an HDR display.
     * - Anything labelled 1080p or below (or not at all) is confirmed.
     */
    fun confirmedPlayable(rank: Int, picture: AdvertisedPicture?, caps: DeviceVideoCaps, filmsStartOnExo: Boolean): Boolean {
        if (rank >= UHD_RANK && !(caps.uhdOutput == true && caps.hevcUhdHardware == true)) return false
        return when (picture) {
            null -> true
            AdvertisedPicture.DOLBY_VISION -> caps.dolbyVisionDisplay == true && caps.dolbyVisionDecoder == true && filmsStartOnExo
            AdvertisedPicture.HDR10_PLUS, AdvertisedPicture.HDR10, AdvertisedPicture.HDR -> caps.hdrDisplay == true
        }
    }

    /** Whether the picture format promised by the label is not established on this TV. */
    fun pictureMayFallBack(picture: AdvertisedPicture?, caps: DeviceVideoCaps): Boolean = when (picture) {
        null -> false
        AdvertisedPicture.DOLBY_VISION -> !(caps.dolbyVisionDisplay == true && caps.dolbyVisionDecoder == true)
        else -> caps.hdrDisplay != true
    }

    /**
     * Which option the picker focuses first: the version this profile chose last time for this title,
     * else the best version (the list is best first) this TV is confirmed to show, else the one the
     * customer opened. Nothing is ever hidden; this only decides where OK lands without moving.
     */
    fun initialIndex(options: List<VersionOption>, rememberedKey: String?): Int {
        if (options.isEmpty()) return 0
        val choosable = options.withIndex().filter { it.value.unavailable == null }
        rememberedKey?.let { key -> choosable.firstOrNull { it.value.key == key }?.let { return it.index } }
        choosable.firstOrNull { it.value.confirmed }?.let { return it.index }
        options.indexOfFirst { it.isAnchor }.takeIf { it >= 0 && options[it].unavailable == null }?.let { return it }
        return choosable.firstOrNull()?.index ?: 0
    }

    /** How a chosen version starts. [offerResumeFromMs] non-null: ask, resuming at that point in this version. */
    data class StartPlan(val startPositionMs: Long, val offerResumeFromMs: Long?)

    /**
     * Where [chosen] starts, given the progress on every version of the title.
     *
     * - Its own unfinished progress: resume there, exactly as Watch always has.
     * - None, but another version was left part-way: offer to resume at the same point only when both
     *   durations are known and within [SAME_LENGTH_TOLERANCE] of each other (different cuts or a
     *   different intro would put the same timestamp in a different scene). Otherwise start at 0.
     * - Progress is only ever read here; playing [chosen] writes its own row, never another version's.
     */
    fun startPlan(chosen: VersionOption, chosenDurationMs: Long?, all: List<VersionOption>): StartPlan {
        if (chosen.positionMs > 0 && !chosen.completed) return StartPlan(chosen.positionMs, null)
        val other = all
            .filter { it.itemId != chosen.itemId && it.unavailable == null && it.positionMs > 0 && !it.completed && it.durationMs > 0 }
            .maxByOrNull { it.progressUpdatedAt }
            ?: return StartPlan(0, null)
        val target = chosenDurationMs?.takeIf { it > 0 } ?: return StartPlan(0, null)
        val sameLength = kotlin.math.abs(target - other.durationMs) <= other.durationMs * SAME_LENGTH_TOLERANCE
        if (!sameLength || other.positionMs >= target) return StartPlan(0, null)
        return StartPlan(0, other.positionMs)
    }

    /** Two durations within 2 % are the same cut (a re-encode, another container). */
    const val SAME_LENGTH_TOLERANCE = 0.02

    const val UHD_RANK = 4
}

/**
 * The version each profile chose last per title, as one small JSON value in settings (no database
 * table): an array of `["<profileId>|<titleKey>", "<versionKey>"]` pairs, oldest first. Bounded to
 * [MAX_ENTRIES], the oldest choice dropped first, so it never grows without limit. Device-only — not in
 * any backup list.
 */
object VersionChoiceStore {
    const val MAX_ENTRIES = 400

    fun entryKey(profileId: Long, titleKey: String) = "$profileId|$titleKey"

    private fun entries(json: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val array = runCatching { JSONArray(json ?: return out) }.getOrNull() ?: return out
        for (i in 0 until array.length()) {
            val pair = array.optJSONArray(i) ?: continue
            val k = pair.optString(0)
            val v = pair.optString(1)
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    fun read(json: String?, profileId: Long, titleKey: String): String? = entries(json)[entryKey(profileId, titleKey)]

    /** [json] with this choice recorded as the newest entry. */
    fun write(json: String?, profileId: Long, titleKey: String, versionKey: String): String {
        val ordered = entries(json)
        val key = entryKey(profileId, titleKey)
        ordered.remove(key)
        ordered[key] = versionKey
        while (ordered.size > MAX_ENTRIES) ordered.remove(ordered.keys.first())
        val array = JSONArray()
        ordered.forEach { (k, v) -> array.put(JSONArray().put(k).put(v)) }
        return array.toString()
    }
}

/**
 * The picker's state on a TV remote: Up/Down move, OK chooses, Back dismisses. A choice is handed out
 * once — a second OK (a held button, a quick double press) gets nothing, so one press can never start
 * two playback sessions.
 */
class VersionPickerModel(
    val optionCount: Int,
    initialIndex: Int,
    /** Rows listed but not choosable (an episode missing from that version): OK on them does nothing. */
    private val choosable: (Int) -> Boolean = { true },
) {
    var focusedIndex: Int = initialIndex.coerceIn(0, (optionCount - 1).coerceAtLeast(0))
        private set
    var finished: Boolean = false
        private set

    fun moveUp() { if (!finished && focusedIndex > 0) focusedIndex-- }
    fun moveDown() { if (!finished && focusedIndex < optionCount - 1) focusedIndex++ }
    fun focus(index: Int) { if (!finished && index in 0 until optionCount) focusedIndex = index }

    /** The index to play, the first time only. */
    fun choose(index: Int = focusedIndex): Int? {
        if (finished || index !in 0 until optionCount || !choosable(index)) return null
        finished = true
        return index
    }

    /** Back: closes without playing. True the first time. */
    fun dismiss(): Boolean {
        if (finished) return false
        finished = true
        return true
    }
}
