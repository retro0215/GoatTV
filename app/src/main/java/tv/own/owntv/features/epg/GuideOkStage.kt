package tv.own.owntv.features.epg

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * 4.6.0: OK on a programme in the TV Guide works in two stages. The first OK plays the programme's channel in
 * the Guide's preview (its live broadcast, whatever time the programme is at) and the Guide stays open; a
 * second OK on the same programme opens its details (Watch, reminder, catch-up). A state, not a double-click:
 * the second press may come any time later. Moving to another programme or channel starts over.
 *
 * Pure rules, so the Guide's key handling can be tested without Compose.
 */
internal object GuideOkStage {

    /** What OK is on: the channel, and the start of the programme highlighted on it (null on a row without
     *  guide data). Start, not the whole row: a Now/Next or details refresh keeps the same programme. */
    data class Target(val channelId: Long, val programmeStartMs: Long?)

    fun target(channelId: Long, programme: EpgProgrammeEntity?): Target = Target(channelId, programme?.startMs)

    enum class Action { PREVIEW, OPEN_PROGRAMME }

    /** OK released on [focused]; [armed] is the programme the previous OK previewed (null: none yet). */
    fun onOk(armed: Target?, focused: Target): Action =
        if (armed == focused) Action.OPEN_PROGRAMME else Action.PREVIEW

    /** The highlight moved: the stage carries on only while it is still on the same programme. */
    fun afterMove(armed: Target?, focused: Target?): Target? = armed?.takeIf { it == focused }
}
