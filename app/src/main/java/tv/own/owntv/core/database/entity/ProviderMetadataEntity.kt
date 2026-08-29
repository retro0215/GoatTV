package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * On-demand cache for rich metadata fetched directly from the IPTV provider (e.g. Xtream get_vod_info).
 * Keeps the main content tables lean and prevents sync overwrites.
 */
@Entity(tableName = "provider_metadata_cache")
data class ProviderMetadataEntity(
    /** Stable key: "movie:<sourceId>:<remoteId>" */
    @PrimaryKey val key: String,
    val sourceId: Long,
    val remoteId: String,
    val title: String?,
    val plot: String?,
    val rating: Double?,
    val releaseDate: String?,
    val year: Int?,
    val genre: String?,
    val durationSecs: Int?,
    val director: String?,
    val actors: String?,
    val trailer: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val tmdbId: String?,
    val updatedAt: Long
)
