package tv.own.owntv.features.sports

import tv.own.owntv.core.database.entity.ChannelEntity

enum class SportsSection(val title: String, val sortOrder: Int) {
    NFL("NFL", 1),
    NBA("NBA", 2),
    MLB("MLB", 3),
    NHL("NHL", 4),
    NCAAF("NCAAF", 5),
    NCAAB("NCAAB", 6),
    WNBA("WNBA", 7),
    SOCCER("SOCCER", 8),
    BOXING_MMA("BOXING / MMA", 9),
    PPV_SPECIAL("PPV & SPECIAL EVENTS", 10)
}

data class SportsSectionData(
    val section: SportsSection,
    val channels: List<ChannelEntity>
)

fun matchSportsSection(name: String): SportsSection? {
    val upper = name.uppercase()
    val clean = upper.replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    // 1. WNBA (before NBA)
    if ("WNBA" in clean || "W N B A" in clean) return SportsSection.WNBA

    // 2. NCAAF / College Football (before NFL / generic football)
    if ("NCAAF" in clean || "NCAA FOOTBALL" in clean || "COLLEGE FOOTBALL" in clean || "CFB" in clean) {
        return SportsSection.NCAAF
    }

    // 3. NCAAB / College Basketball (before NBA)
    if ("NCAAB" in clean || "NCAA BASKETBALL" in clean || "COLLEGE BASKETBALL" in clean || "CBB" in clean) {
        return SportsSection.NCAAB
    }

    // 4. NFL
    if ("NFL" in clean || "SUNDAY TICKET" in clean || "NFL GAME PASS" in clean || "NFL PACKAGE" in clean) {
        if (!("NCAAF" in clean || "NCAA" in clean || "COLLEGE" in clean)) {
            return SportsSection.NFL
        }
    }

    // 5. NBA
    if ("NBA" in clean || "LEAGUE PASS" in clean) {
        if (!("WNBA" in clean || "NCAAB" in clean || "NCAA" in clean || "COLLEGE" in clean)) {
            return SportsSection.NBA
        }
    }

    // 6. MLB
    if ("MLB" in clean || "EXTRA INNINGS" in clean) {
        return SportsSection.MLB
    }

    // 7. NHL
    if ("NHL" in clean) {
        return SportsSection.NHL
    }

    // 8. SOCCER
    if ("SOCCER" in clean || "EPL" in clean || "PREMIER LEAGUE" in clean || "CHAMPIONS LEAGUE" in clean || "MLS" in clean || "LA LIGA" in clean || "SERIE A" in clean || "BUNDESLIGA" in clean || "LIGUE 1" in clean) {
        return SportsSection.SOCCER
    }
    if ("FOOTBALL" in clean && !("NFL" in clean || "NCAA" in clean || "COLLEGE" in clean || "CFB" in clean || "AMERICAN" in clean)) {
        return SportsSection.SOCCER
    }

    // 9. BOXING / MMA
    if ("BOXING" in clean || "MMA" in clean || "UFC" in clean || "PFL" in clean || "BELLATOR" in clean) {
        return SportsSection.BOXING_MMA
    }

    // 10. PPV & SPECIAL EVENTS
    if ("PPV" in clean || "PAY PER VIEW" in clean || "PAY-PER-VIEW" in clean || "SPECIAL EVENT" in clean || "SPECIAL EVENTS" in clean) {
        return SportsSection.PPV_SPECIAL
    }

    return null
}
