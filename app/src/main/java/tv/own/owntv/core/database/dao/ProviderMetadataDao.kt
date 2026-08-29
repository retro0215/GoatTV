package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tv.own.owntv.core.database.entity.ProviderMetadataEntity

/**
 * DAO for the provider-specific rich metadata cache.
 */
@Dao
interface ProviderMetadataDao {
    @Query("SELECT * FROM provider_metadata_cache WHERE key = :key LIMIT 1")
    suspend fun getMetadata(key: String): ProviderMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(entity: ProviderMetadataEntity)

    @Query("DELETE FROM provider_metadata_cache WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("DELETE FROM provider_metadata_cache")
    suspend fun clearAll()
}
