package tv.own.owntv.features.sports.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.own.owntv.R
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/** Broadcast-style live red (the theme has no error role); used only for LIVE indicators. */
internal val LiveRed = Color(0xFFE53935)

/** Near-white disc behind team logos (contrast for navy/black marks on dark cards). */
internal val LogoDisc = Color(0xFFF2F4F5)

/** Cards sit on their own dark backdrop, so their text is always light regardless of theme. */
internal val CardText = Color(0xFFF5F7F8)
internal val CardTextMuted = Color(0xFFB9C3C7)

private val FeaturedCardWidth = 400.dp
private val FeaturedCardHeight = 204.dp
private val StandardCardWidth = 300.dp
private val StandardCardHeight = 164.dp
private val CardShape = RoundedCornerShape(18.dp)

/**
 * One horizontal row: Popular Events (featured cards), a league, or a sport group (Soccer). Items
 * are keyed by event id, so focus survives refreshes and reordering.
 */
@Composable
fun SportsEventRow(
    section: SportsEventSection,
    leaguesById: Map<String, SportsLeague>,
    /** Click with the card's own [FocusRequester], so the caller can return focus after a dialog. */
    onEventClick: (SportsEvent, FocusRequester) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    /** A card gained focus (drives the sticky preview's Game Center; must stay cheap — no I/O). */
    onEventFocused: (SportsEvent) -> Unit = {},
    /** Keeps / restores this row's position and focused card across fullscreen / Multiscreen. */
    restorer: SportsBrowseRestorer? = null,
    /**
     * Event OK gestures (single → Where to Watch, double → Game Center ↔ video, long → Multiscreen).
     * Null while event channels are off: OK keeps opening Game Details via [onEventClick], undelayed.
     */
    okGestures: SportsEventOkHandlers? = null,
) {
    val featured = section.key == SportsSlateLogic.POPULAR_KEY
    val rowKey = "events:" + section.key
    val keys: List<Any> = remember(section.events) { section.events.map { it.id } }
    val listState = rememberSportsRowState(restorer, rowKey, keys)
    val requesters = remember(rowKey) { HashMap<Any, FocusRequester>() }
    SportsRowFocusRestore(restorer, rowKey, keys, listState, requesters)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SportsRowHeader(
            title = section.title,
            subtitle = section.competitions.mapNotNull { SportsEventPresentation.competitionLabel(it) }
                .takeIf { it.size > 1 }
                ?.joinToString(stringResource(R.string.sports_list_separator)),
        )
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) onFocused() },
            // Vertical room for the focus scale + glow, so the focused card is never clipped.
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(section.events, key = { _, e -> e.id }) { index, event ->
                val requester = remember(event.id) { requesters.getOrPut(event.id) { FocusRequester() } }
                val league = leaguesById[event.leagueId]
                // Mixed rows (Popular, sport groups) label each card with its competition.
                val showCompetition = section.league == null
                val cardModifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (it.hasFocus) {
                            restorer?.onItemFocused(rowKey, event.id, index)
                            onEventFocused(event)
                        }
                    }
                    .then(
                        if (okGestures != null) {
                            Modifier.sportsOkGestures(
                                onSingle = { okGestures.onSingle(event, requester) },
                                onDouble = { okGestures.onDouble(event) },
                                onLong = { okGestures.onLong(event, requester) },
                            )
                        } else {
                            Modifier
                        },
                    )
                if (SportsEventPresentation.isFightCard(event, league)) {
                    SportsFightCard(
                        event = event,
                        league = league,
                        featured = featured,
                        onClick = { onEventClick(event, requester) },
                        modifier = cardModifier,
                    )
                } else if (featured) {
                    SportsFeaturedCard(
                        event = event,
                        league = league,
                        onClick = { onEventClick(event, requester) },
                        modifier = cardModifier,
                    )
                } else {
                    SportsEventCard(
                        event = event,
                        league = league,
                        showCompetition = showCompetition,
                        onClick = { onEventClick(event, requester) },
                        modifier = cardModifier,
                    )
                }
            }
        }
    }
}

/** Section heading shared by event rows and the Sports Channels section. */
@Composable
fun SportsRowHeader(title: String, subtitle: String? = null, prominent: Boolean = false) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier.padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(if (prominent) 26.dp else 20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.primary),
        )
        Text(
            text = title,
            style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Popular Events: larger, cinematic matchup card. */
@Composable
fun SportsFeaturedCard(
    event: SportsEvent,
    league: SportsLeague?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = SportsEventPresentation.visualKind(league, event.leagueId)
    val awayAccent = rememberTeamAccent(event.away?.logoUrl)
    val homeAccent = rememberTeamAccent(event.home?.logoUrl)
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(FeaturedCardWidth).height(FeaturedCardHeight),
        shape = CardShape,
        focusedScale = 1.035f,
        glowElevation = 14,
        unfocusedContainerColor = backdropPalette(kind).bottom,
        focusedContainerColor = backdropPalette(kind).bottom,
        contentAlignment = Alignment.TopStart,
    ) { focused ->
        val focusAmount by animateFloatAsState(if (focused) 1f else 0f, label = "featuredFocus")
        Box(
            Modifier
                .fillMaxSize()
                .sportsBackdrop(kind, { focusAmount }, awayAccent, homeAccent, accentY = 0.48f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                CardTopRow(event, SportsEventPresentation.competitionLabel(league) ?: event.leagueId.uppercase())
                if (event.isTeamEvent) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FeaturedTeam(event.away!!, Modifier.weight(1f))
                        MatchupCenter(event, compact = false, modifier = Modifier.widthIn(min = 72.dp))
                        FeaturedTeam(event.home!!, Modifier.weight(1f))
                    }
                } else {
                    EventHeadline(event, maxLines = 2, large = true)
                }
                CardFooter(event, centered = event.isTeamEvent)
            }
        }
    }
}

/** League / sport-group rows: compact two-line matchup card. */
@Composable
fun SportsEventCard(
    event: SportsEvent,
    league: SportsLeague?,
    /** Competition badge for mixed rows (Soccer group); a single-league row already says it. */
    showCompetition: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = SportsEventPresentation.visualKind(league, event.leagueId)
    val awayAccent = rememberTeamAccent(event.away?.logoUrl)
    val homeAccent = rememberTeamAccent(event.home?.logoUrl)
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(StandardCardWidth).height(StandardCardHeight),
        shape = CardShape,
        focusedScale = 1.04f,
        glowElevation = 10,
        unfocusedContainerColor = backdropPalette(kind).bottom,
        focusedContainerColor = backdropPalette(kind).bottom,
        contentAlignment = Alignment.TopStart,
    ) { focused ->
        val focusAmount by animateFloatAsState(if (focused) 1f else 0f, label = "cardFocus")
        Box(
            Modifier
                .fillMaxSize()
                .sportsBackdrop(kind, { focusAmount }, awayAccent, homeAccent, accentY = 0.55f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                CardTopRow(event, if (showCompetition) SportsEventPresentation.competitionLabel(league) ?: event.leagueId.uppercase() else null)
                if (event.isTeamEvent) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        TeamLine(event.away!!, event, isHome = false)
                        TeamLine(event.home!!, event, isHome = true)
                    }
                } else {
                    EventHeadline(event, maxLines = 2, large = false)
                }
                CardFooter(event, centered = false)
            }
        }
    }
}

@Composable
private fun CardTopRow(event: SportsEvent, competition: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (competition != null) CompetitionChip(competition) else Spacer(Modifier.width(1.dp))
        EventStatusChip(event)
    }
}

/** Subtle competition identity ("EPL", "UCL", "Liga MX", "NFL"). */
@Composable
fun CompetitionChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TeamLine(team: SportsTeam, event: SportsEvent, isHome: Boolean) {
    val loser = event.status == SportsEventStatus.FINAL && event.showsScores && isLoser(event, isHome)
    val textColor = if (loser) CardTextMuted else CardText
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TeamLogo(team, size = 34.dp)
        Text(
            text = team.shortName ?: team.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (loser) FontWeight.Medium else FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (event.showsScores) {
            Text(
                text = team.score ?: stringResource(R.string.sports_score_unknown),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
        }
    }
}

@Composable
private fun FeaturedTeam(team: SportsTeam, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamLogo(team, size = 60.dp)
        Text(
            text = team.shortName ?: team.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = CardText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Center of a matchup: the score once play has started, otherwise "at" + kickoff. */
@Composable
private fun MatchupCenter(event: SportsEvent, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (event.showsScores) {
            Text(
                text = stringResource(
                    R.string.sports_score_line,
                    event.away?.score ?: stringResource(R.string.sports_score_unknown),
                    event.home?.score ?: stringResource(R.string.sports_score_unknown),
                ),
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(R.string.sports_event_at).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CardTextMuted,
            )
        }
    }
}

@Composable
private fun EventHeadline(event: SportsEvent, maxLines: Int, large: Boolean) {
    Text(
        text = event.title ?: listOfNotNull(event.away?.name, event.home?.name).joinToString(stringResource(R.string.sports_list_separator)),
        style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = CardText,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Bottom line: the backend note (leg / shootout result) and the primary broadcast. */
@Composable
private fun CardFooter(event: SportsEvent, centered: Boolean) {
    val parts = listOfNotNull(SportsEventPresentation.note(event), event.primaryBroadcast?.name)
    Text(
        text = parts.joinToString(stringResource(R.string.sports_list_separator)),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = CardTextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun isLoser(event: SportsEvent, isHome: Boolean): Boolean {
    val home = event.home?.score?.toIntOrNull() ?: return false
    val away = event.away?.score?.toIntOrNull() ?: return false
    return if (isHome) home < away else away < home
}

/** Team logo at its natural aspect ratio (never stretched) in fixed bounds; initials when absent. */
@Composable
fun TeamLogo(team: SportsTeam, size: Dp) {
    if (!team.logoUrl.isNullOrBlank()) {
        // Light disc behind the mark: many team logos are navy/brown/black and vanish on dark cards.
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(LogoDisc)
                .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = team.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(size / 8).fillMaxSize(),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (team.abbreviation ?: team.name).take(3).uppercase(),
                style = if (size >= 56.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = CardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * SCHEDULED → local day/time · LIVE → red pill + detail ("60'", "Halftime") · FINAL (+ AET / Pens /
 * OT as sent) · DELAYED / POSTPONED / CANCELED → labelled pill.
 */
@Composable
fun EventStatusChip(event: SportsEvent) {
    val sep = stringResource(R.string.sports_list_separator)
    when (val s = SportsEventPresentation.status(event)) {
        is SportsEventPresentation.Status.Live -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Pill(stringResource(R.string.sports_live_badge), LiveRed, Color.White)
            val detail = if (s.halftime) stringResource(R.string.sports_status_halftime) else s.detail
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SportsEventPresentation.Status.Scheduled -> Text(
            text = rememberStartLabel(event.startTimeMs),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = CardText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        is SportsEventPresentation.Status.Final -> {
            val extra = when (val x = s.extra) {
                SportsEventPresentation.FinalExtra.ExtraTime -> stringResource(R.string.sports_final_extra_time)
                SportsEventPresentation.FinalExtra.Penalties -> stringResource(R.string.sports_final_penalties)
                is SportsEventPresentation.FinalExtra.Raw -> x.text
                null -> null
            }
            Pill(
                text = listOfNotNull(stringResource(R.string.sports_status_final), extra).joinToString(sep),
                background = Color.White.copy(alpha = 0.16f),
                content = CardText,
            )
        }
        SportsEventPresentation.Status.Delayed -> Pill(stringResource(R.string.sports_status_delayed), Color(0xFFB8860B), Color.White)
        SportsEventPresentation.Status.Postponed -> Pill(stringResource(R.string.sports_status_postponed), Color.White.copy(alpha = 0.12f), CardTextMuted)
        SportsEventPresentation.Status.Canceled -> Pill(stringResource(R.string.sports_status_canceled), Color.White.copy(alpha = 0.12f), CardTextMuted)
    }
}

@Composable
internal fun Pill(text: String, background: Color, content: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Today 7:30 PM" / "Tomorrow 1:00 PM" / "Sat, Oct 4 1:00 PM" in the device's zone and locale. */
@Composable
fun rememberStartLabel(startMs: Long): String {
    val time = rememberSystemTimeFormatter()
    val date = rememberBestDateFormatter("EEEMMMd")
    val today = stringResource(R.string.sports_day_today)
    val tomorrow = stringResource(R.string.sports_day_tomorrow)
    val dayTime = stringResource(R.string.sports_day_time)
    return remember(startMs, time, date, today, tomorrow, dayTime) {
        val day = when (SportsTimeLabels.dayOffset(startMs, System.currentTimeMillis())) {
            0 -> today
            1 -> tomorrow
            else -> date(startMs)
        }
        String.format(dayTime, day, time(startMs))
    }
}

/**
 * Game details (OK on a card). The action bar renders [SportsEventPresentation.detailActions]: while
 * channels are off (Phase C1) that is Close only — never Watch / Select Channel without a resolved
 * channel. Phase C2 adds its actions to that list and handlers here, without redesigning the panel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportsEventDetails(
    event: SportsEvent,
    league: SportsLeague?,
    onDismiss: () -> Unit,
    /** From [SportsEventPresentation.detailActions] over LOCALLY verified channels (Close only otherwise). */
    actions: List<SportsEventAction> = SportsEventPresentation.detailActions(event),
    onAction: (SportsEventAction) -> Unit = {},
) {
    val colors = OwnTVTheme.colors
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstAction.requestFocus() } }
    BackHandler { onDismiss() }
    val kind = SportsEventPresentation.visualKind(league, event.leagueId)
    val awayAccent = rememberTeamAccent(event.away?.logoUrl)
    val homeAccent = rememberTeamAccent(event.home?.logoUrl)
    val sep = stringResource(R.string.sports_list_separator)

    Box(
        modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.dialogPanel(width = 780.dp, corner = 24.dp, padding = 0.dp)) {
            // --- Hero: competition, status, crests, score ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (event.isTeamEvent) 268.dp else 180.dp)
                    .sportsBackdrop(kind, { 0.65f }, awayAccent, homeAccent, accentY = 0.50f),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = league?.name ?: event.leagueId.uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CardText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        EventStatusChip(event)
                    }
                    if (event.isTeamEvent) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DetailTeam(event.away!!, Modifier.weight(1f))
                            MatchupCenter(event, compact = false, modifier = Modifier.widthIn(min = 120.dp))
                            DetailTeam(event.home!!, Modifier.weight(1f))
                        }
                    } else {
                        EventHeadline(event, maxLines = 2, large = true)
                    }
                    Text(
                        text = SportsEventPresentation.note(event).orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = CardTextMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // --- Info + broadcasts + actions ---
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val start = rememberStartLabel(event.startTimeMs)
                val info = listOfNotNull(
                    if (event.status == SportsEventStatus.SCHEDULED) stringResource(R.string.sports_event_starts, start) else start,
                    event.venueName,
                )
                Text(
                    text = info.joinToString(sep),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.sports_event_broadcasts).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceVariant,
                    )
                    val visible = event.broadcasts.filter { !it.type.equals("RADIO", ignoreCase = true) }
                    if (visible.isEmpty()) {
                        Text(stringResource(R.string.sports_event_no_broadcasts), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            visible.forEach { BroadcastChip(it.name) }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    actions.forEachIndexed { index, action ->
                        val focusModifier = if (index == 0) Modifier.focusRequester(firstAction) else Modifier
                        when (action) {
                            SportsEventAction.CLOSE -> DetailActionButton(
                                icon = OwnTVIcon.CLOSE,
                                label = stringResource(R.string.content_close),
                                onClick = onDismiss,
                                modifier = focusModifier,
                            )
                            SportsEventAction.WATCH -> DetailActionButton(
                                icon = OwnTVIcon.PLAY,
                                label = stringResource(R.string.sports_event_watch),
                                onClick = { onAction(action) },
                                modifier = focusModifier,
                            )
                            SportsEventAction.SELECT_CHANNEL -> DetailActionButton(
                                icon = OwnTVIcon.LIVE_TV,
                                label = stringResource(R.string.sports_event_select_channel),
                                onClick = { onAction(action) },
                                modifier = focusModifier,
                            )
                            SportsEventAction.ADD_TO_MULTISCREEN -> DetailActionButton(
                                icon = OwnTVIcon.ADD,
                                label = stringResource(R.string.content_multiscreen_add),
                                onClick = { onAction(action) },
                                modifier = focusModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTeam(team: SportsTeam, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamLogo(team, size = 92.dp)
        Text(
            text = team.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = CardText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BroadcastChip(name: String) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OwnTVIcon(icon = OwnTVIcon.LIVE_TV, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailActionButton(icon: OwnTVIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.widthIn(min = 160.dp),
        shape = RoundedCornerShape(12.dp),
        surface = GlassSurface.DIALOGS,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon = icon, tint = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (focused) colors.onPrimaryContainer else colors.onSurface)
        }
    }
}

/**
 * What single / double / long OK do on an event card (each receives the card's focus requester to
 * return to). While [blocked] is true (an event modal owns input) none of them fires.
 */
class SportsEventOkHandlers(
    private val blocked: () -> Boolean = { false },
    onSingle: (SportsEvent, FocusRequester) -> Unit,
    onDouble: (SportsEvent) -> Unit,
    onLong: (SportsEvent, FocusRequester) -> Unit,
) {
    val onSingle: (SportsEvent, FocusRequester) -> Unit = { e, r -> if (!blocked()) onSingle(e, r) }
    val onDouble: (SportsEvent) -> Unit = { e -> if (!blocked()) onDouble(e) }
    val onLong: (SportsEvent, FocusRequester) -> Unit = { e, r -> if (!blocked()) onLong(e, r) }
}
