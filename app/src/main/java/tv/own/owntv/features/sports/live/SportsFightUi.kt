package tv.own.owntv.features.sports.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
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
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

private val FightText = Color(0xFFF5F7F8)
private val FightTextMuted = Color(0xFFB9C3C7)
private val FightCardShape = RoundedCornerShape(18.dp)

/**
 * Fight card (MMA / boxing) for the MMA / Boxing rows and Popular Events: card title,
 * main event fighters and time/broadcast. Everything shown comes from the backend; a card
 * without bouts yet still renders from its title.
 */
@Composable
fun SportsFightCard(
    event: SportsEvent,
    league: SportsLeague?,
    featured: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = SportsEventPresentation.visualKind(league, event.leagueId)
    val main = event.fight?.mainEvent
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(if (featured) 400.dp else 300.dp).height(if (featured) 204.dp else 164.dp),
        shape = FightCardShape,
        focusedScale = if (featured) 1.035f else 1.04f,
        glowElevation = if (featured) 14 else 10,
        unfocusedContainerColor = backdropPalette(kind).bottom,
        focusedContainerColor = backdropPalette(kind).bottom,
        contentAlignment = Alignment.TopStart,
    ) { focused ->
        val focusAmount by animateFloatAsState(if (focused) 1f else 0f, label = "fightFocus")
        Box(Modifier.fillMaxSize().sportsBackdrop(kind, { focusAmount }, accentY = 0.6f)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    CompetitionChip(SportsEventPresentation.competitionLabel(league) ?: event.leagueId.uppercase())
                    EventStatusChip(event)
                }
                Text(
                    text = event.title ?: "",
                    style = if (featured) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = FightText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (main != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = stringResource(R.string.sports_fight_main_event).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FightTextMuted,
                        )
                        FighterLine(main.fighters[0], winner = main.winner == 0, size = if (featured) 30.dp else 26.dp)
                        FighterLine(main.fighters[1], winner = main.winner == 1, size = if (featured) 30.dp else 26.dp)
                    }
                } else {
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    text = listOfNotNull(main?.weightClass, event.primaryBroadcast?.name).joinToString(stringResource(R.string.sports_list_separator)),
                    style = MaterialTheme.typography.labelMedium,
                    color = FightTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FighterLine(fighter: SportsFighter, winner: Boolean, size: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        FighterBadge(fighter, size)
        Text(
            text = fighter.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (winner) FontWeight.Bold else FontWeight.SemiBold,
            color = FightText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        fighter.record?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = FightTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Country flag when supplied (never a stretched image), otherwise the fighter's initials. */
@Composable
fun FighterBadge(fighter: SportsFighter, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!fighter.flagUrl.isNullOrBlank()) {
            AsyncImage(model = fighter.flagUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = SportsEventPresentation.initials(fighter.name),
                style = if (size >= 56.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FightText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Result line for a completed bout ("KO/TKO · R1 1:02") or null; only provider-stated parts. */
@Composable
internal fun boutResult(bout: SportsBout): String? {
    if (bout.status != SportsEventStatus.FINAL) return null
    val method = when (bout.method) {
        "KO/TKO" -> stringResource(R.string.sports_fight_method_ko)
        "Submission" -> stringResource(R.string.sports_fight_method_submission)
        "Decision" -> stringResource(R.string.sports_fight_method_decision)
        else -> null
    }
    val roundClock = if (bout.round != null && bout.clock != null) stringResource(R.string.sports_fight_round_clock, bout.round, bout.clock) else null
    return listOfNotNull(method, roundClock).joinToString(stringResource(R.string.sports_list_separator)).ifEmpty { null }
}

/**
 * Fight Details: card header, the main event large, then the whole card grouped by the
 * provider's start times (segment names are not supplied). Close is the only action while
 * channels are off; the action list is shared with Game Details so C2 adds Watch there.
 */
@Composable
fun SportsFightDetails(event: SportsEvent, league: SportsLeague?, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    BackHandler { onDismiss() }
    val kind = SportsEventPresentation.visualKind(league, event.leagueId)
    val fight = event.fight
    val main = fight?.mainEvent
    val sep = stringResource(R.string.sports_list_separator)
    val time = rememberSystemTimeFormatter()

    Box(
        modifier = Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup().longPressMenuGuard(),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.dialogPanel(width = 820.dp, corner = 24.dp, padding = 0.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(if (main != null) 250.dp else 140.dp).sportsBackdrop(kind, { 0.65f }, accentY = 0.55f)) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = event.title ?: (league?.name ?: event.leagueId.uppercase()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FightText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        EventStatusChip(event)
                    }
                    if (main != null) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DetailFighter(main.fighters[0], main.winner == 0, Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 120.dp)) {
                                Text(stringResource(R.string.sports_fight_vs).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = FightTextMuted)
                                Text(
                                    text = listOfNotNull(main.weightClass, main.scheduledRounds?.let { pluralStringResource(R.plurals.sports_fight_rounds, it, it) }).joinToString(sep),
                                    style = MaterialTheme.typography.labelMedium, color = FightTextMuted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                boutResult(main)?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = FightText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            DetailFighter(main.fighters[1], main.winner == 1, Modifier.weight(1f))
                        }
                    }
                    Text(
                        text = listOfNotNull(rememberStartLabel(event.startTimeMs), event.venueName).joinToString(sep),
                        style = MaterialTheme.typography.bodyMedium, color = FightTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fight != null) {
                    Text(stringResource(R.string.sports_fight_card).uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
                    for ((start, bouts) in SportsEventPresentation.boutGroups(fight)) {
                        if (start != null) {
                            Text(stringResource(R.string.sports_fight_bouts_from, time(start)), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                        for (bout in bouts) BoutRow(bout)
                    }
                }
                val broadcasts = event.broadcasts.filter { !it.type.equals("RADIO", ignoreCase = true) }
                Text(
                    text = if (broadcasts.isEmpty()) stringResource(R.string.sports_event_no_broadcasts) else broadcasts.joinToString(sep) { it.name },
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    // Same action list as Game Details: Close only while channels are off (C2 adds Watch).
                    if (SportsEventPresentation.detailActions(event).contains(SportsEventAction.CLOSE)) {
                        FocusableSurface(
                            onClick = onDismiss,
                            modifier = Modifier.widthIn(min = 160.dp).focusRequester(closeFocus),
                            shape = RoundedCornerShape(12.dp),
                            surface = GlassSurface.DIALOGS,
                            contentAlignment = Alignment.Center,
                        ) { focused ->
                            Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                OwnTVIcon(icon = OwnTVIcon.CLOSE, tint = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Text(stringResource(R.string.content_close), style = MaterialTheme.typography.labelLarge, color = if (focused) colors.onPrimaryContainer else colors.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailFighter(fighter: SportsFighter, winner: Boolean, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FighterBadge(fighter, 72.dp)
        Text(fighter.name, style = MaterialTheme.typography.titleLarge, fontWeight = if (winner) FontWeight.ExtraBold else FontWeight.SemiBold, color = FightText, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            text = listOfNotNull(fighter.record, fighter.country).joinToString(stringResource(R.string.sports_list_separator)),
            style = MaterialTheme.typography.labelMedium, color = FightTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One bout; focusable so the D-pad can scroll a long card. Winner in bold; result only when stated. */
@Composable
private fun BoutRow(bout: SportsBout) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        unfocusedContainerColor = colors.surfaceContainerHigh,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(bout.weightClass ?: "", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FighterBadge(bout.fighters[0], 24.dp)
                Text(bout.fighters[0].name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bout.winner == 0) FontWeight.Bold else FontWeight.Normal, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.sports_fight_vs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(bout.fighters[1].name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bout.winner == 1) FontWeight.Bold else FontWeight.Normal, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                FighterBadge(bout.fighters[1], 24.dp)
            }
            val status = when (bout.status) {
                SportsEventStatus.LIVE -> listOfNotNull(stringResource(R.string.sports_live_badge), bout.round?.let { r -> bout.clock?.let { stringResource(R.string.sports_fight_round_clock, r, it) } }).joinToString(" ")
                SportsEventStatus.CANCELED -> stringResource(R.string.sports_status_canceled)
                SportsEventStatus.POSTPONED -> stringResource(R.string.sports_status_postponed)
                else -> boutResult(bout) ?: ""
            }
            Text(status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(min = 110.dp), textAlign = TextAlign.End)
        }
    }
}
