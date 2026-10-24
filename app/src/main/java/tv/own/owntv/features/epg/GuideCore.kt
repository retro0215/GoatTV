package tv.own.owntv.features.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.PopupFontTheme
import tv.own.owntv.ui.theme.glass

internal object GuideGridDefaults {
    // Wide enough for the logo, the number and a readable channel name (Phase 3 cut most names short).
    val ChannelCol = 236.dp
    val RowHeight = 64.dp
    val RowGap = 4.dp
    val PxPerMin = 4.dp
    const val SlotMin = 30
}

/**
 * Draws a whole guide row — the frozen channel cell and the programme strip — on ONE Canvas, so a row is
 * a single composable. Phase 3C measured ~27 ms to compose a row on the onn (two Text composables, an
 * AsyncImage, a data-loading coroutine and a flow collector per row); everything here is plain drawing.
 *
 * One renderer serves every row: the styles, px sizes, glyphs and the bounded time-label cache are built
 * once per screen, and text is measured through the guide's shared [measurer] (repeated titles and the
 * width-snapped clipped titles hit its cache). Nothing is allocated per programme beyond the label the
 * first time a cell is drawn.
 */
internal class GuideRowRenderer(
    density: androidx.compose.ui.unit.Density,
    private val colors: tv.own.owntv.ui.theme.OwnTVColors,
    typography: androidx.tv.material3.Typography,
    private val measurer: androidx.compose.ui.text.TextMeasurer,
    private val formatTime: (Long) -> String,
    private val timeRangeTemplate: String,
    private val nowTemplate: String,
    focusBorder: androidx.compose.ui.unit.Dp,
) {
    private val pxPerMin = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    private val channelColPx = with(density) { GuideGridDefaults.ChannelCol.toPx() }
    private val channelGapPx = with(density) { 6.dp.toPx() }
    private val cellPadPx = with(density) { 10.dp.toPx() }
    private val logoW = with(density) { 48.dp.toPx() }
    private val logoH = with(density) { 36.dp.toPx() }
    private val logoInsetPx = with(density) { 3.dp.toPx() }
    private val dotPx = with(density) { 7.dp.toPx() }
    private val gapPx = with(density) { 4.dp.toPx() }
    private val padPx = with(density) { 10.dp.toPx() }
    private val iconGapPx = with(density) { 6.dp.toPx() }
    private val minTextPx = with(density) { 28.dp.toPx() }
    private val borderPx = with(density) { focusBorder.toPx() }
    private val airingBarPx = with(density) { 3.dp.toPx() }
    private val airingBarInsetPx = with(density) { 4.dp.toPx() }
    private val corner = with(density) { CornerRadius(10.dp.toPx(), 10.dp.toPx()) }
    private val logoCorner = with(density) { CornerRadius(8.dp.toPx(), 8.dp.toPx()) }
    private val nowLinePx = with(density) { 2.dp.toPx() }
    private val nowColor = Color(0xFFFF5C5C)

    private val title = typography.titleSmall.copy(color = colors.onSurface, textDirection = TextDirection.Content)
    private val time = typography.labelSmall.copy(color = colors.onSurfaceVariant, textDirection = TextDirection.Content)
    // The focused programme sits on a filled primary container, so its text switches to the on-colour.
    private val titleFocused = title.copy(color = colors.onPrimaryContainer)
    private val timeFocused = time.copy(color = colors.onPrimaryContainer.copy(alpha = 0.8f))
    private val badge = typography.labelMedium.copy(color = colors.primary, textDirection = TextDirection.Content)
    private val bell = typography.titleSmall.copy(textDirection = TextDirection.Content)
    private val number = typography.labelMedium.copy(color = colors.primary, fontWeight = FontWeight.Bold)
    private val name = typography.titleSmall.copy(color = colors.onSurface, textDirection = TextDirection.Content)
    private val initials = typography.labelSmall.copy(color = colors.primary, fontWeight = FontWeight.Bold)

    private val catchupGlyph = measurer.measure("↻", badge)
    private val reminderGlyph = measurer.measure("🔔", bell)

    /** Time labels of drawn cells, keyed by programme and on-air state. Bounded (a catch-up guide holds days). */
    private val labels = BoundedLru<Long, String>(LABEL_CACHE_SIZE)

    private fun label(p: EpgProgrammeEntity, isNow: Boolean): String = labels.getOrPut(p.id * 2 + if (isNow) 1 else 0) {
        val t = String.format(java.util.Locale.ROOT, timeRangeTemplate, formatTime(p.startMs), formatTime(p.stopMs))
        if (isNow) String.format(java.util.Locale.ROOT, nowTemplate, t) else t
    }

    fun DrawScope.drawRow(
        channel: ChannelEntity,
        numberText: String?,
        categoryDot: Color?,
        logo: ImageBitmap?,
        data: GuideRowData?,
        highlight: Long?,
        now: Long,
        scrollPx: Float,
        windowStart: Long,
        windowEnd: Long,
        reminderSlots: Set<tv.own.owntv.core.sync.work.ProgramReminders.Key>,
    ) {
        android.os.Trace.beginSection("GuideRow.draw")
        drawChannelCell(channel, numberText, categoryDot, logo, focused = highlight != null)
        val stripX = channelColPx
        val stripW = size.width - stripX
        if (data != null && stripW > 0f) {
            clipRect(left = stripX, top = 0f, right = size.width, bottom = size.height) {
                translate(left = stripX) {
                    drawStrip(channel.id, data, highlight, now, scrollPx, windowStart, windowEnd, stripW, reminderSlots)
                }
            }
        }
        android.os.Trace.endSection()
    }

    private fun DrawScope.drawChannelCell(channel: ChannelEntity, numberText: String?, dot: Color?, logo: ImageBitmap?, focused: Boolean) {
        val w = channelColPx - channelGapPx
        val h = size.height
        drawRoundRect(color = if (focused) colors.primaryContainer else colors.surfaceContainerHigh, size = Size(w, h), cornerRadius = corner)
        if (focused) {
            drawRoundRect(
                color = colors.focusBorder,
                topLeft = Offset(borderPx / 2f, borderPx / 2f),
                size = Size(w - borderPx, h - borderPx),
                cornerRadius = corner,
                style = Stroke(borderPx),
            )
        }
        // Logo box (or the name's initials).
        val lx = cellPadPx
        val ly = (h - logoH) / 2f
        drawRoundRect(color = colors.surfaceContainerLowest, topLeft = Offset(lx, ly), size = Size(logoW, logoH), cornerRadius = logoCorner)
        if (logo != null) {
            val boxW = logoW - logoInsetPx * 2f
            val boxH = logoH - logoInsetPx * 2f
            val scale = minOf(boxW / logo.width, boxH / logo.height)
            val dw = (logo.width * scale).toInt().coerceAtLeast(1)
            val dh = (logo.height * scale).toInt().coerceAtLeast(1)
            drawImage(
                image = logo,
                dstOffset = IntOffset((lx + (logoW - dw) / 2f).toInt(), (ly + (logoH - dh) / 2f).toInt()),
                dstSize = IntSize(dw, dh),
            )
        } else {
            val text = measurer.measure(channel.name.take(3).uppercase(), initials, maxLines = 1, constraints = Constraints(maxWidth = logoW.toInt()))
            drawText(text, topLeft = Offset(lx + (logoW - text.size.width) / 2f, ly + (logoH - text.size.height) / 2f))
        }
        // Number (with the category dot) over the name.
        val tx = lx + logoW + cellPadPx
        val maxW = (w - tx - cellPadPx).toInt().coerceAtLeast(1)
        val num = numberText?.let { measurer.measure(it, number, maxLines = 1, constraints = Constraints(maxWidth = maxW)) }
        val nm = measurer.measure(channel.name, name, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
        val blockH = (num?.size?.height ?: 0) + nm.size.height
        var y = (h - blockH) / 2f
        if (num != null) {
            var nx = tx
            if (dot != null) {
                drawCircle(color = dot, radius = dotPx / 2f, center = Offset(tx + dotPx / 2f, y + num.size.height / 2f))
                nx += dotPx + iconGapPx
            }
            drawText(num, topLeft = Offset(nx, y))
            y += num.size.height
        }
        drawText(nm, topLeft = Offset(tx, y))
    }

    private fun DrawScope.drawStrip(
        channelId: Long,
        data: GuideRowData,
        highlight: Long?,
        now: Long,
        scrollPx: Float,
        windowStart: Long,
        windowEnd: Long,
        viewW: Float,
        reminderSlots: Set<tv.own.owntv.core.sync.work.ProgramReminders.Key>,
    ) {
        val h = size.height
        for (p in data.programmes) {
            val s = p.startMs.coerceIn(windowStart, windowEnd)
            val e = p.stopMs.coerceIn(windowStart, windowEnd)
            if (e <= s) continue
            val x = ((s - windowStart) / 60_000f) * pxPerMin - scrollPx
            val w = (((e - s) / 60_000f) * pxPerMin - gapPx).coerceAtLeast(0f)
            if (x + w <= 0f || x >= viewW) continue // cull off-screen programmes
            val isNow = now in p.startMs until p.stopMs
            val hi = highlight != null && highlight in p.startMs until p.stopMs
            val bg = when {
                hi -> colors.primaryContainer
                isNow -> colors.primaryContainer.copy(alpha = 0.18f)
                p.stopMs <= now -> colors.surfaceContainerHigh.copy(alpha = 0.5f) // aired: recedes behind Now/Next
                else -> colors.surfaceContainerHigh
            }
            drawRoundRect(color = bg, topLeft = Offset(x, 0f), size = Size(w, h), cornerRadius = corner)
            if (isNow && x >= 0f) {
                drawRoundRect(
                    color = colors.primary,
                    topLeft = Offset(x + airingBarInsetPx, airingBarInsetPx),
                    size = Size(airingBarPx, (h - airingBarInsetPx * 2f).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(airingBarPx / 2f, airingBarPx / 2f),
                )
            }
            if (hi) drawRoundRect(color = colors.focusBorder, topLeft = Offset(x, 0f), size = Size(w, h), cornerRadius = corner, style = Stroke(borderPx))

            val hasBell = tv.own.owntv.core.sync.work.ProgramReminders.Key(channelId, p.startMs) in reminderSlots
            val hasBadge = p.id in data.catchupIds
            val cell = GuideCellLayout.layout(
                cellX = x, cellW = w, viewW = viewW, pad = padPx, gap = iconGapPx,
                bellW = if (hasBell) reminderGlyph.size.width.toFloat() else 0f,
                badgeW = if (hasBadge) catchupGlyph.size.width.toFloat() else 0f,
                minText = minTextPx,
            )
            var titleBottom = h / 2f
            if (cell.textWidth > 0f) {
                val maxW = cell.textWidth.toInt()
                val t = measurer.measure(p.title, if (hi) titleFocused else title, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
                val m = measurer.measure(label(p, isNow), if (hi) timeFocused else time, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
                val top = (h - (t.size.height + m.size.height + 2)) / 2f
                drawText(t, topLeft = Offset(cell.textX, top))
                drawText(m, topLeft = Offset(cell.textX, top + t.size.height + 2))
                titleBottom = top + t.size.height
            }
            cell.bellX?.let { bx ->
                // Centred on the title line, so the bell reads as part of the title.
                val by = if (cell.textWidth > 0f) titleBottom - reminderGlyph.size.height else (h - reminderGlyph.size.height) / 2f
                drawText(reminderGlyph, topLeft = Offset(bx, by.coerceAtLeast(0f)))
            }
            cell.badgeX?.let { bx -> drawText(catchupGlyph, topLeft = Offset(bx, 3f)) }
        }
        // Vertical "now" marker — drawn on every row so it reads as one continuous line down the grid.
        if (now in windowStart..windowEnd) {
            val nowX = ((now - windowStart) / 60_000f) * pxPerMin - scrollPx
            if (nowX in 0f..viewW) {
                drawLine(color = nowColor, start = Offset(nowX, 0f), end = Offset(nowX, h), strokeWidth = nowLinePx)
            }
        }
    }

    companion object {
        /** About a dozen screens of cells; labels are short strings. */
        const val LABEL_CACHE_SIZE = 2048
    }
}

/** The screen's one row renderer, rebuilt only when the theme, density, locale or time format changes. */
@Composable
internal fun rememberGuideRowRenderer(measurer: androidx.compose.ui.text.TextMeasurer): GuideRowRenderer {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val colors = OwnTVTheme.colors
    val typography = MaterialTheme.typography
    val formatTime = rememberSystemTimeFormatter()
    val timeRangeTemplate = stringResource(R.string.content_epg_time_range)
    val nowTemplate = stringResource(R.string.content_epg_now)
    val focusBorder = tv.own.owntv.ui.theme.LocalFocusBorderWidth.current
    return remember(density, colors, typography, measurer, formatTime, timeRangeTemplate, nowTemplate, focusBorder) {
        GuideRowRenderer(density, colors, typography, measurer, formatTime, timeRangeTemplate, nowTemplate, focusBorder)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProgrammeDetailDialog(
    channelName: String,
    programme: EpgProgrammeEntity,
    loadDescription: suspend (Long) -> String?,
    canCatchup: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onWatch: () -> Unit,
    onPlayCatchup: () -> Unit,
    onDismiss: () -> Unit,
    // Where "Watch from start" sends the archive. ASK shows a chooser popup on top of this dialog;
    // INTERNAL/EXTERNAL go straight there. Defaulted so non-catch-up callers can ignore it.
    catchupPlayer: SettingsRepository.CatchupPlayer = SettingsRepository.CatchupPlayer.INTERNAL,
    onPlayCatchupExternal: () -> Unit = {},
    // Denser variant for the Live TV catch-up picker, which opens this on top of an already-small
    // popup chain — full-size chrome dwarfed the picker it came from. Guide keeps the roomy layout.
    compact: Boolean = false,
    // Programme reminder (shared ProgramReminders action). Null hides the button — e.g. a programme
    // that already ended. [reminderSet] flips the label; [reminderMessage] is the last action's result.
    onToggleReminder: (() -> Unit)? = null,
    reminderSet: Boolean = false,
    reminderMessage: String? = null,
    // Which action takes focus when the dialog opens (the Guide passes GuideNav.detailFocus): Watch for
    // a programme on air, the reminder for an upcoming one, Watch from start for an archived one.
    initialFocus: GuideNav.DetailFocus = GuideNav.DetailFocus.CATCHUP,
) {
    val colors = OwnTVTheme.colors
    val formatTime = rememberSystemTimeFormatter()
    // The grid load drops `description` to stay under the CursorWindow limit, so fetch it on demand
    // here (fall back to the row's own value when it was loaded by the lazy per-row path).
    val description by produceState(programme.description, programme.id) {
        value = programme.description ?: loadDescription(programme.id)
    }
    val fr = remember { FocusRequester() } // first action: Watch from start, else Watch channel
    val watchFr = remember { FocusRequester() }
    val reminderFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val target = when {
            initialFocus == GuideNav.DetailFocus.REMINDER && onToggleReminder != null -> reminderFr
            initialFocus == GuideNav.DetailFocus.WATCH && canCatchup -> watchFr
            else -> fr
        }
        if (runCatching { target.requestFocus() }.isFailure) runCatching { fr.requestFocus() }
    }
    // "Always ask" → a second, small popup asking which player takes the archive.
    var showPlayerChooser by remember { mutableStateOf(false) }
    if (showPlayerChooser) {
        CatchupPlayerChooser(
            onInternal = { showPlayerChooser = false; onPlayCatchup() },
            onExternal = { showPlayerChooser = false; onPlayCatchupExternal() },
            onDismiss = { showPlayerChooser = false },
        )
    }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        BackHandler { onDismiss() }
      CompactPopupFont(compact) {
        Box(
            // The dialog can be opened by a long-press on the programme cell; the OK key is often still
            // held when it appears, which would instantly fire the focused action. Swallow OK until it's
            // released once so the held long-press only reveals the dialog, then the user chooses.
            Modifier.fillMaxSize().modalScrim().longPressMenuGuard(),
            contentAlignment = Alignment.Center,
        ) {
            // Scrollable: long XMLTV descriptions can exceed a small screen's height. widthIn (not a
            // fixed width) keeps it responsive on narrow screens, so this uses .glass() directly rather
            // than dialogPanel (which sets a fixed width) — same DIALOGS surface + fill hook.
            val corner = if (compact) 16.dp else 20.dp
            Column(
                Modifier.widthIn(max = if (compact) 400.dp else 560.dp).clip(RoundedCornerShape(corner))
                    .glass(surface = GlassSurface.DIALOGS, baseFill = colors.surfaceContainerHigh, shape = RoundedCornerShape(corner))
                    .verticalScroll(rememberScrollState()).padding(if (compact) 18.dp else 28.dp),
            ) {
                Text(channelName.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(programme.title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, color = colors.onSurface)
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                Text(stringResource(R.string.content_epg_time_range, formatTime(programme.startMs), formatTime(programme.stopMs)), style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                    Text(description.orEmpty(), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
                // FlowRow so the actions wrap to a second line on narrower screens instead of the last
                // button being clipped off the dialog edge (4 buttons don't fit one row when catch-up adds
                // "Watch from start" + "Watch channel").
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    // Catch-up channels: replay this programme from its start (seekable archive playback).
                    if (canCatchup) {
                        val startCatchup = {
                            when (catchupPlayer) {
                                SettingsRepository.CatchupPlayer.ASK -> showPlayerChooser = true
                                SettingsRepository.CatchupPlayer.INTERNAL -> onPlayCatchup()
                                SettingsRepository.CatchupPlayer.EXTERNAL -> onPlayCatchupExternal()
                            }
                        }
                        // All actions share one style so the focused one is the only lit button.
                        OwnTVButton(stringResource(R.string.content_epg_watch_start), onClick = startCatchup, icon = OwnTVIcon.PLAY, style = OwnTVButtonStyle.SECONDARY, compact = compact, modifier = Modifier.focusRequester(fr))
                        OwnTVButton(stringResource(R.string.content_epg_watch_channel), onClick = onWatch, style = OwnTVButtonStyle.SECONDARY, compact = compact, modifier = Modifier.focusRequester(watchFr))
                    } else {
                        OwnTVButton(stringResource(R.string.content_epg_watch_channel), onClick = onWatch, icon = OwnTVIcon.PLAY, style = OwnTVButtonStyle.SECONDARY, compact = compact, modifier = Modifier.focusRequester(fr))
                    }
                    if (onToggleReminder != null) {
                        OwnTVButton(
                            stringResource(if (reminderSet) R.string.content_reminder_remove else R.string.content_reminder_set),
                            onClick = onToggleReminder,
                            style = OwnTVButtonStyle.SECONDARY,
                            compact = compact,
                            modifier = Modifier.focusRequester(reminderFr),
                        )
                    }
                    // Favourite the channel without leaving the guide; the label flips in place.
                    OwnTVButton(
                        stringResource(if (isFavorite) R.string.content_epg_unfavourite else R.string.content_epg_favourite),
                        onClick = onToggleFavorite,
                        style = OwnTVButtonStyle.SECONDARY,
                        icon = OwnTVIcon.FAVORITE,
                        compact = compact,
                    )
                    OwnTVButton(stringResource(R.string.settings_close), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, compact = compact)
                }
                if (reminderMessage != null) {
                    Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                    Text(reminderMessage, style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
            }
        }
      }
    }
}

/** The "Always ask" chooser: which player takes this catch-up archive. Deliberately tiny — it sits on
 *  top of the programme dialog, so it only asks the one question and gets out of the way. */
@Composable
private fun CatchupPlayerChooser(
    onInternal: () -> Unit,
    onExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss) {
        BackHandler { onDismiss() }
        PopupFontTheme(fontScale = 0.7f) {
            Box(
                Modifier.fillMaxSize().modalScrim()
                    .trapAllFocusExit().focusGroup(),
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.dialogPanel(width = 340.dp, corner = 16.dp, padding = 18.dp, scroll = false)) {
                    Text(stringResource(R.string.settings_catchup_player), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.content_epg_player_choice_description),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    OwnTVButton(stringResource(R.string.content_epg_own_player, stringResource(R.string.app_name)), onClick = onInternal, icon = OwnTVIcon.PLAY, compact = true, modifier = Modifier.fillMaxWidth().focusRequester(fr))
                    Spacer(Modifier.height(8.dp))
                    OwnTVButton(stringResource(R.string.content_epg_external_player), onClick = onExternal, style = OwnTVButtonStyle.SECONDARY, compact = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, compact = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Applies the shared popup type ramp at a reduced scale only when [compact]; otherwise leaves the
 *  caller's typography untouched, so the Guide's own dialog keeps its existing look. */
@Composable
private fun CompactPopupFont(compact: Boolean, content: @Composable () -> Unit) {
    if (compact) PopupFontTheme(fontScale = 0.7f, content = content) else content()
}
