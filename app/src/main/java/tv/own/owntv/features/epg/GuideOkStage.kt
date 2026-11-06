package tv.own.owntv.features.epg

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * 4.6.0: OK on a programme in the TV Guide works in two stages. The first OK plays the programme's channel in
 * the Guide's preview (its live broadcast, whatever time the programme is at) and the Guide stays open; a
 * second OK on the same programme opens its details (Watch, reminder, catch-up). A state, not a double-click:
 * the second press may come any time later. Moving to another programme or channel starts over.
 *
 * A channel already playing in the preview skips the first stage: OK opens the programme at once, and the
 * channel is never tuned again.
 *
 * Pure rules, so the Guide's key handling can be tested without Compose.
 */
object GuideOkStage {

    /** What OK is on: the channel, and the start of the programme highlighted on it (null on a row without
     *  guide data). Start, not the whole row: a Now/Next or details refresh keeps the same programme. */
    data class Target(val channelId: Long, val programmeStartMs: Long?)

    fun target(channelId: Long, programme: EpgProgrammeEntity?): Target = Target(channelId, programme?.startMs)

    enum class Action { PREVIEW, OPEN_PROGRAMME }

    /**
     * OK released on [focused]; [armed] is the programme the previous OK previewed (null: none yet), and
     * [previewingChannelId] the channel playing in the Guide's preview now (null: none). OK on the channel
     * already in the preview opens the programme straight away — a first OK there would change nothing.
     */
    fun onOk(armed: Target?, focused: Target, previewingChannelId: Long? = null): Action = when {
        armed == focused -> Action.OPEN_PROGRAMME
        previewingChannelId != null && focused.channelId == previewingChannelId -> Action.OPEN_PROGRAMME
        else -> Action.PREVIEW
    }

    /** What the shell did with a first OK's preview request. */
    enum class Preview {
        /** The channel now plays in the preview: the Guide waits for a second OK. */
        TUNED,
        /** The preview already shows it (nothing is re-tuned): open the programme now. */
        ALREADY_PLAYING,
        /** No in-app preview (external player, mini player, audio bar): open the programme now. */
        UNAVAILABLE,
    }

    /** After a first OK: whether the programme opens now ([Action.OPEN_PROGRAMME]) or the stage waits (null). */
    fun afterPreview(result: Preview): Action? = if (result == Preview.TUNED) null else Action.OPEN_PROGRAMME

    /** The highlight moved: the stage carries on only while it is still on the same programme. */
    fun afterMove(armed: Target?, focused: Target?): Target? = armed?.takeIf { it == focused }
}
