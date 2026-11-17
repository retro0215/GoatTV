package tv.own.owntv.player

/**
 * Small, pure volume rules shared by the engines (final audio reliability fixes).
 */
object VolumeLevels {

    /**
     * A remembered per-item level to restore when the item is opened again, or null to keep the engine's
     * default. Zero is never restored: a channel stepped down to 0 once would otherwise open silent forever
     * (and on Live TV a 0 also deselects the audio track). The stored row is left as it is — muting during a
     * session still works, and the next real level the customer sets replaces it.
     */
    fun restorable(saved: Int?): Int? = saved?.takeIf { it > 0 }?.coerceAtMost(VolumeBoost.MAX_VOLUME)

    /**
     * Live TV's level for a fresh tune from Settings → Default volume. A default of 0 was never applied to
     * Live TV (the player always ran at full gain), so it must not start silencing channels now; any other
     * value is the customer's chosen starting level.
     */
    fun liveTuneLevel(defaultVolume: Int): Int =
        defaultVolume.takeIf { it > 0 }?.coerceAtMost(VolumeBoost.MAX_VOLUME) ?: 100

    /** The player's own 0–1 gain for [level]; 100–150 % is the LoudnessEnhancer's job, not this one. */
    fun playerGain(level: Int, muted: Boolean): Float = if (muted) 0f else level.coerceIn(0, 100) / 100f
}
