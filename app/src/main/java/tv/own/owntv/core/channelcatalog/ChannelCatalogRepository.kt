package tv.own.owntv.core.channelcatalog

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.own.owntv.BuildConfig
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.metadata.OwnTVClientId
import tv.own.owntv.rooms.BrandResolver
import tv.own.owntv.rooms.SupabaseClientProvider

@Serializable
data class CatalogChannelDto(
    @SerialName("channelName") val channelName: String,
    @SerialName("remoteId") val remoteId: String,
    @SerialName("epgChannelId") val epgChannelId: String? = null,
    @SerialName("logoUrl") val logoUrl: String? = null,
    @SerialName("categoryName") val categoryName: String? = null
)

@Serializable
data class CatalogIngestPayload(
    @SerialName("sourceType") val sourceType: String,
    @SerialName("claimedBrand") val claimedBrand: String,
    @SerialName("channels") val channels: List<CatalogChannelDto>
)

class ChannelCatalogRepository(
    private val context: Context,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val okHttpClient: OkHttpClient
) {
    private val clientIdHelper = OwnTVClientId(context)
    private val client = SupabaseClientProvider.client

    suspend fun syncSourceCatalog(source: SourceEntity) {
        val typeStr = when (source.type) {
            SourceType.XTREAM -> "XTREAM"
            SourceType.M3U -> "M3U"
            SourceType.STALKER -> "STALKER"
            else -> {
                Log.d("ChannelCatalog", "Skipping unsupported source type: ${source.type}")
                return
            }
        }

        val brandId = BrandResolver.resolveBrandId()
        val clientId = clientIdHelper.get()

        val channels = channelDao.allForSources(listOf(source.id), Int.MAX_VALUE)
        if (channels.isEmpty()) return

        val categories = categoryDao.getAllForSourceOnce(source.id).associateBy { it.id }

        val catalogChannels = channels.mapNotNull { ch ->
            val remoteId = ch.remoteId?.trim()
            if (remoteId.isNullOrBlank()) return@mapNotNull null

            val categoryName = ch.categoryId?.let { categories[it]?.name }

            CatalogChannelDto(
                channelName = ch.name,
                remoteId = remoteId,
                epgChannelId = ch.epgChannelId?.takeIf { it.isNotBlank() },
                logoUrl = ch.logoUrl?.takeIf { it.isNotBlank() },
                categoryName = categoryName?.takeIf { it.isNotBlank() }
            )
        }

        if (catalogChannels.isEmpty()) return

        val batches = catalogChannels.chunked(200)
        val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/channel-catalog-ingest"
        val anonKey = BuildConfig.SUPABASE_ANON_KEY
        val accessToken = runCatching { client.auth.currentSessionOrNull()?.accessToken }.getOrNull() ?: anonKey

        for ((index, batch) in batches.withIndex()) {
            val payload = CatalogIngestPayload(
                sourceType = typeStr,
                claimedBrand = brandId,
                channels = batch
            )

            val jsonBody = kotlinx.serialization.json.Json.encodeToString(CatalogIngestPayload.serializer(), payload)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("X-OwnTV-Client", clientId)
                .post(requestBody)
                .build()

            val success = runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val clientPrefix = clientId.take(8)
                    Log.d("ChannelCatalog", "Batch ${index + 1}/${batches.size} submitted for brand=$brandId type=$typeStr size=${batch.size} status=$code client=$clientPrefix...")
                    if (code == 429) {
                        Log.w("ChannelCatalog", "Rate limited (429). Stopping remaining batches for this sync.")
                        false
                    } else if (code >= 400) {
                        val respBody = response.body?.string().orEmpty().take(200)
                        Log.w("ChannelCatalog", "Catalog ingestion error code $code: $respBody")
                        false
                    } else {
                        true
                    }
                }
            }.getOrElse { e ->
                Log.e("ChannelCatalog", "Network error submitting catalog batch ${index + 1}: ${e.message}", e)
                false
            }

            if (!success) break
        }
    }
}
