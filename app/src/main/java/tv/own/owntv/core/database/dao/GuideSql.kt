package tv.own.owntv.core.database.dao

/**
 * The TV Guide's channel-matching SQL, shared by the DAOs and the unit tests that run it on a real SQLite
 * (so the tests check the statements the app runs, not copies of them).
 */
object GuideSql {
    /**
     * Channels that HAVE programmes, in guide order, cut at :limit — Phase 4B's one-statement version, kept
     * as the reference the Phase 4C path is tested against. The id match is case/whitespace-insensitive
     * (XMLTV ids often differ from the panel's epg_channel_id in case). The programme table is already
     * pruned to the retained window, so there is no time filter, which lets the DISTINCT use the
     * (sourceId, epgChannelId) index; programme ids are stored normalized.
     */
    const val CHANNELS_WITH_GUIDE =
        "SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND epgChannelId IS NOT NULL AND epgChannelId != '' " +
            "AND (:query = '' OR name LIKE '%' || :query || '%') " +
            "AND LOWER(TRIM(epgChannelId)) IN (" +
            "  SELECT DISTINCT epgChannelId FROM epg_programmes WHERE sourceId IN (:sourceIds)" +
            ") ORDER BY number ASC, name ASC LIMIT :limit"

    /** The same candidates in the same order as [CHANNELS_WITH_GUIDE], as (id, normalized key) only. */
    const val GUIDE_CANDIDATES =
        "SELECT id, LOWER(TRIM(epgChannelId)) AS guideKey FROM channels WHERE sourceId IN (:sourceIds) " +
            "AND epgChannelId IS NOT NULL AND epgChannelId != '' " +
            "AND (:query = '' OR name LIKE '%' || :query || '%') ORDER BY number ASC, name ASC"

    /** The guide keys with programmes: [CHANNELS_WITH_GUIDE]'s sub-select, run on its own. */
    const val GUIDE_KEYS = "SELECT DISTINCT epgChannelId FROM epg_programmes WHERE sourceId IN (:sourceIds)"

    /**
     * One page of the guide window preload: the programmes overlapping [:from, :to), in id order after
     * :afterId, without the synopsis (fetched on demand).
     */
    const val WINDOW_PAGE =
        "SELECT id, sourceId, epgChannelId, startMs, stopMs, title, NULL AS description, contentHash FROM epg_programmes " +
            "WHERE sourceId IN (:sourceIds) AND stopMs > :from AND startMs < :to AND id > :afterId ORDER BY id ASC LIMIT :limit"

    /**
     * 4.6.0: the programmes of some guide keys that START in [:from, :to): what the window gained when it moved
     * on (GuideWindowMove). One short range per key on the (epgChannelId, startMs) index, instead of the
     * whole-table pass [WINDOW_PAGE] makes.
     */
    const val WINDOW_TOP_UP =
        "SELECT id, sourceId, epgChannelId, startMs, stopMs, title, NULL AS description, contentHash FROM epg_programmes " +
            "WHERE epgChannelId IN (:epgKeys) AND sourceId IN (:sourceIds) AND startMs >= :from AND startMs < :to"

    /** Full channel rows by primary key. */
    const val CHANNELS_BY_IDS = "SELECT * FROM channels WHERE id IN (:ids)"
}
