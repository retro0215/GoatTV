package tv.own.owntv.features.versions

import tv.own.owntv.core.catalog.versions.ContentVersion
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.player.DeviceVideoCaps
import tv.own.owntv.player.EnginePreference

/** Building picker rows from VersionRepository results — the same for films, series and episodes. */
object VersionOptions {

    /** Whether films and episodes start on ExoPlayer (the only engine Dolby Vision is trusted on). */
    fun startsOnExo(preference: EnginePreference): Boolean = !preference.startsOnMpv

    /**
     * The row for [version]. [itemId] defaults to the version's own row; an episode row passes the
     * matching episode. Progress, favourite and availability come from the caller, which owns them.
     */
    fun option(
        version: ContentVersion,
        playlistName: String?,
        caps: DeviceVideoCaps,
        startsOnExo: Boolean,
        itemId: Long = version.row.id,
        positionMs: Long = 0,
        durationMs: Long = 0,
        completed: Boolean = false,
        progressUpdatedAt: Long = 0,
        isFavorite: Boolean = false,
        unavailable: VersionOption.Unavailable? = null,
        isCurrent: Boolean = false,
    ): VersionOption {
        val row = version.row
        val label = version.label
        return VersionOption(
            itemId = itemId,
            key = CustomizeKeys.item(row.sourceId, row.remoteId, row.name),
            qualityRank = label.qualityRank,
            picture = label.advertisedPicture,
            language = label.language,
            playlistName = playlistName,
            categoryName = row.categoryName,
            positionMs = positionMs,
            durationMs = durationMs,
            completed = completed,
            progressUpdatedAt = progressUpdatedAt,
            isFavorite = isFavorite,
            isAnchor = version.isAnchor,
            confirmed = VersionChoice.confirmedPlayable(label.qualityRank, label.advertisedPicture, caps, startsOnExo),
            pictureMayFallBack = VersionChoice.pictureMayFallBack(label.advertisedPicture, caps),
            unavailable = unavailable,
            isCurrent = isCurrent,
        )
    }

    /**
     * One remembered choice per title: the shared signature plus the one year the group agrees on.
     * Stable whichever version the customer opened.
     */
    fun titleKey(found: List<ContentVersion>): String {
        val anchor = found.firstOrNull { it.isAnchor }?.row ?: found.first().row
        val year = found.mapNotNull { it.row.parsedYear }.toSet().singleOrNull()
        return "${anchor.titleSignature}|${year ?: ""}"
    }
}
