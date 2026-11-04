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

    /** Full channel rows by primary key. */
    const val CHANNELS_BY_IDS = "SELECT * FROM channels WHERE id IN (:ids)"
}
