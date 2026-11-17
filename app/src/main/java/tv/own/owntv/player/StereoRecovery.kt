package tv.own.owntv.player

/**
 * The customer's one-tap "No sound? Switch to stereo" for ONE live engine.
 *
 * Session-only and engine-scoped on purpose. It never writes the saved Surround setting and never touches
 * [AudioOutputPolicy], which every other player reads (Multiscreen tiles, VOD, Home hero), so pressing it
 * on Live TV/Sports changes nothing anywhere else. It survives channel changes, because the fault it works
 * around lives in the TV/HDMI path rather than in one channel. It ends when the customer leaves the
 * playback session ([clear]) or changes the Surround setting.
 *
 * Not thread-safe: the live engine calls it from the main thread only.
 */
class StereoRecoverySession {

    /** The override is in force: this engine's next player is built on the stereo PCM sink. */
    var active: Boolean = false
        private set

    /** A rebuild for the override is under way; further requests are refused until it settles. */
    var inFlight: Boolean = false
        private set

    /** Sink choice for this engine: the user's mode, or stereo while the override is in force. */
    fun forceStereo(mode: SurroundMode): Boolean = !AudioOutputPolicy.allowsMultichannel(mode) || active

    /**
     * Whether the action may be offered. Hidden when it cannot help or should not run:
     * the brand flag is off, nothing is tuned, the stream has no audio, the audio is intentionally
     * silenced (muted preview, Multiscreen suspension), the output is already stereo ("Stereo only" or a
     * recovery already applied), or a recovery is still rebuilding.
     */
    fun isAvailable(
        featureEnabled: Boolean,
        mode: SurroundMode,
        tuned: Boolean,
        hasAudioTrack: Boolean,
        silencedOnPurpose: Boolean,
    ): Boolean = featureEnabled && tuned && hasAudioTrack && !silencedOnPurpose &&
        !forceStereo(mode) && !inFlight

    /** Start a recovery. False (and no state change) when one is running or already applied. */
    fun begin(): Boolean {
        if (inFlight || active) return false
        active = true
        inFlight = true
        return true
    }

    /** The rebuild ended. A failed one gives the override back, so the customer is not left stuck in it. */
    fun finish(success: Boolean) {
        inFlight = false
        if (!success) active = false
    }

    /** End the session (left playback, or the Surround setting changed). Returns whether it was active. */
    fun clear(): Boolean {
        val was = active
        active = false
        inFlight = false
        return was
    }
}
