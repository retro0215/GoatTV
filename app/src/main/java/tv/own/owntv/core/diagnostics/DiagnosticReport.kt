package tv.own.owntv.core.diagnostics

/** Labels of the copyable report, resolved to the user's language by [DiagnosticText]. */
enum class ReportLabel {
    SUMMARY, APP, DEVICE, ANDROID, NETWORK, LATENCY, SPEED,
    INTERNET, SERVICE, PLAYLIST, CHANNELS, MOVIES, SERIES, EPG, GUIDE_PROGRAMMES,
    LIVE_TV, VOD, VOD_PLAYER, SPORTS, SPORTS_EVENTS, APP_UPDATE, DEVICE_CHECK,
    RECOMMENDATIONS, ADVANCED, CPU, MEMORY, STORAGE, SCREEN, CODECS, TIMESTAMP,
}

/**
 * All wording of the report. The app implements it with string resources; unit tests use a plain
 * English fake. Keeping wording out of [DiagnosticReport] is what lets the builder stay pure.
 */
interface DiagnosticText {
    fun title(): String
    fun line(label: ReportLabel, value: String): String
    fun bullet(text: String): String
    fun none(): String
    fun status(status: DiagStatus): String
    fun summary(summary: DiagnosticSummary): String
    fun app(brand: String, version: String, code: Long): String
    fun device(manufacturer: String, model: String): String
    fun android(release: String, sdk: Int): String
    fun transport(transport: NetworkTransport): String
    fun engine(engine: VodEngine, exclusive: Boolean): String
    fun update(update: UpdateFact): String
    fun recommendation(recommendation: Recommendation): String
    fun latency(ms: Long): String
    fun speed(result: SpeedTestResult): String
    fun count(value: Int): String
    fun bytes(value: Long): String
    fun memory(availBytes: Long, totalBytes: Long): String
    fun storage(freeBytes: Long, totalBytes: Long): String
    fun screen(widthPx: Int, heightPx: Int, refreshHz: Float): String
    fun codec(codec: DiagCodec, support: CodecSupport): String
    fun timestamp(epochMs: Long): String
}

/**
 * Builds the plain-text report behind COPY REPORT / SHARE REPORT.
 *
 * Safe by construction: the input is [DiagnosticFacts], which only holds counts, enums, flags, the
 * brand name/version and the device's marketing model — never a username, password, provider or
 * Sports address, stream URL, remote/EPG channel id, token or device identifier. [scrub] then removes
 * anything that still *looks* like an address, identifier or token, as a second line of defence
 * (e.g. against an odd device model string).
 */
object DiagnosticReport {

    fun build(
        facts: DiagnosticFacts,
        results: List<CheckResult>,
        summary: DiagnosticSummary,
        recommendations: List<Recommendation>,
        text: DiagnosticText,
        nowMs: Long,
    ): String {
        val status = results.associate { it.check to it.status }
        fun st(check: DiagCheck) = text.status(status[check] ?: DiagStatus.NOT_CHECKED)
        val lines = mutableListOf<String>()
        lines += text.title()
        lines += ""
        lines += text.line(ReportLabel.SUMMARY, text.summary(summary))
        lines += text.line(ReportLabel.APP, text.app(facts.app.brandName, facts.app.versionName, facts.app.versionCode))
        facts.device?.let { d ->
            lines += text.line(ReportLabel.DEVICE, text.device(d.manufacturer, d.model))
            lines += text.line(ReportLabel.ANDROID, text.android(d.androidRelease, d.sdkInt))
        }
        facts.internet?.let { net ->
            lines += text.line(ReportLabel.NETWORK, text.transport(net.transport))
            net.latencyMs?.let { lines += text.line(ReportLabel.LATENCY, text.latency(it)) }
        }
        facts.speed?.let { lines += text.line(ReportLabel.SPEED, text.speed(it)) }
        lines += ""
        lines += text.line(ReportLabel.INTERNET, st(DiagCheck.INTERNET))
        lines += text.line(ReportLabel.SERVICE, st(DiagCheck.SERVICE))
        lines += text.line(ReportLabel.PLAYLIST, st(DiagCheck.PLAYLIST))
        facts.playlist?.takeIf { it.hasSource }?.let { p ->
            lines += text.line(ReportLabel.CHANNELS, text.count(p.channels))
            lines += text.line(ReportLabel.MOVIES, text.count(p.movies))
            lines += text.line(ReportLabel.SERIES, text.count(p.series))
        }
        lines += text.line(ReportLabel.EPG, st(DiagCheck.EPG))
        facts.epg?.takeIf { it.sourceCount > 0 }?.let { lines += text.line(ReportLabel.GUIDE_PROGRAMMES, text.count(it.programmes)) }
        lines += text.line(ReportLabel.LIVE_TV, st(DiagCheck.LIVE_TV))
        lines += text.line(ReportLabel.VOD, st(DiagCheck.VOD))
        facts.player?.let { lines += text.line(ReportLabel.VOD_PLAYER, text.engine(it.vodEngine, it.exclusive)) }
        lines += text.line(ReportLabel.SPORTS, st(DiagCheck.SPORTS))
        (facts.sports as? SportsFact.Connected)?.let { lines += text.line(ReportLabel.SPORTS_EVENTS, text.count(it.events)) }
        facts.update?.let { lines += text.line(ReportLabel.APP_UPDATE, text.update(it)) }
        lines += text.line(ReportLabel.DEVICE_CHECK, st(DiagCheck.DEVICE))
        lines += ""
        lines += text.line(ReportLabel.RECOMMENDATIONS, if (recommendations.isEmpty()) text.none() else "")
        recommendations.forEach { lines += text.bullet(text.recommendation(it)) }
        facts.device?.let { d ->
            lines += ""
            lines += text.line(ReportLabel.ADVANCED, "")
            lines += text.line(ReportLabel.CPU, d.abi)
            lines += text.line(ReportLabel.MEMORY, text.memory(d.availRamBytes, d.totalRamBytes))
            lines += text.line(ReportLabel.STORAGE, text.storage(d.storageFreeBytes, d.storageTotalBytes))
            lines += text.line(ReportLabel.SCREEN, text.screen(d.screenWidthPx, d.screenHeightPx, d.refreshRateHz))
            lines += text.line(ReportLabel.CODECS, "")
            DiagCodec.entries.forEach { c -> lines += text.bullet(text.codec(c, d.codecs[c] ?: CodecSupport.NOT_REPORTED)) }
        }
        lines += ""
        lines += text.line(ReportLabel.TIMESTAMP, text.timestamp(nowMs))
        return scrub(lines.joinToString(NEWLINE) { it.trimEnd() })
    }

    private const val NEWLINE = "\n"
    private const val REDACTED = "***"

    // Anything address-, identifier- or token-shaped. Order matters: URLs before bare hosts/IPs.
    private val SENSITIVE = listOf(
        Regex("""(?i)\b[a-z][a-z0-9+.\-]*://\S+"""),
        Regex("""(?i)\b(?:username|password|token|apikey|api_key|mac)=\S+"""),
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
        Regex("""(?i)\b(?:[0-9a-f]{2}[:\-]){5}[0-9a-f]{2}\b"""),
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b"""),
        Regex("""(?i)\b(?:[a-z0-9\-]+\.)+(?:com|net|org|tv|io|app|xyz|me|cc|co|info|live|online|site|pro|top|uk|us|ca)(?::\d+)?(?:/\S*)?"""),
        Regex("""\b[A-Za-z0-9_\-]{32,}\b"""),
    )

    /** Removes anything that looks like an address, identifier or secret. */
    fun scrub(text: String): String = SENSITIVE.fold(text) { acc, re -> re.replace(acc, REDACTED) }
}
