package tv.own.owntv.player

import java.text.Normalizer
import java.util.Locale

/**
 * Movies & Series audio-track diagnostics (VOD Phase 5A). Report-only: nothing here selects, reorders or
 * filters a track. It exists so a "plays the commentary instead of the film" report can be answered from
 * the exported diagnostics ring: which tracks the file offered, what metadata each carried, which one the
 * engine picked, and why.
 *
 * Raw provider titles are never logged — they are free text (release groups, sometimes worse). Only the
 * [AudioTitleClass] derived from them is.
 */
enum class AudioTitleClass(val wire: String) {
    MAIN("main"),
    COMMENTARY("commentary"),
    AUDIO_DESCRIPTION("audio_description"),
    /** Titled, but nothing in the title says what kind of track it is (e.g. "English 5.1"). */
    OTHER("other"),
    /** No title and no flag to go on. */
    UNKNOWN("unknown"),
}

/** Why the selected track is the selected one. Only ever set from evidence (see [VodAudioDiagnostics]). */
enum class AudioSelectionReason(val wire: String) {
    ENGINE_DEFAULT("engine_default"),
    USER("user"),
    ENGINE_HANDOFF("engine_handoff"),
    /** The customer's own pick, re-found and re-applied on the engine that took the item over (5B). */
    USER_CARRIED("user_carried"),
    UNKNOWN("unknown"),
}

object AudioTrackClassifier {

    const val ROLE_FLAG_MAIN = androidx.media3.common.C.ROLE_FLAG_MAIN
    const val ROLE_FLAG_COMMENTARY = androidx.media3.common.C.ROLE_FLAG_COMMENTARY
    const val ROLE_FLAG_DESCRIBES_VIDEO = androidx.media3.common.C.ROLE_FLAG_DESCRIBES_VIDEO

    // Matched on a lower-cased, accent-stripped title. Substrings, so "commentaire", "commento" and
    // "Director's Commentary" all hit "comment".
    private val COMMENTARY_WORDS = listOf(
        "comment",       // en commentary, fr commentaire, it commento, nl commentaar
        "kommentar",     // de, sv, da, no
        "comentario",    // es, pt (comentário after accent stripping)
        "komentarz",     // pl
        "komentar",      // cs, sk, hr
        "yorum",         // tr
    )
    private val AUDIO_DESCRIPTION_WORDS = listOf(
        "audio description", "audio-description", "audiodescription", "audio described",
        "described video", "descriptive", "visually impaired", "audiodeskription",
        "audiodescripcion", "audiodescricao", "audiodescrizione", "audiobeschrijving",
        "description audio", // fr "piste description audio"
    )
    /** A bare "AD" token ("English (AD)", "ENG AD 2.0") — whole-token only, never inside a word. */
    private val AD_TOKEN = Regex("(^|[^a-z0-9])ad([^a-z0-9]|$)")
    /** Word-start only, so "domain" or "remain" never read as main. "original" also covers originale/-ton. */
    private val MAIN_TOKEN = Regex("(^|[^a-z0-9])(main|original|principal)")

    /**
     * Classify one audio track. Flags win over the title because they are structured metadata; the title
     * is the only signal Xtream/M3U MKV and MP4 files usually carry (Media3 reads no commentary flag from
     * them, and mpv exposes none at all).
     */
    fun classify(title: String?, visualImpaired: Boolean? = null, roleFlags: Int? = null): AudioTitleClass {
        if (roleFlags != null) {
            if (roleFlags and ROLE_FLAG_COMMENTARY != 0) return AudioTitleClass.COMMENTARY
            if (roleFlags and ROLE_FLAG_DESCRIBES_VIDEO != 0) return AudioTitleClass.AUDIO_DESCRIPTION
        }
        if (visualImpaired == true) return AudioTitleClass.AUDIO_DESCRIPTION
        val t = normalize(title)
        if (t.isEmpty()) {
            return if (roleFlags != null && roleFlags and ROLE_FLAG_MAIN != 0) AudioTitleClass.MAIN else AudioTitleClass.UNKNOWN
        }
        if (COMMENTARY_WORDS.any { it in t }) return AudioTitleClass.COMMENTARY
        if (AUDIO_DESCRIPTION_WORDS.any { it in t } || AD_TOKEN.containsMatchIn(t)) return AudioTitleClass.AUDIO_DESCRIPTION
        if (MAIN_TOKEN.containsMatchIn(t)) return AudioTitleClass.MAIN
        if (roleFlags != null && roleFlags and ROLE_FLAG_MAIN != 0) return AudioTitleClass.MAIN
        return AudioTitleClass.OTHER
    }

    private fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return stripped.lowercase(Locale.ROOT).trim()
    }
}

/**
 * One audio track as an engine reported it. [index] is the ordinal among the file's audio tracks — the same
 * numbering on both engines, so an mpv line and an ExoPlayer line about one file line up. Null = the engine
 * does not expose that field (mpv has no role flags; ExoPlayer has no visual-impaired flag).
 */
data class VodAudioTrackInfo(
    val index: Int,
    val language: String?,
    val codec: String?,
    val channels: Int?,
    val isDefault: Boolean?,
    val visualImpaired: Boolean?,
    val roleFlags: Int?,
    val titleClass: AudioTitleClass,
    val playable: Boolean?,
    val selected: Boolean,
) {
    /** The wire form — no title, no id, no URL. */
    fun describe(): String =
        "[$index lang=${language ?: "-"} codec=${codec ?: "-"} ch=${channels ?: "-"} " +
            "default=${flag(isDefault)} vi=${flag(visualImpaired)} role=${roleFlags ?: "-"} " +
            "class=${titleClass.wire} playable=${flag(playable)}${if (selected) " sel" else ""}]"

    private fun flag(b: Boolean?): String = when (b) { true -> "y"; false -> "n"; null -> "-" }
}

/**
 * Turns engine track reports into exported-ring lines for ONE player ([OwnTVPlayer] owns one; mpv and its
 * ExoPlayer engine both report through it, so a handoff between them is visible as such).
 *
 * Duplicate suppression: a track list is written only when its content differs from the last one written
 * for that engine and item; a selection only when it differs from the last selection written for that item
 * on that engine. Selection reasons are decided from evidence only:
 *  - [AudioSelectionReason.USER]: the customer picked it in the HUD ([onUserSelected]).
 *  - [AudioSelectionReason.ENGINE_DEFAULT]: the first selection seen for this item, on the first engine.
 *  - [AudioSelectionReason.ENGINE_HANDOFF]: the first selection on an engine that took the item over from
 *    the other one (an earlier selection for the same item exists on a different engine).
 *  - [AudioSelectionReason.UNKNOWN]: any other change — nothing recorded explains it.
 *
 * Not thread-safe by itself: callers serialize (see [OwnTVPlayer]).
 */
class VodAudioDiagnostics(private val sink: (String) -> Unit = { LiveDiagnosticsLog.event(it) }) {

    private var itemKey: String? = null
    private val lastTracksLine = HashMap<String, String>()                 // engine -> last line written
    private val lastTracks = HashMap<String, List<VodAudioTrackInfo>>()    // engine -> last list reported
    private val lastSelected = HashMap<String, Int>()                      // engine -> last selected index
    private var lastEngineWithSelection: String? = null

    /** A new movie/episode/alternate version started from the UI (not an in-place reload): fresh state. */
    fun startItem(contentKey: String?) {
        itemKey = safeKey(contentKey)
        lastTracksLine.clear(); lastTracks.clear(); lastSelected.clear(); lastEngineWithSelection = null
    }

    /** Not a Movies & Series item (Live TV on mpv): report nothing until the next [startItem]. */
    fun stopItem() {
        itemKey = null
        lastTracksLine.clear(); lastTracks.clear(); lastSelected.clear(); lastEngineWithSelection = null
    }

    /** [engine] ("mpv" / "exo") published its audio tracks for the current item; [player] is the
     *  per-player diagnostics tag when the engine has one. */
    fun onTracks(engine: String, tracks: List<VodAudioTrackInfo>, player: String? = null) {
        val key = itemKey ?: return
        lastTracks[engine] = tracks
        val body = tracks.take(MAX_TRACKS).joinToString(" ") { it.describe() } +
            if (tracks.size > MAX_TRACKS) " +${tracks.size - MAX_TRACKS} more" else ""
        val who = if (player != null) "engine=$engine player=$player" else "engine=$engine"
        val line = "VOD_AUDIO_TRACKS $who content=$key count=${tracks.size} $body"
        if (lastTracksLine[engine] != line) {
            lastTracksLine[engine] = line
            sink(line)
        }
        tracks.firstOrNull { it.selected }?.let { observeSelection(engine, it) }
    }

    /** The customer chose [index] in the HUD. Logged at once; the engine echoing it back is suppressed. */
    fun onUserSelected(engine: String, index: Int) {
        if (itemKey == null) return
        writeSelection(engine, index, lastTracks[engine]?.firstOrNull { it.index == index }, AudioSelectionReason.USER)
    }

    /**
     * The outcome of carrying the customer's pick into [engine] (5B). A match that had to change the track
     * is also written as a selection with reason `user_carried`; an ambiguous or missing match leaves the
     * engine's own pick, said explicitly as `fallback=engine_default`.
     */
    fun onCarry(engine: String, from: String, result: CarryResult, applied: Boolean) {
        val key = itemKey ?: return
        val idx = result.target?.index
        val tail = when (result.outcome) {
            CarryOutcome.MATCHED -> " basis=${result.basis} index=$idx applied=$applied"
            else -> " fallback=engine_default"
        }
        sink("VOD_AUDIO_CARRY engine=$engine from=$from content=$key outcome=${result.outcome.wire}$tail")
        if (applied && idx != null) {
            writeSelection(engine, idx, lastTracks[engine]?.firstOrNull { it.index == idx }, AudioSelectionReason.USER_CARRIED)
        }
    }

    private fun observeSelection(engine: String, track: VodAudioTrackInfo) {
        // Same engine still on the same track: nothing to say (re-reports, in-place reloads, echoes).
        if (lastEngineWithSelection == engine && lastSelected[engine] == track.index) return
        val reason = when (lastEngineWithSelection) {
            null -> AudioSelectionReason.ENGINE_DEFAULT        // first pick for this item
            engine -> AudioSelectionReason.UNKNOWN             // changed with no request we know of
            else -> AudioSelectionReason.ENGINE_HANDOFF        // the other engine had the item until now
        }
        writeSelection(engine, track.index, track, reason)
    }

    private fun writeSelection(engine: String, index: Int, track: VodAudioTrackInfo?, reason: AudioSelectionReason) {
        val key = itemKey ?: return
        lastSelected[engine] = index
        lastEngineWithSelection = engine
        val detail = track?.let {
            " lang=${it.language ?: "-"} codec=${it.codec ?: "-"} ch=${it.channels ?: "-"} class=${it.titleClass.wire}"
        } ?: ""
        sink("VOD_AUDIO_SELECTED engine=$engine content=$key index=$index reason=${reason.wire}$detail")
    }

    companion object {
        /** Long track lists are rare; capping keeps one line well inside the ring's per-line budget. */
        const val MAX_TRACKS = 16

        /** `sourceId:MOVIE|EPISODE:remoteId` from the catalog, never a URL. Anything URL-shaped is refused. */
        fun safeKey(contentKey: String?): String {
            val k = contentKey?.takeIf { it.isNotBlank() } ?: return "none"
            return if ("://" in k || k.startsWith("/")) "withheld" else k
        }
    }
}

/**
 * ExoPlayer's view of one audio track. Media3 holds language, MIME type, channel count, selection flags
 * (MKV `FlagDefault`; MP4 and TS set none) and role flags (HLS/DASH only). It has no visual-impaired flag,
 * so that is reported as unknown; [Format.label] is used for classification and never logged.
 */
fun vodAudioTrackInfo(
    index: Int,
    format: androidx.media3.common.Format,
    playable: Boolean?,
    selected: Boolean,
): VodAudioTrackInfo = VodAudioTrackInfo(
    index = index,
    language = format.language,
    codec = format.sampleMimeType?.substringAfter('/'),
    channels = format.channelCount.takeIf { it != androidx.media3.common.Format.NO_VALUE },
    isDefault = format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_DEFAULT != 0,
    visualImpaired = null,
    roleFlags = format.roleFlags,
    titleClass = AudioTrackClassifier.classify(format.label, visualImpaired = null, roleFlags = format.roleFlags),
    playable = playable,
    selected = selected,
)

/**
 * mpv's view of one audio track (`track-list/N/…`). mpv has `default` and `visual-impaired` flags but no
 * role flags and no commentary flag, and it decodes every codec itself, so playability is not known here.
 * [title] is used for classification and never logged.
 */
fun mpvAudioTrackInfo(
    index: Int,
    title: String?,
    language: String?,
    codec: String?,
    channels: Int?,
    isDefault: Boolean?,
    visualImpaired: Boolean?,
    selected: Boolean,
): VodAudioTrackInfo = VodAudioTrackInfo(
    index = index,
    language = language?.takeIf { it.isNotBlank() },
    codec = codec?.takeIf { it.isNotBlank() },
    channels = channels?.takeIf { it > 0 },
    isDefault = isDefault,
    visualImpaired = visualImpaired,
    roleFlags = null,
    titleClass = AudioTrackClassifier.classify(title, visualImpaired = visualImpaired, roleFlags = null),
    playable = null,
    selected = selected,
)
