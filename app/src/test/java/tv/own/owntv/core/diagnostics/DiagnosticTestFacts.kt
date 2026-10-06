package tv.own.owntv.core.diagnostics

/** Shared fixtures: a healthy box, from which each test breaks exactly one thing. */
internal object DiagnosticTestFacts {
    val app = AppFact(brandName = "GoatTV", versionName = "4.4.24", versionCode = 40424)

    val device = DeviceFact(
        manufacturer = "onn",
        model = "4K Plus",
        androidRelease = "14",
        sdkInt = 34,
        abi = "arm64-v8a",
        totalRamBytes = 4L shl 30,
        availRamBytes = 2L shl 30,
        storageFreeBytes = 20L shl 30,
        storageTotalBytes = 32L shl 30,
        screenWidthPx = 3840,
        screenHeightPx = 2160,
        refreshRateHz = 60f,
        codecs = DiagCodec.entries.associateWith { CodecSupport.SUPPORTED },
    )

    val playlist = PlaylistFact(
        hasSource = true, channels = 4625, movies = 12345, series = 3456,
        liveEnabled = true, moviesEnabled = true, seriesEnabled = true, lastSyncAtMs = 1_759_600_000_000,
    )

    val healthy = DiagnosticFacts(
        app = app,
        internet = InternetFact(reachable = true, transport = NetworkTransport.WIFI, latencyMs = 31),
        service = ServiceFact.Ok(ServiceKind.XTREAM),
        playlist = playlist,
        epg = EpgFact(sourceCount = 1, programmes = 250_000, guideChannels = 900, lastSyncAtMs = 1_759_600_000_000, lastSyncFailed = false),
        sports = SportsFact.Connected(events = 120, gameCenterOk = true),
        player = PlayerFact(VodEngine.EXO, exclusive = false),
        update = UpdateFact.UpToDate,
        device = device,
    )
}

/** Plain English [DiagnosticText], close to the shipped resources, for report tests. */
internal class EnglishDiagnosticText : DiagnosticText {
    override fun title() = "DIAGNOSTIC REPORT"
    override fun line(label: ReportLabel, value: String) = "${label.name}: $value"
    override fun bullet(text: String) = "- $text"
    override fun none() = "None"
    override fun status(status: DiagStatus) = status.name
    override fun summary(summary: DiagnosticSummary) = when {
        !summary.complete -> "Diagnostic not finished"
        summary.issues == 0 -> "All checks passed"
        summary.issues == 1 -> "1 issue found"
        else -> "${summary.issues} issues found"
    }
    override fun app(brand: String, version: String, code: Long) = "$brand $version (build $code)"
    override fun device(manufacturer: String, model: String) = "$manufacturer $model"
    override fun android(release: String, sdk: Int) = "Android $release (API $sdk)"
    override fun transport(transport: NetworkTransport) = transport.name
    override fun engine(engine: VodEngine, exclusive: Boolean) = if (exclusive) "${engine.name} only" else engine.name
    override fun update(update: UpdateFact) = when (update) {
        UpdateFact.UpToDate -> "Current"
        is UpdateFact.Available -> "Available ${update.version}"
        UpdateFact.CheckFailed -> "Check failed"
        UpdateFact.Busy -> "Downloading"
    }
    override fun recommendation(recommendation: Recommendation) = recommendation.name
    override fun latency(ms: Long) = "$ms ms"
    override fun speed(result: SpeedTestResult) = "${result.downloadMbps} Mbps ${result.rating}"
    override fun count(value: Int) = value.toString()
    override fun bytes(value: Long) = "${value shr 20} MB"
    override fun memory(availBytes: Long, totalBytes: Long) = "${bytes(availBytes)} of ${bytes(totalBytes)}"
    override fun storage(freeBytes: Long, totalBytes: Long) = "${bytes(freeBytes)} free of ${bytes(totalBytes)}"
    override fun screen(widthPx: Int, heightPx: Int, refreshHz: Float) = "${widthPx}x$heightPx@${refreshHz.toInt()}"
    override fun codec(codec: DiagCodec, support: CodecSupport) = "${codec.name}: ${support.name}"
    override fun timestamp(epochMs: Long) = "T$epochMs"
}
