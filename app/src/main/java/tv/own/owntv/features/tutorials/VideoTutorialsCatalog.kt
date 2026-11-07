package tv.own.owntv.features.tutorials

import org.json.JSONArray
import org.json.JSONObject

/**
 * One published tutorial as the public `get_video_tutorials(p_brand_id)` RPC returns it. The RPC only
 * exposes display metadata (no status, enabled flag, brand list or author), and the server has already
 * applied brand, publish-state and publish-window filtering.
 */
data class VideoTutorial(
    val id: String,
    val title: String,
    val description: String?,
    /** Always a validated 11-character YouTube id ([YouTubeVideoId.isValid]); rows without one are dropped. */
    val youtubeVideoId: String,
    /** Admin category slug (`getting-started`, `live-tv`, …); null = uncategorized. */
    val category: String?,
    val durationSeconds: Int?,
    val sortOrder: Int,
)

/** Tutorials of one category, in catalog order. */
data class VideoTutorialGroup(val category: String?, val tutorials: List<VideoTutorial>)

/** The YouTube id is the only part of a catalog row that ever reaches a URL, and only after this check. */
object YouTubeVideoId {
    private val PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

    fun isValid(id: String?): Boolean = id != null && PATTERN.matches(id)

    /** Thumbnail built by the app from a validated id; catalog data never supplies an image URL. */
    fun thumbnailUrl(id: String): String? = if (isValid(id)) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null
}

/**
 * Pure parser for the RPC's JSON array (org.json, like the other API parsers). Tolerant per row: a row
 * with a missing id/title or an invalid YouTube id is dropped on its own; a body that is not a JSON
 * array yields null (→ "couldn't load"). Duplicate ids keep the first row.
 */
internal object VideoTutorialsParser {
    private const val MAX_TITLE = 120
    private const val MAX_DESCRIPTION = 1000
    private val CATEGORY = Regex("^[a-z0-9-]{1,40}$")

    fun parse(body: String): List<VideoTutorial>? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val seen = HashSet<String>()
        val out = ArrayList<VideoTutorial>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val tutorial = parseRow(row) ?: continue
            if (seen.add(tutorial.id)) out += tutorial
        }
        return out.sortedWith(compareBy<VideoTutorial> { it.sortOrder }.thenBy { it.title.lowercase() }.thenBy { it.id })
    }

    private fun parseRow(row: JSONObject): VideoTutorial? {
        val id = row.optStringOrNull("id") ?: return null
        val title = row.optStringOrNull("title")?.take(MAX_TITLE) ?: return null
        val videoId = row.optStringOrNull("youtube_video_id")?.takeIf(YouTubeVideoId::isValid) ?: return null
        return VideoTutorial(
            id = id,
            title = title,
            description = row.optStringOrNull("description")?.take(MAX_DESCRIPTION),
            youtubeVideoId = videoId,
            category = row.optStringOrNull("category")?.lowercase()?.takeIf { CATEGORY.matches(it) },
            durationSeconds = if (row.isNull("duration_seconds")) null else row.optInt("duration_seconds", 0).takeIf { it > 0 },
            sortOrder = row.optInt("sort_order", 0),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").trim().takeIf { it.isNotEmpty() }
}

/**
 * Category sections in catalog order: a category appears where its first tutorial sits in the admin's
 * ordering, and every tutorial keeps its relative order inside its section. Uncategorized tutorials
 * form one section (category = null) placed the same way.
 */
internal fun groupByCategory(tutorials: List<VideoTutorial>): List<VideoTutorialGroup> {
    val groups = LinkedHashMap<String?, MutableList<VideoTutorial>>()
    for (t in tutorials) groups.getOrPut(t.category) { ArrayList() } += t
    return groups.map { (category, items) -> VideoTutorialGroup(category, items) }
}
