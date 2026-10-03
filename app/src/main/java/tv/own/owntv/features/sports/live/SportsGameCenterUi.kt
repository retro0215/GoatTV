package tv.own.owntv.features.sports.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Game Center in the sticky Sports preview (event card focused). Pure composition over the event +
 * an optional detail: no player, no network, nothing to cancel — so it can follow the D-pad across a
 * whole row. One composition is updated in place: switching events swaps the (cached) backdrop and
 * fades/slides the content in, restarting rather than queueing on rapid D-pad; a detail arriving for
 * the SAME event updates without any animation. (A two-layer crossfade measured ~8ms/frame slower at
 * 4K on SHIELD, so it is deliberately not used.)
 */
@Composable
fun SportsGameCenterPreview(
    event: SportsEvent,
    league: SportsLeague?,
    detailState: GameCenterDetailState,
    modifier: Modifier = Modifier,
) {
    val preview = remember(event, league, detailState) { GameCenterPresentation.preview(event, league, detailState) }
    Box(modifier) { GameCenterBody(event, league, preview) }
}

@Composable
private fun GameCenterBody(event: SportsEvent, league: SportsLeague?, preview: GameCenterPreview) {
    val awayAccent = rememberTeamAccent(event.away?.logoUrl)
    val homeAccent = rememberTeamAccent(event.home?.logoUrl)
    // Focus moved to another event: the content fades/slides in over ~160ms. Restarting on every key
    // (LaunchedEffect cancels the previous run) means rapid D-pad never queues animations.
    val enter = remember { Animatable(1f) }
    LaunchedEffect(event.id) {
        enter.snapTo(0f)
        enter.animateTo(1f, tween(160))
    }
    Box(Modifier.fillMaxSize()) {
        // Backdrop in its own cached (offscreen) layer: it is re-rasterized only when the event
        // changes, never on each frame of a card's focus animation or the content fade.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .sportsBackdrop(preview.kind, { 0.55f }, awayAccent, homeAccent, accentY = 0.5f),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = enter.value
                    alpha = 0.35f + 0.65f * t
                    translationX = (1f - t) * 12.dp.toPx()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // LEFT: competition, status, matchup / fight / headline.
            Column(
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val title = if (preview.layout == GameCenterLayout.FIGHT) event.title else null
                    CompetitionChip(title ?: SportsEventPresentation.competitionLabel(league) ?: event.leagueId.uppercase())
                    GameCenterStatusChip(event, preview)
                }
                when (preview.layout) {
                    GameCenterLayout.MATCHUP -> Matchup(event, preview)
                    GameCenterLayout.FIGHT -> FightMatchup(event, preview)
                    GameCenterLayout.HEADLINE -> Text(
                        text = event.title ?: event.leagueId.uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = CardText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Live: the last play (when the backend states one) takes the note's line.
                val bottomLine = preview.lastPlay?.let { stringResource(R.string.sports_gc_last_play, it) } ?: SportsEventPresentation.note(event)
                bottomLine?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = CardTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: Spacer(Modifier.height(1.dp))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 6.dp).background(Color.White.copy(alpha = 0.14f)))
            // RIGHT: a few stats + leaders, or (nothing to compare yet) when / where / on what.
            if (preview.showsInfoPanel) {
                InfoPanel(event, preview, Modifier.weight(1f).fillMaxHeight())
            } else {
                Row(modifier = Modifier.weight(1.35f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (preview.hasStats) TeamStatsColumn(event, preview, Modifier.weight(1f).fillMaxHeight())
                    if (preview.hasLeaders) {
                        LeadersColumn(event, preview.leaders, Modifier.weight(1.15f).fillMaxHeight())
                    } else {
                        // Alone (soccer: no player leaders), the comparison keeps a readable width instead of spanning the pane.
                        Spacer(Modifier.weight(0.6f))
                    }
                }
            }
        }
    }
}

/** Live team games show just the LIVE pill up top; the period/clock sits under the score instead. */
@Composable
private fun GameCenterStatusChip(event: SportsEvent, preview: GameCenterPreview) {
    val status = SportsEventPresentation.status(event)
    if (preview.layout == GameCenterLayout.MATCHUP && status is SportsEventPresentation.Status.Live && !status.halftime && event.showsScores) {
        Pill(stringResource(R.string.sports_live_badge), LiveRed, Color.White)
    } else if (status == SportsEventPresentation.Status.Scheduled && preview.showsInfoPanel) {
        Spacer(Modifier.height(1.dp)) // the start time leads the info panel; don't repeat it
    } else {
        EventStatusChip(event)
    }
}

@Composable
private fun Matchup(event: SportsEvent, preview: GameCenterPreview) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GameCenterTeam(event.away!!, hasBall = preview.possession == GameCenterSide.AWAY, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 6.dp)) {
            if (event.showsScores) {
                val unknown = stringResource(R.string.sports_score_unknown)
                val final = preview.phase == GameCenterPhase.FINAL
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScoreText(event.away.score ?: unknown, dim = final && isTrailing(event, home = false))
                    Text(unknown, style = MaterialTheme.typography.headlineSmall, color = CardTextMuted)
                    ScoreText(event.home!!.score ?: unknown, dim = final && isTrailing(event, home = true))
                }
                if (preview.clockParts.isNotEmpty()) {
                    Text(
                        text = preview.clockParts.joinToString(stringResource(R.string.sports_list_separator)).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CardText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                preview.downDistance?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CardTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                preview.situation?.let { BaseballSituation(it) }
            } else {
                Text(
                    text = stringResource(R.string.sports_event_at).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CardTextMuted,
                )
            }
        }
        GameCenterTeam(event.home!!, hasBall = preview.possession == GameCenterSide.HOME, Modifier.weight(1f))
    }
}

@Composable
private fun ScoreText(score: String, dim: Boolean) {
    Text(
        text = score,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.ExtraBold,
        color = if (dim) CardTextMuted else CardText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun isTrailing(event: SportsEvent, home: Boolean): Boolean {
    val h = event.home?.score?.toIntOrNull() ?: return false
    val a = event.away?.score?.toIntOrNull() ?: return false
    return if (home) h < a else a < h
}

/** [hasBall]: football possession, only when the backend states it (a small dot beside the name). */
@Composable
private fun GameCenterTeam(team: SportsTeam, hasBall: Boolean, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TeamLogo(team, size = 54.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasBall) Box(Modifier.size(7.dp).clip(CircleShape).background(PossessionAmber))
            Text(
                text = team.abbreviation ?: team.shortName ?: team.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val PossessionAmber = Color(0xFFFFD54F)

/** Outs / count / bases — each part only when the detail states it (never invented). */
@Composable
private fun BaseballSituation(s: GameCenterLiveSituation) {
    val parts = listOfNotNull(
        s.outs?.let { pluralStringResource(R.plurals.sports_gc_outs, it, it) },
        if (s.hasBaseballCount) stringResource(R.string.sports_gc_count, s.balls!!, s.strikes!!) else null,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.hasBases) BasesDiamond(s, 18.dp)
        if (parts.isNotEmpty()) {
            Text(parts.joinToString(stringResource(R.string.sports_list_separator)), style = MaterialTheme.typography.labelMedium, color = CardTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BasesDiamond(s: GameCenterLiveSituation, size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension * 0.22f
        val c = this.size.minDimension / 2
        // second (top), third (left), first (right)
        val bases = listOf(Offset(c, r) to s.onSecond, Offset(r, c) to s.onThird, Offset(this.size.width - r, c) to s.onFirst)
        for ((center, occupied) in bases) {
            val p = Path().apply {
                moveTo(center.x, center.y - r); lineTo(center.x + r, center.y); lineTo(center.x, center.y + r); lineTo(center.x - r, center.y); close()
            }
            if (occupied == true) drawPath(p, Color(0xFFFFD54F), style = Fill)
            else drawPath(p, CardTextMuted, style = Stroke(width = 1.5f))
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = CardTextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun TeamStatsColumn(event: SportsEvent, preview: GameCenterPreview, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(event.away?.abbreviation.orEmpty(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SectionHeader(
                text = stringResource(R.string.sports_gc_team_stats),
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            Text(event.home?.abbreviation.orEmpty(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        for (stat in preview.teamStats) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatValue(stat.away, TextAlign.Start)
                Text(
                    text = stat.labelRes?.let { stringResource(it) } ?: stat.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = CardTextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                StatValue(stat.home, TextAlign.End)
            }
        }
        if (preview.stale) {
            Text(stringResource(R.string.sports_gc_stale), style = MaterialTheme.typography.labelSmall, color = CardTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatValue(value: String?, align: TextAlign) {
    Text(
        text = value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sports_score_unknown),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = CardText,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(min = 44.dp),
    )
}

@Composable
private fun LeadersColumn(event: SportsEvent, leaders: List<GameCenterLeader>, modifier: Modifier) {
    val sep = stringResource(R.string.sports_list_separator)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
        SectionHeader(stringResource(R.string.sports_gc_leaders))
        for (leader in leaders) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LeaderBadge(leader, 30.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = listOfNotNull(leader.label.uppercase(), GameCenterPresentation.leaderTeam(event, leader)?.abbreviation).joinToString(sep),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CardTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = leader.athleteName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = CardText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = leader.statParts.joinToString(sep),
                            style = MaterialTheme.typography.labelLarge,
                            color = CardText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Small circular headshot when supplied; otherwise initials (the preview never depends on photos). */
@Composable
private fun LeaderBadge(leader: GameCenterLeader, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.26f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!leader.headshotUrl.isNullOrBlank()) {
            AsyncImage(model = leader.headshotUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = GameCenterPresentation.leaderInitials(leader),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = CardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Upcoming / no stats yet: start time, venue and where it's on — intentional, never an empty table. */
@Composable
private fun InfoPanel(event: SportsEvent, preview: GameCenterPreview, modifier: Modifier) {
    val sep = stringResource(R.string.sports_list_separator)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        val start = rememberStartLabel(event.startTimeMs)
        when (preview.phase) {
            GameCenterPhase.UPCOMING, GameCenterPhase.INTERRUPTED -> Text(
                text = start,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GameCenterPhase.LIVE, GameCenterPhase.FINAL -> Text(
                text = start,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val bout = preview.bout
        if (bout != null) {
            val boutLine = listOfNotNull(bout.weightClass, bout.scheduledRounds?.let { pluralStringResource(R.plurals.sports_fight_rounds, it, it) })
            if (boutLine.isNotEmpty()) {
                Text(boutLine.joinToString(sep), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        event.venueName?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Detail still on its way: one quiet line, never a spinner over the pane.
        if (preview.detailPending && preview.phase != GameCenterPhase.INTERRUPTED) {
            Text(
                text = stringResource(if (preview.phase == GameCenterPhase.UPCOMING) R.string.sports_gc_loading_info else R.string.sports_gc_loading_stats),
                style = MaterialTheme.typography.labelMedium,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        event.primaryBroadcast?.let { broadcast ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.sports_gc_where_on, broadcast.name),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = CardText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Fight Center: the main event, fighter vs fighter. Round / clock / winner / method only as provided. */
@Composable
private fun FightMatchup(event: SportsEvent, preview: GameCenterPreview) {
    val bout = preview.bout
    if (bout == null || bout.fighters.size < 2) {
        Text(
            text = event.title ?: event.leagueId.uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = CardText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(R.string.sports_fight_main_event), Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GameCenterFighter(bout.fighters[0], winner = bout.winner == 0, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 96.dp).padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.sports_fight_vs).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CardTextMuted)
                val live = if (bout.status == SportsEventStatus.LIVE && bout.round != null && bout.clock != null) {
                    stringResource(R.string.sports_fight_round_clock, bout.round, bout.clock)
                } else {
                    null
                }
                (live ?: boutResult(bout))?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = CardText, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
            GameCenterFighter(bout.fighters[1], winner = bout.winner == 1, Modifier.weight(1f))
        }
        bout.winner?.let { bout.fighters.getOrNull(it) }?.let { winner ->
            Text(
                text = stringResource(R.string.sports_gc_winner, winner.name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CardText,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        } ?: Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun GameCenterFighter(fighter: SportsFighter, winner: Boolean, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        FighterBadge(fighter, 44.dp)
        Text(
            text = fighter.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (winner) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = CardText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        fighter.record?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = CardTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
