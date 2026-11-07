package tv.own.owntv.features.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.diagnostics.CodecSupport
import tv.own.owntv.core.diagnostics.DiagCheck
import tv.own.owntv.core.diagnostics.DiagCodec
import tv.own.owntv.core.diagnostics.DiagStatus
import tv.own.owntv.core.diagnostics.DiagnosticEvaluator
import tv.own.owntv.core.diagnostics.DiagnosticFacts
import tv.own.owntv.core.diagnostics.Recommendation
import tv.own.owntv.core.diagnostics.ServiceFact
import tv.own.owntv.core.diagnostics.SpeedRating
import tv.own.owntv.core.diagnostics.SpeedTestState
import tv.own.owntv.core.diagnostics.SportsFact
import tv.own.owntv.core.diagnostics.VodEngine
import tv.own.owntv.features.update.UpdateDialog
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.dialogPanel
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

private val WarningAmber = Color(0xFFF59E0B)
private val FailedRed = Color(0xFFEF4444)

/**
 * Settings → Diagnostic Center (Phase 1, local diagnostics only).
 *
 * TV focus: the Run button takes focus on entry; every status card is focusable so the D-pad scrolls
 * through results; progress updates never request focus; dialogs hand focus back to the button that
 * opened them. Leaving the screen (Back or the header arrow) cancels a running diagnostic.
 */
@Composable
fun DiagnosticCenterScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: DiagnosticCenterViewModel = koinViewModel()
    val context = LocalContext.current
    val colors = OwnTVTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val engine by vm.vodEngine.collectAsStateWithLifecycle()
    val epgSync by vm.epgSync.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val speedAtMs by vm.speedAtMs.collectAsStateWithLifecycle()
    val text = remember(context) { ResourceDiagnosticText(context) }

    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var showVpn by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var dialogReturn by remember { mutableStateOf<FocusRequester?>(null) }

    val runFocus = remember { FocusRequester() }
    val switchFocus = remember { FocusRequester() }
    val vpnFocus = remember { FocusRequester() }
    val updateFocus = remember { FocusRequester() }
    val resyncPlaylistFocus = remember { FocusRequester() }
    val resyncEpgFocus = remember { FocusRequester() }
    // Explicit up/down links between each status card and the quick-fix button under it. Compose's
    // default 2D search weights horizontal offset heavily, so from a full-width card D-pad Down would
    // jump to the next full-width card and skip the narrow, left-aligned button in between.
    val cardFocus = remember { DiagCheck.entries.associateWith { FocusRequester() } }
    val actionFocus = mapOf(
        DiagCheck.PLAYLIST to resyncPlaylistFocus,
        DiagCheck.EPG to resyncEpgFocus,
        DiagCheck.PLAYER to switchFocus,
        DiagCheck.APP_VERSION to updateFocus,
    )

    LaunchedEffect(Unit) { runCatching { runFocus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { vm.cancel(); vm.clearNotice() } }
    BackHandler { onBack() }

    // Confirmations as a toast: visible wherever the user has scrolled, and never takes focus.
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        val message = noticeMessage(context, n, engine)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        vm.clearNotice()
    }

    // Restore focus to whatever opened a dialog once it closes, the way Settings does: when the scrim
    // is torn down Compose re-searches focus through the scrollable column (resetting its scroll), so
    // wait a frame, snap the scroll back to where the user was, then focus the opener. While a return
    // target is pending, the column's onEnter routes there too instead of to the Run button.
    val scrollState = rememberScrollState()
    var savedScroll by remember { mutableStateOf(0) }
    val openDialog: (FocusRequester, () -> Unit) -> Unit = { opener, show ->
        savedScroll = scrollState.value
        dialogReturn = opener
        show()
    }
    val anyDialog = showSwitch || showVpn || showUpdate
    LaunchedEffect(anyDialog) {
        if (!anyDialog) dialogReturn?.let { target ->
            withFrameNanos { }
            runCatching { scrollState.scrollTo(savedScroll) }
            kotlinx.coroutines.delay(80)
            runCatching { target.requestFocus() }
            dialogReturn = null
        }
    }

    val facts = state.facts
    val statusOf = state.results.associate { it.check to it.status }
    val running = state.running
    val current = state.results.firstOrNull { it.status == DiagStatus.CHECKING }?.check
    val brand = stringResource(R.string.app_name)

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            .focusProperties { onEnter = { runCatching { (dialogReturn ?: runFocus).requestFocus() } } }
            .focusGroup()
            .verticalScroll(scrollState)
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Header(stringResource(R.string.diag_title), onBack)
        Text(stringResource(R.string.diag_subtitle), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OwnTVButton(
                label = stringResource(if (running) R.string.diag_stop else R.string.diag_run),
                onClick = { if (running) vm.cancel() else vm.runFull() },
                style = if (running) OwnTVButtonStyle.SECONDARY else OwnTVButtonStyle.PRIMARY,
                modifier = Modifier.focusRequester(runFocus),
            )
            OwnTVButton(
                label = stringResource(R.string.diag_copy_report),
                onClick = { copyReport(context, vm.buildReport(text)); vm.notify(DiagnosticCenterViewModel.Notice.REPORT_COPIED) },
                style = OwnTVButtonStyle.SECONDARY,
                enabled = !running,
            )
            OwnTVButton(
                label = stringResource(R.string.diag_share_report),
                onClick = { if (!shareReport(context, vm.buildReport(text))) vm.notify(DiagnosticCenterViewModel.Notice.SHARE_UNAVAILABLE) },
                style = OwnTVButtonStyle.SECONDARY,
                enabled = !running,
            )
        }
        Spacer(Modifier.height(4.dp))
        when {
            running && current != null -> Text(
                stringResource(R.string.diag_checking, stringResource(ResourceDiagnosticText.checkRes(current))),
                style = MaterialTheme.typography.titleMedium, color = colors.primary,
            )
            state.summary != null -> {
                val issues = state.summary?.issues ?: 0
                Text(
                    if (issues == 0) stringResource(R.string.diag_all_passed) else pluralStringResource(R.plurals.diag_issues_found, issues, issues),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (issues == 0) colors.primary else WarningAmber,
                )
            }
            else -> Text(stringResource(R.string.diag_not_run), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }

        if (state.recommendations.isNotEmpty() && !running) {
            GroupLabel(stringResource(R.string.diag_recommendations))
            InfoCard(lines = state.recommendations.map { stringResource(ResourceDiagnosticText.recommendationRes(it)) }, focusable = false)
            if (Recommendation.SERVICE_UNREACHABLE_VPN in state.recommendations) {
                OwnTVButton(
                    stringResource(R.string.diag_vpn_help),
                    onClick = { openDialog(vpnFocus) { showVpn = true } },
                    style = OwnTVButtonStyle.SECONDARY,
                    modifier = Modifier.focusRequester(vpnFocus),
                )
            }
        }

        GroupLabel(stringResource(R.string.diag_title))
        DiagCheck.entries.forEachIndexed { index, check ->
            val previous = DiagCheck.entries.getOrNull(index - 1)
            val next = DiagCheck.entries.getOrNull(index + 1)
            val action = actionFocus[check]
            // Card ↔ its own button, and the next card's Up returns to that button.
            val actionModifier = action?.let { fr ->
                Modifier.focusRequester(fr).focusProperties {
                    up = cardFocus.getValue(check)
                    if (next != null) down = cardFocus.getValue(next)
                }
            } ?: Modifier
            StatusCard(
                title = stringResource(ResourceDiagnosticText.checkRes(check)),
                status = statusOf[check] ?: DiagStatus.NOT_CHECKED,
                lines = detailLines(check, statusOf[check] ?: DiagStatus.NOT_CHECKED, facts, engine, text),
                modifier = Modifier.focusRequester(cardFocus.getValue(check)).focusProperties {
                    if (action != null) down = action
                    previous?.let { actionFocus[it] }?.let { up = it }
                },
            )
            when (check) {
                DiagCheck.PLAYLIST -> ActionRow { OwnTVButton(stringResource(R.string.diag_resync_playlist), onClick = vm::resyncPlaylist, style = OwnTVButtonStyle.SECONDARY, modifier = actionModifier) }
                DiagCheck.EPG -> ActionRow {
                    // Stays focusable while a sync runs (focus never jumps); pressing it then only explains.
                    val label = when {
                        epgSync == null -> stringResource(R.string.diag_resync_epg)
                        (epgSync?.programmes ?: 0) > 0 ->
                            stringResource(R.string.diag_epg_sync_in_progress_count, text.count(epgSync?.programmes ?: 0))
                        else -> stringResource(R.string.diag_epg_sync_in_progress)
                    }
                    OwnTVButton(label, onClick = vm::resyncEpg, style = OwnTVButtonStyle.SECONDARY, modifier = actionModifier)
                }
                DiagCheck.VOD -> Text(
                    stringResource(R.string.diag_vod_tip),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
                DiagCheck.PLAYER -> ActionRow {
                    OwnTVButton(
                        stringResource(R.string.diag_switch_player_to, stringResource(ResourceDiagnosticText.engineRes(DiagnosticEvaluator.switchTarget(engine)))),
                        onClick = { openDialog(switchFocus) { showSwitch = true } },
                        style = OwnTVButtonStyle.SECONDARY,
                        modifier = actionModifier,
                    )
                }
                DiagCheck.APP_VERSION -> ActionRow {
                    OwnTVButton(
                        stringResource(R.string.diag_check_update),
                        onClick = { openDialog(updateFocus) { showUpdate = true } },
                        style = OwnTVButtonStyle.SECONDARY,
                        modifier = actionModifier,
                    )
                }
                else -> Unit
            }
        }

        GroupLabel(stringResource(R.string.diag_speed_title))
        SpeedTestPanel(speed, text)
        ActionRow {
            val speedRunning = speed is SpeedTestState.Latency || speed is SpeedTestState.Downloading
            OwnTVButton(
                stringResource(
                    when {
                        speedRunning -> R.string.common_cancel
                        speed is SpeedTestState.Done || speed is SpeedTestState.Failed -> R.string.diag_speed_run_again
                        else -> R.string.diag_speed_run
                    },
                ),
                // One button that changes its label, so focus stays put while the test runs.
                onClick = { if (speedRunning) vm.cancelSpeedTest() else vm.runSpeedTest() },
                style = if (speedRunning) OwnTVButtonStyle.SECONDARY else OwnTVButtonStyle.PRIMARY,
            )
        }

        GroupLabel(stringResource(R.string.diag_actions))
        InfoCard(lines = listOf(stringResource(R.string.diag_clear_cache_description)), focusable = false)
        ActionRow { OwnTVButton(stringResource(R.string.diag_clear_cache), onClick = vm::clearImageCache, style = OwnTVButtonStyle.SECONDARY) }
        InfoCard(lines = listOf(stringResource(R.string.diag_open_app_settings_description)), focusable = false)
        ActionRow { OwnTVButton(stringResource(R.string.diag_open_app_settings), onClick = { openAppSettings(context) }, style = OwnTVButtonStyle.SECONDARY) }
        InfoCard(title = stringResource(R.string.diag_restart_title), lines = listOf(stringResource(R.string.diag_restart_description, brand)), focusable = false)

        Spacer(Modifier.height(4.dp))
        ActionRow {
            OwnTVButton(
                stringResource(if (showAdvanced) R.string.diag_advanced_hide else R.string.diag_advanced),
                onClick = { showAdvanced = !showAdvanced },
                style = OwnTVButtonStyle.SECONDARY,
            )
        }
        if (showAdvanced) AdvancedDetails(facts, text, speedAtMs)
    }

    if (showSwitch) {
        val target = DiagnosticEvaluator.switchTarget(engine)
        SwitchPlayerDialog(
            target = stringResource(ResourceDiagnosticText.engineRes(target)),
            current = stringResource(ResourceDiagnosticText.engineRes(engine)),
            onConfirm = { showSwitch = false; vm.switchVodPlayer() },
            onDismiss = { showSwitch = false },
        )
    }
    if (showVpn) VpnHelpDialog(onDismiss = { showVpn = false })
    if (showUpdate) UpdateDialog(onDismiss = { showUpdate = false }, checkOnOpen = true)
}

/** Per-check detail lines. Wording only ever claims what the check verified. */
@Composable
private fun detailLines(check: DiagCheck, status: DiagStatus, f: DiagnosticFacts, engine: VodEngine, text: ResourceDiagnosticText): List<String> {
    if (status == DiagStatus.CHECKING) return emptyList()
    return when (check) {
        DiagCheck.INTERNET -> f.internet?.let {
            listOf(
                stringResource(if (it.reachable) R.string.diag_internet_ok else R.string.diag_internet_failed),
                stringResource(R.string.diag_network_type, stringResource(ResourceDiagnosticText.transportRes(it.transport))),
            ) + listOfNotNull(it.latencyMs?.let { ms -> stringResource(R.string.diag_latency_line, text.latency(ms)) })
        }.orEmpty()
        DiagCheck.SERVICE -> when (f.service) {
            null -> if (status == DiagStatus.FAILED) listOf(stringResource(R.string.diag_service_failed)) else emptyList()
            ServiceFact.NoSource -> listOf(stringResource(R.string.diag_service_no_source))
            is ServiceFact.Ok -> listOf(stringResource(R.string.diag_service_ok))
            is ServiceFact.Rejected -> listOf(stringResource(R.string.diag_service_rejected))
            is ServiceFact.Unreachable -> listOf(stringResource(R.string.diag_service_failed))
        }
        DiagCheck.PLAYLIST -> f.playlist?.let { p ->
            buildList {
                add(stringResource(R.string.diag_playlist_loaded, stringResource(if (p.hasSource && p.total > 0) R.string.diag_yes else R.string.diag_no)))
                if (p.hasSource) {
                    add(stringResource(R.string.diag_count_channels, text.count(p.channels)))
                    add(stringResource(R.string.diag_count_movies, text.count(p.movies)))
                    add(stringResource(R.string.diag_count_series, text.count(p.series)))
                    add(p.lastSyncAtMs?.let { stringResource(R.string.diag_last_refresh, text.timestamp(it)) } ?: stringResource(R.string.diag_last_refresh_unknown))
                    if (p.total == 0) add(stringResource(R.string.diag_playlist_empty))
                }
            }
        }.orEmpty()
        DiagCheck.EPG -> f.epg?.let { e ->
            buildList {
                add(stringResource(R.string.diag_epg_available, stringResource(if (e.programmes > 0) R.string.diag_yes else R.string.diag_no)))
                if (e.sourceCount == 0) add(stringResource(R.string.diag_epg_none))
                else {
                    add(stringResource(R.string.diag_epg_programmes, text.count(e.programmes)))
                    add(e.lastSyncAtMs?.let { stringResource(R.string.diag_last_refresh, text.timestamp(it)) } ?: stringResource(R.string.diag_last_refresh_unknown))
                    if (e.lastSyncFailed) add(stringResource(R.string.diag_epg_failed))
                    // Tells "refresh is failing" apart from "refresh was turned off" for a stale guide.
                    if (e.autoRefreshOff) add(stringResource(R.string.diag_report_line, stringResource(R.string.settings_epg_sources_auto_refresh_title), stringResource(R.string.settings_sources_refresh_off)))
                }
            }
        }.orEmpty()
        DiagCheck.LIVE_TV -> when (status) {
            DiagStatus.PASS -> listOf(stringResource(R.string.diag_live_ok))
            DiagStatus.WARNING -> listOf(stringResource(R.string.diag_live_warning))
            DiagStatus.FAILED -> listOf(stringResource(R.string.diag_live_failed))
            else -> if (f.playlist?.hasSource == true) listOf(stringResource(R.string.diag_live_na)) else emptyList()
        }
        DiagCheck.VOD -> f.playlist?.takeIf { it.hasSource }?.let { p ->
            when (status) {
                DiagStatus.PASS -> listOf(
                    stringResource(R.string.diag_count_movies, text.count(p.movies)),
                    stringResource(R.string.diag_count_series, text.count(p.series)),
                    stringResource(R.string.diag_vod_ok),
                )
                DiagStatus.WARNING -> listOf(stringResource(R.string.diag_vod_warning))
                else -> listOf(stringResource(R.string.diag_vod_na))
            }
        }.orEmpty()
        DiagCheck.SPORTS -> when (val s = f.sports) {
            null -> emptyList()
            SportsFact.Unavailable -> listOf(stringResource(R.string.diag_sports_unavailable))
            is SportsFact.Connected -> buildList {
                add(stringResource(R.string.diag_sports_connected))
                add(stringResource(R.string.diag_sports_events, text.count(s.events)))
                if (s.events == 0) add(stringResource(R.string.diag_sports_no_events))
                s.gameCenterOk?.let { add(stringResource(if (it) R.string.diag_sports_gc_ok else R.string.diag_sports_gc_failed)) }
            }
        }
        // The current player is shown even before a run: it is a local setting, not a test result.
        DiagCheck.PLAYER -> listOf(stringResource(R.string.diag_player_current, text.engine(engine, f.player?.exclusive == true)))
        DiagCheck.APP_VERSION -> buildList {
            add(stringResource(R.string.diag_version, BuildConfig.VERSION_NAME))
            add(stringResource(R.string.diag_build, BuildConfig.VERSION_CODE.toString()))
            f.update?.let { add(text.update(it)) }
        }
        DiagCheck.DEVICE -> f.device?.let { d ->
            buildList {
                add(text.device(d.manufacturer, d.model))
                add(text.android(d.androidRelease, d.sdkInt))
                f.internet?.let { add(stringResource(R.string.diag_network_type, stringResource(ResourceDiagnosticText.transportRes(it.transport)))) }
                if (d.storageTotalBytes > 0) add(stringResource(R.string.diag_storage_free, text.bytes(d.storageFreeBytes), text.bytes(d.storageTotalBytes)))
                if (DiagnosticEvaluator.isLowStorage(d)) add(stringResource(R.string.diag_storage_low))
            }
        }.orEmpty()
    }
}

@Composable
private fun AdvancedDetails(f: DiagnosticFacts, text: ResourceDiagnosticText, speedAtMs: Long?) {
    val d = f.device
    if (d == null) {
        InfoCard(lines = listOf(stringResource(R.string.diag_not_run)))
        return
    }
    InfoCard(
        title = stringResource(R.string.diag_advanced),
        lines = buildList {
            add(stringResource(R.string.diag_adv_cpu, d.abi))
            add(stringResource(R.string.diag_adv_memory, text.memory(d.availRamBytes, d.totalRamBytes)))
            if (d.storageTotalBytes > 0) add(stringResource(R.string.diag_storage_free, text.bytes(d.storageFreeBytes), text.bytes(d.storageTotalBytes)))
            if (d.screenWidthPx > 0) add(stringResource(R.string.diag_adv_screen, text.screen(d.screenWidthPx, d.screenHeightPx, d.refreshRateHz)))
            f.internet?.let { add(stringResource(R.string.diag_network_type, stringResource(ResourceDiagnosticText.transportRes(it.transport)))) }
            f.internet?.latencyMs?.let { add(stringResource(R.string.diag_latency_line, text.latency(it))) }
            val result = f.speed
            if (result != null && speedAtMs != null) add(stringResource(R.string.diag_adv_last_speed, text.speed(result), text.timestamp(speedAtMs)))
        },
    )
    InfoCard(
        title = stringResource(R.string.diag_adv_codecs),
        lines = DiagCodec.entries.map { c -> text.codec(c, d.codecs[c] ?: CodecSupport.NOT_REPORTED) } + stringResource(R.string.diag_codec_note),
    )
}

/**
 * The speed test's live read-out. Shows only what the engine measured: the latency phase, then the
 * current download rate from its one-second window and the elapsed share of the time budget, then the
 * result. A failure is shown as "could not be completed", never as a slow connection.
 */
@Composable
private fun SpeedTestPanel(state: SpeedTestState, text: ResourceDiagnosticText) {
    val colors = OwnTVTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            SpeedTestState.Idle -> Text(stringResource(R.string.diag_speed_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            is SpeedTestState.Latency -> {
                Text(stringResource(R.string.diag_speed_testing_latency), style = MaterialTheme.typography.titleMedium, color = colors.primary)
                SpeedProgressBar(if (state.of > 0) state.sample.toFloat() / state.of else 0f)
            }
            is SpeedTestState.Downloading -> {
                Text(stringResource(R.string.diag_speed_testing_download), style = MaterialTheme.typography.titleMedium, color = colors.primary)
                // The first chunk has no window to measure over yet: show the rate once there is one.
                if (state.currentMbps > 0.0) {
                    SpeedFigure(stringResource(R.string.diag_speed_download), stringResource(R.string.diag_speed_mbps, text.decimal(state.currentMbps)))
                }
                state.latencyMs?.let { SpeedFigure(stringResource(R.string.diag_speed_latency), text.latency(it)) }
                SpeedProgressBar((state.elapsedMs.toFloat() / state.maxMs).coerceIn(0f, 1f))
            }
            is SpeedTestState.Done -> {
                val r = state.result
                SpeedFigure(stringResource(R.string.diag_speed_download), stringResource(R.string.diag_speed_mbps, text.decimal(r.downloadMbps)))
                r.latencyMs?.let { SpeedFigure(stringResource(R.string.diag_speed_latency), text.latency(it)) }
                SpeedFigure(stringResource(R.string.diag_speed_connection), stringResource(ResourceDiagnosticText.ratingRes(r.rating)))
                Text(
                    stringResource(
                        when (r.rating) {
                            SpeedRating.EXCELLENT, SpeedRating.GOOD -> R.string.diag_speed_note_4k
                            SpeedRating.FAIR -> R.string.diag_speed_note_hd
                            SpeedRating.POOR -> R.string.diag_speed_note_poor
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
                )
                Text(stringResource(R.string.diag_speed_disclaimer), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            is SpeedTestState.Failed -> Text(stringResource(R.string.diag_speed_failed), style = MaterialTheme.typography.bodyMedium, color = WarningAmber)
        }
    }
}

@Composable
private fun SpeedFigure(label: String, value: String) {
    val colors = OwnTVTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.width(160.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeedProgressBar(fraction: Float) {
    val colors = OwnTVTheme.colors
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.secondaryContainer)) {
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.primary))
    }
}

/** A focusable status card: focus is what lets the D-pad scroll through the results. */
@Composable
private fun StatusCard(title: String, status: DiagStatus, lines: List<String>, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                StatusChip(status)
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
    }
}

@Composable
private fun StatusChip(status: DiagStatus) {
    val colors = OwnTVTheme.colors
    val (bg, fg) = when (status) {
        DiagStatus.PASS -> colors.primaryContainer to colors.onPrimaryContainer
        DiagStatus.WARNING -> WarningAmber to Color.Black
        DiagStatus.FAILED -> FailedRed to Color.White
        DiagStatus.CHECKING, DiagStatus.NOT_CHECKED -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Text(
        stringResource(ResourceDiagnosticText.statusRes(status)).uppercase(),
        style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * A block of explanatory text. [focusable] cards let the D-pad scroll through long content (Advanced
 * Details); text that only introduces the button under it is not focusable, so focus moves button to
 * button. Unfocused, both look identical (cards are transparent until focused).
 */
@Composable
private fun InfoCard(lines: List<String>, title: String? = null, focusable: Boolean = true) {
    val colors = OwnTVTheme.colors
    if (!focusable) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
        return
    }
    FocusableSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        surface = GlassSurface.CARDS,
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun SwitchPlayerDialog(target: String, current: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
        Column(Modifier.dialogPanel(width = 480.dp, padding = 28.dp)) {
            Text(stringResource(R.string.diag_switch_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.diag_switch_message, target, current), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Cancel holds the initial focus so a stray OK press changes nothing.
                OwnTVButton(stringResource(R.string.common_cancel), onClick = onDismiss, style = OwnTVButtonStyle.SECONDARY, modifier = Modifier.focusRequester(focus))
                Spacer(Modifier.weight(1f))
                OwnTVButton(stringResource(R.string.diag_switch_confirm), onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun VpnHelpDialog(onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().modalScrim().trapAllFocusExit().focusGroup(), contentAlignment = Alignment.Center) {
        Column(Modifier.dialogPanel(width = 520.dp, padding = 28.dp)) {
            Text(stringResource(R.string.diag_vpn_help), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.diag_rec_service_vpn), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.diag_vpn_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            OwnTVButton(stringResource(R.string.content_close), onClick = onDismiss, modifier = Modifier.focusRequester(focus))
        }
    }
}

/** Toast text for a [DiagnosticCenterViewModel.Notice] (resolved outside composition). */
private fun noticeMessage(context: Context, n: DiagnosticCenterViewModel.Notice, engine: VodEngine): String = when (n) {
    DiagnosticCenterViewModel.Notice.PLAYLIST_RESYNC_STARTED -> context.getString(R.string.diag_notice_playlist_resync)
    DiagnosticCenterViewModel.Notice.NO_PLAYLIST -> context.getString(R.string.diag_notice_no_playlist)
    DiagnosticCenterViewModel.Notice.EPG_RESYNC_STARTED -> context.getString(R.string.diag_notice_epg_resync)
    DiagnosticCenterViewModel.Notice.NO_EPG -> context.getString(R.string.diag_notice_no_epg)
    DiagnosticCenterViewModel.Notice.EPG_ALREADY_RUNNING -> context.getString(R.string.diag_notice_epg_already_running)
    DiagnosticCenterViewModel.Notice.PLAYER_SWITCHED ->
        context.getString(R.string.diag_notice_player_switched, context.getString(ResourceDiagnosticText.engineRes(engine)))
    DiagnosticCenterViewModel.Notice.CACHE_CLEARED -> context.getString(R.string.diag_notice_cache_cleared)
    DiagnosticCenterViewModel.Notice.REPORT_COPIED -> context.getString(R.string.diag_notice_report_copied)
    DiagnosticCenterViewModel.Notice.SHARE_UNAVAILABLE -> context.getString(R.string.diag_notice_share_unavailable)
}

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diag_report_clip_label), report))
}

/** False when no app on the device can receive shared text (common on Android TV). */
private fun shareReport(context: Context, report: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType(PLAIN_TEXT).putExtra(Intent.EXTRA_TEXT, report)
    val chooser = Intent.createChooser(send, context.getString(R.string.diag_share_report)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Android's App Info screen for this app, where the user can clear cache/storage or force stop. */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts(PACKAGE_SCHEME, context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val PLAIN_TEXT = "text/plain"
private const val PACKAGE_SCHEME = "package"
