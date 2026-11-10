package tv.own.owntv.player

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import tv.own.owntv.core.ui.findActivity
import kotlin.math.abs

/**
 * Auto frame rate (AFR) — switch the DISPLAY to a refresh rate that matches the video, so 24/25/50 fps
 * content stops juddering on a fixed 60Hz output.
 *
 * Two independent mechanisms exist on Android and OwnTV uses both:
 *
 * 1. `Surface.setFrameRate()` (API 30+) — a *hint* attached to the video surface. Cheap and seamless,
 *    but it does nothing at all on Android 10 and below. See [MpvVideoSurface] and ExoPlayer's
 *    `setVideoChangeFrameRateStrategy`.
 * 2. `WindowManager.LayoutParams.preferredDisplayModeId` (API 23+) — an explicit request for one of the
 *    display's advertised modes. This is what actually works on older TV boxes, notably Fire OS 7
 *    (API 28) where mechanism 1 is unavailable. That's the case in the report this was written for
 *    (Fire TV Stick 4K Max, Fire OS 7.7.1.3 — AFR worked in other players, which use this API).
 *
 * This controller implements mechanism 2. It is deliberately **window-level**, not surface-level:
 * `preferredDisplayModeId` is an attribute of the Activity's window, so it cannot live inside
 * [MpvVideoSurface] / `ExoPreviewSurface`. Both engines route through here, which also means VOD/mpv
 * gets working AFR on Fire OS 7 for the first time (previously mechanism 1 only → a silent no-op).
 *
 * Mode selection keeps the CURRENT resolution and only varies the refresh rate — never switch a 4K
 * output down to 1080p just to hit a rate. A mode is a match when its refresh rate is an integer
 * multiple (1x–3x) of the video fps, which makes 24fps prefer 24/48/72Hz and 25fps prefer 50Hz while
 * still accepting 60Hz for 30fps. Among matches a **seamless** switch wins first, then the LOWEST
 * multiple (a true 24Hz beats 72Hz) — see [pickMode].
 *
 * Every actual mode change costs an HDMI re-handshake, and on a live stream that black gap reads as
 * "the picture paused". Two guards keep changes to the ones that earn it: [snapFps] stops a drifting
 * *measured* frame rate from picking a different mode each time it is re-sampled, and a cooldown in
 * [apply] collapses a burst of requests into one switch.
 *
 * Everything is best-effort: no matching mode, a locked-down display, or a manufacturer that ignores
 * the request all degrade to "leave the display alone", never to a crash or a black screen.
 */
object FrameRateController {

    private const val TAG = "FrameRate"
    /** Refresh rates within this many Hz of an exact multiple count as a match (panels report 59.94 etc). */
    private const val TOLERANCE_HZ = 0.35f
    private const val MAX_MULTIPLE = 3
    /** Grace period before a release actually lands — see [reset]. */
    private const val RESET_DELAY_MS = 1_500L

    /**
     * The frame rates real content is actually shot/broadcast at. A [fps] reading is snapped to the
     * nearest of these before a mode is chosen — see [snapFps].
     */
    private val STANDARD_FPS = floatArrayOf(
        23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f,
    )

    /** A reading further than this from every entry in [STANDARD_FPS] is left alone rather than snapped. */
    private const val SNAP_MAX_DELTA_HZ = 1.0f

    /**
     * Minimum spacing between two display-mode changes. Every change is an HDMI re-handshake that blanks
     * the panel for a second or more, so a burst of them is far worse than a late one — see [apply].
     */
    private const val MODE_CHANGE_COOLDOWN_MS = 5_000L

    // Android TV's own "Match content frame rate" preference — see [systemMatchPreference]. Restated
    // rather than referenced so the values are readable below API 31, where the platform constants do
    // not exist. Values match DisplayManager.MATCH_CONTENT_FRAMERATE_*.
    internal const val MATCH_NEVER = 0
    internal const val MATCH_SEAMLESS_ONLY = 1
    internal const val MATCH_ALWAYS = 2

    /** One display mode, reduced to what the choice needs — so [chooseMode] runs in unit tests. */
    internal data class ModeSpec(val id: Int, val width: Int, val height: Int, val hz: Float)

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingReset: Runnable? = null
    private var pendingApply: Runnable? = null
    /** Uptime of the last mode change actually pushed to the window; 0 = none this session. */
    private var lastChangeAtMs = 0L

    private fun cancelPendingReset() {
        pendingReset?.let { handler.removeCallbacks(it) }
        pendingReset = null
    }

    private fun cancelPendingApply() {
        pendingApply?.let { handler.removeCallbacks(it) }
        pendingApply = null
    }

    /**
     * Request a display mode matching [fps] for [activity]'s window. Pass fps <= 0 to leave the current
     * mode alone (an unknown frame rate is not a reason to switch anything).
     *
     * **Rate-limited.** A change that would land within [MODE_CHANGE_COOLDOWN_MS] of the previous one is
     * deferred, not dropped, and a later request replaces the deferred one. This matters most on Live TV:
     * raw MPEG-TS rarely declares a frame rate, so `videoFps` there is a *measurement* that is re-sampled
     * several times per tune (`LivePreviewEngine.FPS_MAX_ATTEMPTS`). Readings that drift across a boundary
     * — 24.6 then 25.1 — used to resolve to different modes and fire a separate HDMI handshake each time,
     * blanking the picture repeatedly on a stream that never changed frame rate at all. [snapFps] removes
     * most of that drift; this collapses whatever is left into one switch.
     */
    fun apply(activity: Activity, fps: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || fps <= 0f) return
        cancelPendingApply()
        runCatching {
            // The pending reset is cancelled only once a mode is actually going to be requested. Cancelling
            // up front killed the restore-to-default even when nothing matched this item's frame rate — so
            // leaving a 24p film for something the panel has no mode for kept the display at 24Hz.
            val target = pickMode(activity, fps)
            // No match keeps nothing from the last item: a 50p channel after a 30p film on a panel with no
            // 50 Hz mode used to stay at 30 Hz and lose every other frame. With nothing matching, a mode
            // this app requested goes back to the default.
            val targetId = nextModeId(target?.modeId, activity.window.attributes.preferredDisplayModeId)
            if (target != null || targetId != null) cancelPendingReset()
            if (targetId == null) return
            val waitMs = MODE_CHANGE_COOLDOWN_MS - (android.os.SystemClock.uptimeMillis() - lastChangeAtMs)
            if (lastChangeAtMs != 0L && waitMs > 0) {
                Log.i(TAG, "AFR: ${fps}fps -> mode $targetId deferred ${waitMs}ms (cooldown)")
                // Re-resolve on the way out rather than capturing `target`: by then the fps may have moved
                // again, and the newest reading is the one worth acting on.
                val task = Runnable { pendingApply = null; apply(activity, fps) }
                pendingApply = task
                handler.postDelayed(task, waitMs)
                return
            }
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = targetId }
            lastChangeAtMs = android.os.SystemClock.uptimeMillis()
            if (target == null) {
                Log.i(TAG, "AFR: video ${fps}fps matches no mode -> display default")
            } else {
                Log.i(TAG, "AFR: video ${fps}fps -> display mode ${target.modeId} (${target.refreshRate}Hz)")
            }
        }.onFailure { Log.w(TAG, "AFR apply failed: ${it.message}") }
    }

    /**
     * The mode id to request next, or null to leave the window alone. [chosenId] is the matching mode
     * (null when none matches, or the TV is set to never match), [requestedId] what the window asks for
     * now (0 = the system default). Reference: upstream OwnTV 8f47da68.
     */
    internal fun nextModeId(chosenId: Int?, requestedId: Int): Int? {
        val target = chosenId ?: 0
        return target.takeIf { it != requestedId }
    }

    /**
     * The best display mode for [fps] at the CURRENT resolution, or null when nothing matches.
     *
     * Shared by [apply] and [betterRefreshRateFor] so the "Auto frame rate would help here" prompt (F13)
     * can never promise a switch [apply] would not make.
     *
     * Ordering, highest priority first:
     *  1. **Seamless** switches (API 31+). A mode listed in the current mode's `alternativeRefreshRates`
     *     is one the panel can move to without re-handshaking HDMI, i.e. without the black gap that makes
     *     a live stream look like it paused. Media3's own AFR path is pinned to seamless-only for exactly
     *     this reason; this one only *prefers* it, because on most TVs 60→50 is not seamless and refusing
     *     it outright would silently turn AFR off for the content that needs it most.
     *  2. The lowest refresh-rate multiple (a true 24Hz beats 72Hz).
     *  3. The closest rate within that multiple.
     */
    private fun pickMode(activity: Activity, fps: Float): Display.Mode? {
        val display: Display = displayOf(activity) ?: return null
        val current = display.mode ?: return null
        val seamless = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            current.alternativeRefreshRates.toList()
        } else {
            emptyList()
        }
        val modes = display.supportedModes?.toList().orEmpty()
        val chosenId = chooseMode(
            modes = modes.map { it.spec() },
            current = current.spec(),
            seamlessRates = seamless,
            systemPreference = systemMatchPreference(activity),
            fps = fps,
        ) ?: return null
        return modes.firstOrNull { it.modeId == chosenId }
    }

    private fun Display.Mode.spec() = ModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)

    /**
     * The id of the best mode for [fps] among [modes] at the [current] resolution, or null when nothing
     * matches or the TV's [systemPreference] rules switching out. Pure, so it is unit-tested.
     *
     * - [MATCH_NEVER]: the user told Android TV never to match content, so no mode is chosen.
     * - [MATCH_SEAMLESS_ONLY]: only modes the panel reaches without an HDMI re-sync ([seamlessRates]), or
     *   staying on the current mode (Android lists only the OTHER rates as a mode's alternatives).
     * - [MATCH_ALWAYS] (and every Android below 12, which has no such setting): seamless first, then the
     *   lowest multiple, then the closest rate, as before.
     */
    internal fun chooseMode(
        modes: List<ModeSpec>,
        current: ModeSpec,
        seamlessRates: List<Float>,
        systemPreference: Int,
        fps: Float,
    ): Int? {
        if (systemPreference == MATCH_NEVER) return null
        val wanted = snapFps(fps)
        val seamlessOnly = systemPreference == MATCH_SEAMLESS_ONLY
        fun isSeamless(mode: ModeSpec) = seamlessRates.any { abs(it - mode.hz) <= TOLERANCE_HZ }
        // Under seamless-only, staying on the current mode counts as seamless too. Under Always the ranking
        // is exactly the previous one (a true 30 Hz still beats staying at 60 Hz for 30 fps).
        fun preferred(mode: ModeSpec) = isSeamless(mode) || (seamlessOnly && mode.id == current.id)
        return modes
            .filter { it.width == current.width && it.height == current.height }
            .filter { !seamlessOnly || preferred(it) }
            .mapNotNull { mode -> multipleOf(mode.hz, wanted)?.let { mode to it } }
            .minByOrNull { (mode, mult) ->
                (if (preferred(mode)) 0f else 1_000_000f) + mult * 1000f + abs(mode.hz - mult * wanted)
            }
            ?.first?.id
    }

    /**
     * Android TV's "Match content frame rate" choice (Display and sound settings, Android 12+): Never,
     * Seamless only or Always. Below API 31 there is no such setting and the app behaves as before.
     * Reference: upstream OwnTV 26a1a7e9.
     */
    private fun systemMatchPreference(activity: Activity): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return MATCH_ALWAYS
        return runCatching {
            activity.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.matchContentFrameRateUserPreference ?: MATCH_ALWAYS
        }.getOrDefault(MATCH_ALWAYS)
    }

    /**
     * Pull [fps] onto the nearest rate content is really made at, when it is close enough to one.
     *
     * Live TV feeds this a *measured* frame rate (raw MPEG-TS almost never declares one), and a
     * measurement wanders: the same 25fps channel can read 24.6 and then 25.1. Those two land on opposite
     * sides of [TOLERANCE_HZ] and pick different display modes, so the panel re-handshakes for a frame
     * rate that never actually changed. Snapping first makes both readings mean "25".
     *
     * A reading that is nowhere near a standard rate is returned untouched — better to let [multipleOf]
     * find no match and leave the display alone than to invent a rate the content isn't at.
     */
    internal fun snapFps(fps: Float): Float {
        val nearest = STANDARD_FPS.minByOrNull { abs(it - fps) } ?: return fps
        return if (abs(nearest - fps) <= SNAP_MAX_DELTA_HZ) nearest else fps
    }

    /**
     * Hand the display back to the system default (mode id 0) — called when playback stops.
     *
     * Deferred by [RESET_DELAY_MS] and cancelled by any [apply] in the meantime. The video surface is
     * disposed and remounted on ordinary events (the mpv<->ExoPlayer engine swap, the Realtek fresh-
     * Surface reset, dock/expand), and releasing the mode on each of those would make the TV re-handshake
     * HDMI twice for no reason — a visible black flash on many panels. A real stop still releases, just
     * a moment later.
     */
    fun reset(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        cancelPendingReset()
        // A deferred switch must not outlive the playback that asked for it — otherwise stopping during
        // the cooldown still blanks the panel a moment later, for a stream that is already gone.
        cancelPendingApply()
        val task = Runnable { resetNow(activity) }
        pendingReset = task
        handler.postDelayed(task, RESET_DELAY_MS)
    }

    private fun resetNow(activity: Activity) {
        pendingReset = null
        runCatching {
            if (activity.window.attributes.preferredDisplayModeId == 0) return
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
            // Playback stopped and the display is back on its default, so the next tune is not a "burst"
            // — it starts with a clean cooldown and switches immediately.
            lastChangeAtMs = 0L
            Log.i(TAG, "AFR: display mode released")
        }.onFailure { Log.w(TAG, "AFR reset failed: ${it.message}") }
    }

    /** 1..MAX_MULTIPLE if [refreshRate] is (about) that many times [fps], else null. */
    private fun multipleOf(refreshRate: Float, fps: Float): Int? =
        (1..MAX_MULTIPLE).firstOrNull { abs(refreshRate - it * fps) <= TOLERANCE_HZ }

    /**
     * The refresh rate a matching display mode would give for [fps], or null when switching would not
     * help — because the display is already on a clean multiple, or because no mode at the current
     * resolution is one. Used to decide whether suggesting Auto frame rate is honest (F13): on a panel
     * with only a 60 Hz mode, 25 fps judder cannot be fixed by AFR and the app must not pretend it can.
     */
    fun betterRefreshRateFor(activity: Activity, fps: Float): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || fps <= 0f) return null
        return runCatching {
            val display: Display = displayOf(activity) ?: return null
            val current = display.mode ?: return null
            // Snapped, like the mode choice itself: the prompt must judge the same rate [apply] will act on.
            if (multipleOf(current.refreshRate, snapFps(fps)) != null) return null // already clean
            pickMode(activity, fps)?.refreshRate
        }.getOrNull()
    }

    /** The refresh rate the display is on right now, or null if it can't be read. */
    fun currentRefreshRate(activity: Activity): Float? = runCatching {
        displayOf(activity)?.mode?.refreshRate
    }.getOrNull()

    /**
     * The display this activity is showing on. `Activity.getDisplay()` is the supported call from
     * API 30 on; below that `WindowManager.getDefaultDisplay()` is the only way to ask, and on a TV
     * (single display, no multi-window) the two answer the same thing.
     */
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
}

/**
 * Applies [FrameRateController] for as long as this composable is in the tree, following [fps] as the
 * player reports it, and releases the display mode on dispose. Mount it from the full-screen video
 * surfaces only — a mini/preview window must not reconfigure the whole display.
 */
@Composable
fun AutoFrameRateEffect(fps: Float?, enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    // Apply on every fps change, but keep the release in its OWN effect keyed only on the activity —
    // keying the disposal on fps too would reset the display to default and re-request on each fps
    // update, i.e. an extra HDMI mode flip per change.
    LaunchedEffect(activity, enabled, fps) {
        if (activity == null) return@LaunchedEffect
        if (enabled) FrameRateController.apply(activity, fps ?: 0f) else FrameRateController.reset(activity)
    }
    DisposableEffect(activity) {
        onDispose { if (activity != null) FrameRateController.reset(activity) }
    }
}

