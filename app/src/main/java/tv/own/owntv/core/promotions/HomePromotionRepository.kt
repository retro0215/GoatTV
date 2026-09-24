package tv.own.owntv.core.promotions

import android.annotation.SuppressLint
import android.util.Log
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tv.own.owntv.BuildConfig
import tv.own.owntv.rooms.BrandResolver
import java.time.Instant

@Serializable
data class PromotionDto(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("cta_text") val ctaText: String? = null,
    @SerialName("cta_action") val ctaAction: String? = "none",
    val active: Boolean = false,
    val featured: Boolean = false,
    @SerialName("publish_starts_at") val publishStartsAt: String? = null,
    @SerialName("publish_ends_at") val publishEndsAt: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("room_id") val roomId: String? = null
)

@Serializable
data class PromotionBrandDto(
    @SerialName("promotion_id") val promotionId: String,
    @SerialName("brand_id") val brandId: String
)

data class HomePromotion(
    val id: String,
    val title: String,
    val subtitle: String?,
    val bannerUrl: String?,
    val ctaText: String?,
    val ctaAction: String,
    val featured: Boolean,
    val publishStartsAt: Long?,
    val publishEndsAt: Long?,
    val sortOrder: Int,
    val eventId: String?,
    val roomId: String?
)

class HomePromotionRepository {
    private val publicClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
        }
    }

    @SuppressLint("NewApi")
    suspend fun fetchEligiblePromotions(): List<HomePromotion> {
        val brandId = BrandResolver.resolveBrandId()
        return runCatching {
            val now = Instant.now()
            val promos = publicClient.postgrest["promotions"]
                .select {
                    filter { eq("active", true) }
                }
                .decodeList<PromotionDto>()

            if (promos.isEmpty()) return@runCatching emptyList()

            val promotionIds = promos.map { it.id }
            val promoBrands = publicClient.postgrest["promotion_brands"]
                .select {
                    filter { isIn("promotion_id", promotionIds) }
                }
                .decodeList<PromotionBrandDto>()

            val brandMap = promoBrands.groupBy({ it.promotionId }, { it.brandId })

            promos.mapNotNull { dto ->
                val brands = brandMap[dto.id] ?: emptyList()
                val matchesBrand = brands.any { it.equals(brandId, ignoreCase = true) }

                val startInstant = dto.publishStartsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val endInstant = dto.publishEndsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

                val startMs = startInstant?.toEpochMilli()
                val endMs = endInstant?.toEpochMilli()

                val afterStart = startInstant == null || now.isAfter(startInstant) || now == startInstant
                val beforeEnd = endInstant == null || now.isBefore(endInstant) || now == startInstant

                if (!matchesBrand || !afterStart || !beforeEnd) return@mapNotNull null

                HomePromotion(
                    id = dto.id,
                    title = dto.title,
                    subtitle = dto.subtitle,
                    bannerUrl = dto.bannerUrl,
                    ctaText = dto.ctaText,
                    ctaAction = dto.ctaAction?.ifBlank { "none" } ?: "none",
                    featured = dto.featured,
                    publishStartsAt = startMs,
                    publishEndsAt = endMs,
                    sortOrder = dto.sortOrder,
                    eventId = dto.eventId,
                    roomId = dto.roomId
                )
            }.sortedWith(compareByDescending<HomePromotion> { it.featured }.thenBy { it.sortOrder })
        }.getOrElse { e ->
            Log.e("HomePromoRepo", "Failed to fetch promotions: ${e.message}", e)
            emptyList()
        }
    }
}
