package tv.own.owntv.core.catalog.versions

/**
 * Which rows are versions of the same movie or series as an anchor — pure, so every rule is unit-tested.
 *
 * Conservative by design: a wrong pairing (two different films behind one "Watch") is far worse than a
 * missed one, which the customer can still find by searching. The candidates all share the anchor's
 * `titleSignature` (the cleaned title as letters and digits: tags, quality words, language prefixes and
 * years removed when the row was written). On top of that:
 *
 * - **Years.** Two explicit release years must be equal ("Dune" 1984 and 2021 never pair). A row with no
 *   year joins only when every explicit year among the candidates is one and the same — then the title is
 *   unambiguous. With no year anywhere, or two different ones, a yearless anchor stays on its own.
 * - **Short and numeric titles** ("Up", "It", "300") need an explicit, equal year on both sides.
 * - **Generic titles** ("Movie", "Live", "Trailer") and titles the parser could not clean never group, and
 *   neither does a signature shared by so many rows that the lookup hit its limit.
 *
 * Provider ids, stream URLs, playlists and categories are left untouched: a version is the row as it is.
 */
object VersionMatcher {

    /** Signatures shorter than this ("up", "it", "her") need an explicit, equal year to group. */
    const val SHORT_SIGNATURE = 4

    /** What the parser produces for a title it could not clean; such a row is never grouped. */
    private const val UNMATCHABLE_PREFIX = "__unmatchable_"

    /** Signatures that name no particular title. */
    private val GENERIC = setOf(
        "movie", "movies", "film", "films", "series", "show", "shows", "tv", "live", "trailer", "trailers",
        "test", "untitled", "unknown", "episode", "episodes", "season", "vod", "new", "the", "a",
    )

    /** Whether rows with [signature] may group at all (as opposed to never). */
    fun isGroupable(signature: String): Boolean =
        signature.isNotBlank() && !signature.startsWith(UNMATCHABLE_PREFIX) && signature !in GENERIC

    /** Whether [signature] is too short or numeric to group without an explicit, equal year. */
    fun needsExplicitYear(signature: String): Boolean =
        signature.length < SHORT_SIGNATURE || signature.all(Char::isDigit)

    /**
     * The versions of [anchor] among [candidates] (the anchor included, first), or just the anchor when
     * nothing can safely be grouped with it. [limitReached]: the lookup returned as many rows as it
     * allows, so this signature is shared too widely to trust.
     */
    fun versionsOf(
        anchor: VersionCandidateRow,
        candidates: List<VersionCandidateRow>,
        limitReached: Boolean = false,
    ): List<VersionCandidateRow> {
        val alone = listOf(anchor)
        if (limitReached || !isGroupable(anchor.titleSignature)) return alone
        val same = candidates.filter { it.titleSignature == anchor.titleSignature && it.id != anchor.id }
        if (same.isEmpty()) return alone

        val explicitYears = (same.mapNotNull { it.parsedYear } + listOfNotNull(anchor.parsedYear)).toSet()
        val strict = needsExplicitYear(anchor.titleSignature)
        val year: Int = anchor.parsedYear
            ?: explicitYears.singleOrNull()?.takeUnless { strict }
            ?: return alone // no year anywhere, two different years, or a short title without one
        // A yearless row is safe only when the candidates name exactly this one year.
        val yearlessAllowed = !strict && explicitYears == setOf(year)

        val members = same.filter { row ->
            when (val y = row.parsedYear) {
                null -> yearlessAllowed
                else -> y == year
            }
        }
        if (anchor.parsedYear == null && !yearlessAllowed) return alone
        return alone + members
    }

    /**
     * A key two rows share only when [versionsOf] would certainly group them: the same groupable signature
     * and the same explicit year (a short or numeric title needs exactly that too). Null when that is not
     * certain — such a row is never folded into another. For folding lists such as Continue Watching,
     * where the rows are already the profile's own.
     */
    fun certainGroupKey(signature: String, year: Int?): String? =
        if (year != null && isGroupable(signature)) "$signature|$year" else null

    /**
     * [items] with every version but the most recently used one of each title dropped, in their original
     * order. Items with no certain group key ([certainGroupKey] null) all stay — nothing is folded unless
     * it is certainly the same title.
     */
    fun <T> withoutRepeatedVersions(items: List<T>, lastUsedOf: (T) -> Long, groupKeyOf: (T) -> String?): List<T> {
        val newestPerTitle = HashMap<String, T>()
        for (item in items) {
            val key = groupKeyOf(item) ?: continue
            val kept = newestPerTitle[key]
            if (kept == null || lastUsedOf(item) > lastUsedOf(kept)) newestPerTitle[key] = item
        }
        return items.filter { item -> groupKeyOf(item)?.let { newestPerTitle[it] === item } ?: true }
    }

    /**
     * Order for presenting versions: best advertised quality first, then a stated picture format, then by
     * playlist and row, so the order never changes between two looks at the same catalogue.
     */
    fun ordered(versions: List<Pair<VersionCandidateRow, VersionLabel>>): List<Pair<VersionCandidateRow, VersionLabel>> =
        versions.sortedWith(
            compareByDescending<Pair<VersionCandidateRow, VersionLabel>> { it.second.qualityRank }
                .thenBy { it.second.advertisedPicture?.ordinal ?: Int.MAX_VALUE }
                .thenBy { it.first.sourceId }
                .thenBy { it.first.id },
        )
}
