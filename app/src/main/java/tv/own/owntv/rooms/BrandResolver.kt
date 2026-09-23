package tv.own.owntv.rooms

import tv.own.owntv.BuildConfig

object BrandResolver {
    fun resolveBrandId(applicationId: String = BuildConfig.APPLICATION_ID): String {
        return when (applicationId) {
            "tv.own.owntv" -> "goat"
            "tv.allaccess.app" -> "allaccess"
            "tv.fivestar.ultra" -> "fivestar"
            "tv.supreme.app" -> "supreme"
            else -> "goat"
        }
    }
}
