package tv.own.owntv.features.tutorials

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** One way of opening a video: an explicit app package (or any handler when null) and a URI. */
internal data class YouTubeLaunchCandidate(val packageName: String?, val uri: String)

internal sealed interface YouTubeLaunchResult {
    data class Launched(val candidate: YouTubeLaunchCandidate) : YouTubeLaunchResult
    data object InvalidVideo : YouTubeLaunchResult
    data object NoCompatibleApp : YouTubeLaunchResult
}

/**
 * Opens a tutorial in YouTube without an in-app player. Every URI is built here from a validated
 * 11-character id ([YouTubeVideoId]); catalog data never supplies a URL, so nothing arbitrary can be
 * launched. Order: the YouTube app for Android TV, YouTube on Fire TV, the phone/tablet YouTube app,
 * then any app that registered the `vnd.youtube:` scheme, then any browser / handler for the
 * youtube.com watch link. When nothing accepts it the caller shows a message.
 */
internal object YouTubeLauncher {
    const val YOUTUBE_ANDROID_TV = "com.google.android.youtube.tv"
    const val YOUTUBE_FIRE_TV = "com.amazon.firetv.youtube"
    const val YOUTUBE_MOBILE = "com.google.android.youtube"

    fun candidates(videoId: String): List<YouTubeLaunchCandidate> {
        if (!YouTubeVideoId.isValid(videoId)) return emptyList()
        val watch = "https://www.youtube.com/watch?v=$videoId"
        return listOf(
            YouTubeLaunchCandidate(YOUTUBE_ANDROID_TV, watch),
            YouTubeLaunchCandidate(YOUTUBE_FIRE_TV, watch),
            YouTubeLaunchCandidate(YOUTUBE_MOBILE, watch),
            YouTubeLaunchCandidate(null, "vnd.youtube:$videoId"),
            YouTubeLaunchCandidate(null, watch),
        )
    }

    /** Tries each candidate in order with [start] (true = an activity accepted it). */
    fun launch(videoId: String, start: (YouTubeLaunchCandidate) -> Boolean): YouTubeLaunchResult {
        val list = candidates(videoId)
        if (list.isEmpty()) return YouTubeLaunchResult.InvalidVideo
        for (candidate in list) if (start(candidate)) return YouTubeLaunchResult.Launched(candidate)
        return YouTubeLaunchResult.NoCompatibleApp
    }

    fun launch(context: Context, videoId: String): YouTubeLaunchResult = launch(videoId) { candidate ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(candidate.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Explicit YouTube apps get the plain VIEW intent their filters expect; an unnamed handler
        // must be one that accepts links from browsers (BROWSABLE), not an arbitrary internal activity.
        if (candidate.packageName != null) intent.setPackage(candidate.packageName) else intent.addCategory(Intent.CATEGORY_BROWSABLE)
        // Never hand the link back to this app.
        if (candidate.packageName == null && intent.resolveActivity(context.packageManager)?.packageName == context.packageName) {
            return@launch false
        }
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w("VideoTutorials", "launch refused by ${candidate.packageName ?: "handler"}")
            false
        }
    }
}
