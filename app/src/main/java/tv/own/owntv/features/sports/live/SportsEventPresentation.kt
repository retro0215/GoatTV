package tv.own.owntv.features.sports.live

/**
 * Pure presentation rules for event cards and the details panel (unit-tested). Everything shown is
 * derived from backend fields only: no aggregate scores, shootout results or legs are computed here.
 */
internal object SportsEventPresentation {

    /** Status line content; the UI maps it to localized text. */
    sealed interface Status {
        data class Live(
            /** Backend detail as sent ("60'", "2nd Half", "Q3 4:12"); null when absent. */
            val detail: String?,
            val halftime: Boolean,
        ) : Status

        data class Final(val extra: FinalExtra?) : Status
        data object Scheduled : Status
        data object Delayed : Status
        data object Postponed : Status
        data object Canceled : Status
    }

    sealed interface FinalExtra {
        /** Soccer: decided after extra time (backend detail "AET"). */
        data object ExtraTime : FinalExtra

        /** Soccer: decided on penalties (backend detail "FT-Pens"); the result text is the event title. */
        data object Penalties : FinalExtra

        /** US overtime etc. as sent by the backend ("OT", "2OT", "10"): "Final/OT" → "OT". */
        data class Raw(val text: String) : FinalExtra
    }

    fun status(event: SportsEvent): Status = when (event.status) {
        SportsEventStatus.LIVE -> {
            val detail = event.statusDetail?.trim()?.takeIf { it.isNotEmpty() }
            val halftime = detail != null && (detail.equals("HT", ignoreCase = true) || detail.equals("Halftime", ignoreCase = true))
            Status.Live(if (halftime) null else detail, halftime)
        }
        SportsEventStatus.FINAL -> Status.Final(finalExtra(event.statusDetail))
        SportsEventStatus.DELAYED -> Status.Delayed
        SportsEventStatus.POSTPONED -> Status.Postponed
        SportsEventStatus.CANCELED -> Status.Canceled
        SportsEventStatus.SCHEDULED, SportsEventStatus.UNKNOWN -> Status.Scheduled
    }

    internal fun finalExtra(detail: String?): FinalExtra? {
        val d = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val upper = d.uppercase()
        return when {
            upper == "AET" || upper.contains("EXTRA TIME") -> FinalExtra.ExtraTime
            upper.contains("PEN") -> FinalExtra.Penalties
            upper == "FT" || upper == "FINAL" || upper == "FULL TIME" -> null
            upper.startsWith("FINAL/") -> d.substringAfter('/').trim().takeIf { it.isNotEmpty() }?.let { FinalExtra.Raw(it) }
            else -> null
        }
    }

    /**
     * Secondary line from the backend title for team events: leg ("1st Leg"), a shootout result
     * ("Toluca win 6-5 on penalties") or a round name. Title-only events use the title as their headline
     * instead, so they get no note.
     */
    fun note(event: SportsEvent): String? =
        if (event.isTeamEvent) event.title?.trim()?.takeIf { it.isNotEmpty() } else null

    /** Short competition label for a card ("EPL", "UCL", "Liga MX"); null when the league is unknown. */
    fun competitionLabel(league: SportsLeague?): String? =
        league?.shortName?.takeIf { it.isNotBlank() } ?: league?.name?.takeIf { it.isNotBlank() }

    /** Which local backdrop a card uses. College football is distinct; everything else by sport. */
    fun visualKind(league: SportsLeague?, leagueId: String): SportsVisualKind {
        if (leagueId == "ncaaf") return SportsVisualKind.COLLEGE_FOOTBALL
        return when (league?.sport?.lowercase()) {
            "football" -> SportsVisualKind.FOOTBALL
            "basketball" -> SportsVisualKind.BASKETBALL
            "baseball" -> SportsVisualKind.BASEBALL
            "hockey" -> SportsVisualKind.HOCKEY
            "soccer" -> SportsVisualKind.SOCCER
            else -> SportsVisualKind.GENERIC
        }
    }

    /**
     * Details-panel actions. While [channelsEnabled] is false (Phase C1, `SPORTS_API_CHANNELS` off) the
     * only valid action is Close — Watch / Select Channel / Multiscreen are never offered without a
     * resolved channel. C2 adds them here; the panel renders whatever this returns.
     */
    fun detailActions(event: SportsEvent, channelsEnabled: Boolean = SportsChannelFeature.ENABLED): List<SportsEventAction> {
        if (!channelsEnabled || event.channels.isEmpty()) return listOf(SportsEventAction.CLOSE)
        return buildList {
            add(SportsEventAction.WATCH)
            if (event.channels.size > 1) add(SportsEventAction.SELECT_CHANNEL)
            add(SportsEventAction.ADD_TO_MULTISCREEN)
            add(SportsEventAction.CLOSE)
        }
    }
}

enum class SportsVisualKind { FOOTBALL, COLLEGE_FOOTBALL, BASKETBALL, BASEBALL, HOCKEY, SOCCER, GENERIC }

enum class SportsEventAction { WATCH, SELECT_CHANNEL, ADD_TO_MULTISCREEN, CLOSE }
