package tv.own.owntv.features.settings

import android.content.Context
import android.text.format.Formatter
import tv.own.owntv.R
import tv.own.owntv.core.diagnostics.CodecSupport
import tv.own.owntv.core.diagnostics.DiagCheck
import tv.own.owntv.core.diagnostics.DiagCodec
import tv.own.owntv.core.diagnostics.DiagStatus
import tv.own.owntv.core.diagnostics.DiagnosticSummary
import tv.own.owntv.core.diagnostics.DiagnosticText
import tv.own.owntv.core.diagnostics.NetworkTransport
import tv.own.owntv.core.diagnostics.Recommendation
import tv.own.owntv.core.diagnostics.ReportLabel
import tv.own.owntv.core.diagnostics.UpdateFact
import tv.own.owntv.core.diagnostics.VodEngine
import tv.own.owntv.ui.format.formatBestDateTime
import java.text.NumberFormat

/** [DiagnosticText] from string resources, so the screen and the copied report share one wording. */
class ResourceDiagnosticText(private val context: Context) : DiagnosticText {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun title() = s(R.string.diag_report_title)
    override fun line(label: ReportLabel, value: String) = s(R.string.diag_report_line, labelText(label), value)
    override fun bullet(text: String) = s(R.string.diag_report_bullet, text)
    override fun none() = s(R.string.diag_report_none)
    override fun status(status: DiagStatus) = s(statusRes(status))
    override fun summary(summary: DiagnosticSummary) = when {
        !summary.complete -> s(R.string.diag_report_incomplete)
        summary.issues == 0 -> s(R.string.diag_all_passed)
        else -> context.resources.getQuantityString(R.plurals.diag_issues_found, summary.issues, summary.issues)
    }
    override fun app(brand: String, version: String, code: Long) = s(R.string.diag_report_app, brand, version, code.toString())
    override fun device(manufacturer: String, model: String) = s(R.string.diag_device_name, manufacturer, model)
    override fun android(release: String, sdk: Int) = s(R.string.diag_android, release, sdk.toString())
    override fun transport(transport: NetworkTransport) = s(transportRes(transport))
    override fun engine(engine: VodEngine, exclusive: Boolean) =
        s(if (exclusive) R.string.diag_engine_only else R.string.diag_engine_with_fallback, s(engineRes(engine)))
    override fun update(update: UpdateFact) = when (update) {
        UpdateFact.UpToDate -> s(R.string.diag_up_to_date)
        is UpdateFact.Available -> s(R.string.diag_update_available, update.version)
        UpdateFact.CheckFailed -> s(R.string.diag_update_failed)
        UpdateFact.Busy -> s(R.string.diag_update_busy)
    }
    override fun recommendation(recommendation: Recommendation) = s(recommendationRes(recommendation))
    override fun latency(ms: Long) = s(R.string.diag_latency_value, ms.toString())
    override fun speed(result: tv.own.owntv.core.diagnostics.SpeedTestResult): String {
        val mbps = NumberFormat.getNumberInstance(context.resources.configuration.locales[0])
            .apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
            .format(result.downloadMbps)
        return s(R.string.diag_speed_value, mbps, s(ratingRes(result.rating)))
    }
    override fun count(value: Int): String = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(value)
    override fun bytes(value: Long): String = Formatter.formatShortFileSize(context, value)
    override fun memory(availBytes: Long, totalBytes: Long) = s(R.string.diag_memory_value, bytes(availBytes), bytes(totalBytes))
    override fun storage(freeBytes: Long, totalBytes: Long) = s(R.string.diag_storage_value, bytes(freeBytes), bytes(totalBytes))
    override fun screen(widthPx: Int, heightPx: Int, refreshHz: Float) =
        s(R.string.diag_screen_value, widthPx.toString(), heightPx.toString(), Math.round(refreshHz).toString())
    override fun codec(codec: DiagCodec, support: CodecSupport) = s(R.string.diag_codec_line, s(codecRes(codec)), s(codecSupportRes(support)))
    override fun timestamp(epochMs: Long) = formatBestDateTime(context, DATE_SKELETON, epochMs)

    private fun labelText(label: ReportLabel): String = s(
        when (label) {
            ReportLabel.SUMMARY -> R.string.diag_label_summary
            ReportLabel.APP -> R.string.diag_label_app
            ReportLabel.DEVICE -> R.string.diag_label_device
            ReportLabel.ANDROID -> R.string.diag_label_android
            ReportLabel.NETWORK -> R.string.diag_label_network
            ReportLabel.LATENCY -> R.string.diag_label_latency
            ReportLabel.SPEED -> R.string.diag_label_speed
            ReportLabel.INTERNET -> R.string.diag_check_internet
            ReportLabel.SERVICE -> R.string.diag_check_service
            ReportLabel.PLAYLIST -> R.string.diag_check_playlist
            ReportLabel.CHANNELS -> R.string.diag_label_channels
            ReportLabel.MOVIES -> R.string.diag_label_movies
            ReportLabel.SERIES -> R.string.diag_label_series
            ReportLabel.EPG -> R.string.diag_check_epg
            ReportLabel.GUIDE_PROGRAMMES -> R.string.diag_label_programmes
            ReportLabel.LIVE_TV -> R.string.diag_check_live
            ReportLabel.VOD -> R.string.diag_check_vod
            ReportLabel.VOD_PLAYER -> R.string.diag_label_vod_player
            ReportLabel.SPORTS -> R.string.diag_check_sports
            ReportLabel.SPORTS_EVENTS -> R.string.diag_label_sports_events
            ReportLabel.APP_UPDATE -> R.string.diag_label_app_update
            ReportLabel.DEVICE_CHECK -> R.string.diag_label_device_check
            ReportLabel.RECOMMENDATIONS -> R.string.diag_recommendations
            ReportLabel.ADVANCED -> R.string.diag_label_advanced
            ReportLabel.CPU -> R.string.diag_label_cpu
            ReportLabel.MEMORY -> R.string.diag_label_memory
            ReportLabel.STORAGE -> R.string.diag_label_storage
            ReportLabel.SCREEN -> R.string.diag_label_screen
            ReportLabel.CODECS -> R.string.diag_label_codecs
            ReportLabel.TIMESTAMP -> R.string.diag_label_timestamp
        },
    )

    companion object {
        private const val DATE_SKELETON = "yMMMd"

        fun statusRes(status: DiagStatus): Int = when (status) {
            DiagStatus.NOT_CHECKED -> R.string.diag_status_not_checked
            DiagStatus.CHECKING -> R.string.diag_status_checking
            DiagStatus.PASS -> R.string.diag_status_pass
            DiagStatus.WARNING -> R.string.diag_status_warning
            DiagStatus.FAILED -> R.string.diag_status_failed
        }

        fun checkRes(check: DiagCheck): Int = when (check) {
            DiagCheck.INTERNET -> R.string.diag_check_internet
            DiagCheck.SERVICE -> R.string.diag_check_service
            DiagCheck.PLAYLIST -> R.string.diag_check_playlist
            DiagCheck.EPG -> R.string.diag_check_epg
            DiagCheck.LIVE_TV -> R.string.diag_check_live
            DiagCheck.VOD -> R.string.diag_check_vod
            DiagCheck.SPORTS -> R.string.diag_check_sports
            DiagCheck.PLAYER -> R.string.diag_check_player
            DiagCheck.APP_VERSION -> R.string.diag_check_version
            DiagCheck.DEVICE -> R.string.diag_check_device
        }

        fun transportRes(t: NetworkTransport): Int = when (t) {
            NetworkTransport.WIFI -> R.string.diag_net_wifi
            NetworkTransport.ETHERNET -> R.string.diag_net_ethernet
            NetworkTransport.CELLULAR -> R.string.diag_net_cellular
            NetworkTransport.OTHER -> R.string.diag_net_other
            NetworkTransport.NONE -> R.string.diag_net_none
        }

        fun engineRes(e: VodEngine): Int = if (e == VodEngine.MPV) R.string.diag_engine_mpv else R.string.diag_engine_exo

        fun codecRes(c: DiagCodec): Int = when (c) {
            DiagCodec.H264 -> R.string.diag_codec_h264
            DiagCodec.HEVC -> R.string.diag_codec_hevc
            DiagCodec.AV1 -> R.string.diag_codec_av1
            DiagCodec.AAC -> R.string.diag_codec_aac
            DiagCodec.AC3 -> R.string.diag_codec_ac3
            DiagCodec.EAC3 -> R.string.diag_codec_eac3
        }

        fun ratingRes(r: tv.own.owntv.core.diagnostics.SpeedRating): Int = when (r) {
            tv.own.owntv.core.diagnostics.SpeedRating.EXCELLENT -> R.string.diag_speed_excellent
            tv.own.owntv.core.diagnostics.SpeedRating.GOOD -> R.string.diag_speed_good
            tv.own.owntv.core.diagnostics.SpeedRating.FAIR -> R.string.diag_speed_fair
            tv.own.owntv.core.diagnostics.SpeedRating.POOR -> R.string.diag_speed_poor
        }

        fun codecSupportRes(s: CodecSupport): Int =
            if (s == CodecSupport.SUPPORTED) R.string.diag_codec_supported else R.string.diag_codec_not_reported

        fun recommendationRes(r: Recommendation): Int = when (r) {
            Recommendation.RESTART_ROUTER -> R.string.diag_rec_restart_router
            Recommendation.SERVICE_UNREACHABLE_VPN -> R.string.diag_rec_service_vpn
            Recommendation.CHECK_ACCOUNT -> R.string.diag_rec_check_account
            Recommendation.ADD_PLAYLIST -> R.string.diag_rec_add_playlist
            Recommendation.RESYNC_PLAYLIST -> R.string.diag_rec_resync_playlist
            Recommendation.ADD_EPG -> R.string.diag_rec_add_epg
            Recommendation.RESYNC_EPG -> R.string.diag_rec_resync_epg
            Recommendation.SPORTS_LATER -> R.string.diag_rec_sports_later
            Recommendation.UPDATE_APP -> R.string.diag_rec_update_app
            Recommendation.FREE_STORAGE -> R.string.diag_rec_free_storage
        }
    }
}
