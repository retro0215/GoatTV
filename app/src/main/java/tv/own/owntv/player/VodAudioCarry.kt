package tv.own.owntv.player

import java.util.Locale

/**
 * VOD Phase 5B: keep the customer's own audio-track pick for the movie/episode they picked it on, across
 * mpv ⇄ ExoPlayer switches, and never let it leak into the next item.
 *
 * The two engines number tracks differently (mpv `aid` ids vs Media3 group/index), so a pick is carried as
 * its metadata and re-found in the other engine's list. A match is used only when it is unambiguous; on any
 * doubt the engine's own selection stands. Nothing is persisted.
 */

/** One audio track as an engine lists it. [engineId] is what that engine selects by (mpv `aid`; the
 *  ExoPlayer ordinal); [index] is the ordinal among the file's audio tracks. [title] stays in memory only. */
data class AudioCandidate(
    val index: Int,
    val engineId: Int,
    val language: String?,
    val codec: String?,
    val channels: Int?,
    val title: String?,
    val selected: Boolean,
)

/** A customer's pick, as engine-independent metadata. */
data class AudioChoice(
    val engine: String,
    val index: Int,
    val trackCount: Int,
    val language: String?,
    val codecFamily: String?,
    val channels: Int?,
    val titleKey: String?,
)

enum class CarryOutcome(val wire: String) { MATCHED("matched"), AMBIGUOUS("ambiguous"), MISSING("missing") }

/** [basis] says what decided a match: "metadata" (only one track fits) or "ordinal" (several identical
 *  tracks fit and the same file position is among them, in a list of the same length). */
data class CarryResult(val outcome: CarryOutcome, val target: AudioCandidate?, val basis: String?)

@androidx.media3.common.util.UnstableApi
object AudioTrackMatcher {

    /** Media3's own normalization (the one its track selector uses), so mpv's "eng"/"fre" (MKV's ISO 639-2/B)
     *  and Media3's "en"/"fr" agree. Primary subtag only; "und"/blank = unknown. */
    @androidx.media3.common.util.UnstableApi
    fun normalizeLanguage(code: String?): String? {
        val c = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" && it != "unk" } ?: return null
        val n = runCatching { androidx.media3.common.util.Util.normalizeLanguageCode(c) }.getOrNull() ?: c
        return n.substringBefore('-').lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && it != "und" }
    }

    /** mpv codec names and Media3 MIME subtypes folded onto one family name. Order matters (eac3 ⊃ ac3). */
    fun codecFamily(codec: String?): String? {
        val c = codec?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            "eac3" in c || "e-ac-3" in c || "ec-3" in c -> "eac3"
            "ac4" in c || "ac-4" in c -> "ac4"
            "ac3" in c || "ac-3" in c -> "ac3"
            "truehd" in c || "true-hd" in c || "mlp" in c -> "truehd"
            "dts" in c -> "dts"
            "aac" in c || "mp4a" in c -> "aac"
            "opus" in c -> "opus"
            "flac" in c -> "flac"
            "vorbis" in c -> "vorbis"
            c == "mpeg" || "mp3" in c || "mp2" in c || "mp1" in c || "mpeg-l" in c -> "mpeg"
            "pcm" in c || c == "raw" -> "pcm"
            else -> c
        }
    }

    fun titleKey(title: String?): String? =
        title?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    fun choiceOf(engine: String, picked: AudioCandidate, all: List<AudioCandidate>): AudioChoice = AudioChoice(
        engine = engine,
        index = picked.index,
        trackCount = all.size,
        language = normalizeLanguage(picked.language),
        codecFamily = codecFamily(picked.codec),
        channels = picked.channels?.takeIf { it > 0 },
        titleKey = titleKey(picked.title),
    )

    /**
     * Find [choice] in [candidates]. A field is compared only when BOTH sides know it (one engine may not
     * report a title or a channel count). Strict first (language, codec, channels, title); if nothing fits,
     * relaxed (language and title) for engines that disagree on codec naming or channel count.
     */
    fun match(choice: AudioChoice, candidates: List<AudioCandidate>): CarryResult {
        if (candidates.isEmpty()) return CarryResult(CarryOutcome.MISSING, null, null)
        val strict = candidates.filter { c ->
            agree(choice.language, normalizeLanguage(c.language)) &&
                agree(choice.codecFamily, codecFamily(c.codec)) &&
                agree(choice.channels, c.channels?.takeIf { it > 0 }) &&
                agree(choice.titleKey, titleKey(c.title))
        }
        pick(choice, candidates, strict)?.let { return it }
        val relaxed = candidates.filter { c ->
            agree(choice.language, normalizeLanguage(c.language)) && agree(choice.titleKey, titleKey(c.title))
        }
        // Relaxed must still be backed by something real, or it would "match" every track.
        if (choice.language == null && choice.titleKey == null) {
            return CarryResult(if (strict.isEmpty()) CarryOutcome.MISSING else CarryOutcome.AMBIGUOUS, null, null)
        }
        return pick(choice, candidates, relaxed) ?: CarryResult(
            if (relaxed.isEmpty() && strict.isEmpty()) CarryOutcome.MISSING else CarryOutcome.AMBIGUOUS, null, null,
        )
    }

    /** null = nothing fitting in [fits] (try the next tier); otherwise a decided result. */
    private fun pick(choice: AudioChoice, all: List<AudioCandidate>, fits: List<AudioCandidate>): CarryResult? = when {
        fits.isEmpty() -> null
        fits.size == 1 -> CarryResult(CarryOutcome.MATCHED, fits.single(), "metadata")
        // Several tracks look identical. The same file lists its tracks in the same order on both engines,
        // so the same position is the same track — but only if both lists have the same length.
        all.size == choice.trackCount && fits.any { it.index == choice.index } ->
            CarryResult(CarryOutcome.MATCHED, fits.first { it.index == choice.index }, "ordinal")
        else -> CarryResult(CarryOutcome.AMBIGUOUS, null, null)
    }

    private fun <T> agree(a: T?, b: T?): Boolean = a == null || b == null || a == b
}

/**
 * The per-player state behind 5B, kept by [OwnTVPlayer]. [enabled] is the brand flag; with it off this is
 * inert and the engines behave exactly as before. Synchronized: mpv's event thread and the main thread both
 * reach it.
 */
@androidx.media3.common.util.UnstableApi
class VodAudioSelectionSession(private val enabled: Boolean) {

    private var choice: AudioChoice? = null
    private var aidResetPending = false

    /** A new movie/episode/version from the UI (NOT a retry/reload/handoff of the same item). */
    @Synchronized fun newItem(isLive: Boolean) {
        choice = null
        aidResetPending = enabled && !isLive
    }

    /** True once per new VOD item: put mpv's `aid` back to auto before its loadfile, so an id set on the
     *  previous file cannot select a track in this one. */
    @Synchronized fun takeAidReset(): Boolean = aidResetPending.also { aidResetPending = false }

    /** The customer picked a track on [engine]. */
    @Synchronized fun userSelected(engine: String, picked: AudioCandidate, all: List<AudioCandidate>) {
        if (!enabled) return
        choice = AudioTrackMatcher.choiceOf(engine, picked, all)
    }

    /** The pick to carry into another engine's start, or null (none, or the flag is off). */
    @Synchronized fun currentChoice(): AudioChoice? = if (enabled) choice else null

    /** Whether ExoPlayer must drop audio overrides left from an earlier item when it starts one. */
    val resetsExoOverrides: Boolean get() = enabled
}
