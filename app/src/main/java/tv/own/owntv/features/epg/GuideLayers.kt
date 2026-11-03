package tv.own.owntv.features.epg

/**
 * Phase 4 rendering: which parts of the Guide get their own cached GPU layer.
 *
 * The onn redraws its whole window every frame (no partial updates), and after Phase 3F the remaining
 * jank was the render thread issuing that frame: ~90 draw operations and two render passes per move,
 * 12-24 ms. A cached (offscreen) layer is drawn into once and then composited as one texture until its
 * own content changes, so:
 *  - the panel's background (fill, border, light gradients) is drawn once;
 *  - each row's static content (channel cell, programme cells, texts) is drawn when that row's data,
 *    texts, clock or horizontal scroll change — not when the cursor moves past it, and not when the
 *    list scrolls vertically (the layer just moves);
 *  - the focus highlight stays outside the layer, so a move redraws two small overlays.
 * The video preview is a SurfaceView (a hardware overlay) and never sits inside a cached layer: a
 * layer would capture its transparent hole instead of punching it through to the video.
 *
 * A horizontal timeline scroll still redraws every visible row's layer (an extra render pass each), so
 * the effect has to be measured on the device; [PROPERTY] turns the layers off in debug builds for an
 * A/B trace with one APK.
 */
internal object GuideLayers {

    /** `adb shell setprop debug.owntv.guide.layers 0`, then reopen the Guide: rows and background drawn
     *  as in 4.6 Phase 3F. Debug (and GoatTV Test) builds only; release builds never read it. */
    const val PROPERTY = "debug.owntv.guide.layers"

    fun enabled(debuggable: Boolean, property: String?): Boolean = !(debuggable && property?.trim() == "0")

    /** Reads [PROPERTY]; only called by debuggable builds. */
    fun readProperty(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, PROPERTY) as? String
    }.getOrNull()
}
