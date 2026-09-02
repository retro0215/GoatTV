package tv.own.owntv.core.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Singleton state for the AllAccess in-app notification banner.
 * Foreground only.
 */
object AllAccessBannerState {
    data class BannerData(
        val title: String,
        val message: String,
        val link: String?,
        val tick: Long = System.currentTimeMillis()
    )

    var currentBanner by mutableStateOf<BannerData?>(null)
        private set

    fun show(title: String, message: String, link: String?) {
        currentBanner = BannerData(title, message, link)
    }

    fun dismiss() {
        currentBanner = null
    }
}
